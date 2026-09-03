package com.boc.nl2sql.service.conversation;

import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.domain.authorization.RoleCode;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.controller.conversation.*;
import com.boc.nl2sql.service.conversation.QueryApplicationService;
import com.boc.nl2sql.model.ModelGateway;
import com.boc.nl2sql.model.QueryInterpretation;
import com.boc.nl2sql.model.SqlResultReview;
import com.boc.nl2sql.service.nl2sql.RuleBasedSemanticParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 显式开启才访问本地模拟数据库；不连接真实模型。保留验收任务与审计记录。 */
@SpringBootTest(properties = {"app.query.execution-timeout-seconds=1", "app.model.provider=mock"})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "v11.mysql", matches = "true")
class QueryLifecycleMysqlTest {
    @Autowired QueryApplicationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.boc.nl2sql.service.conversation.ConversationStore conversations;
    @Autowired org.springframework.core.env.Environment environment;
    @MockitoBean ModelGateway model;
    private final CurrentUser director = new CurrentUser(3L, "director01", "负责人", RoleCode.ORG_MANAGER, "EAST", null, null);
    private final String normalSql = "SELECT c.age_band_code, COUNT(*) AS customer_count, AVG(c.total_asset_amount) AS avg_asset_amount FROM dim_customer c WHERE c.region_code = 'EAST' GROUP BY c.age_band_code LIMIT 100";
    private final String slowSql = "SELECT SUM(c.total_asset_amount + d.total_asset_amount + e.total_asset_amount) AS v11_cancel_probe FROM dim_customer c JOIN dim_customer d ON d.region_code=c.region_code JOIN dim_customer e ON e.region_code=c.region_code WHERE c.region_code = 'EAST' LIMIT 100";
    @BeforeEach void reviewAligned(){when(model.reviewResult(anyString(),eq(director),anyString(),anyMap(),anyBoolean())).thenReturn(new SqlResultReview(true,"结构一致"));}
    private QueryInterpretation plan(String sql) {
        return new QueryInterpretation(new RuleBasedSemanticParser().parse("分析各年龄段客户数量和平均资产"),
                "DEEPSEEK", 0.95, sql, "v1.1数据库验收", "AUTO", null);
    }
    private String submit() {
        return service.submit(new SubmitQueryRequest(UUID.randomUUID().toString(), "分析各年龄段客户数量和平均资产", "AUTO", true, null), director, "v11-mysql-test").taskId();
    }
    private TaskStatusResponse awaitState(String taskId, String... expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var status = service.status(taskId, director);
            if (java.util.List.of(expected).contains(status.status())) return status;
            if (java.util.List.of("FAILED", "TIMED_OUT", "CANCELLED", "DEGRADED", "SUCCESS").contains(status.status())) {
                throw new AssertionError("Unexpected terminal state: " + status.status() + " " + status.message());
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Task did not reach expected state");
    }
    private boolean probeRunning() {
        return jdbc.queryForList("SHOW FULL PROCESSLIST").stream().anyMatch(row -> row.get("Info") instanceof String info && info.contains("AS v11_cancel_probe"));
    }
    private void awaitProbeStopped() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (probeRunning() && System.nanoTime() < until) Thread.sleep(40);
        assertThat(probeRunning()).isFalse();
    }

    @Test
    void jdbcTimeoutActuallyStopsMysqlAndConnectionPoolRemainsUsable() throws Exception {
        when(model.interpret(anyString(), eq(director), any(), anyBoolean())).thenReturn(plan(slowSql));
        String id = submit();
        var status = awaitState(id, "TIMED_OUT");
        assertThat(status.repairAttempts()).isZero();
        verify(model, never()).repair(anyString(), any(), anyString(), anyString(), anyBoolean());
        awaitProbeStopped();
        when(model.interpret(anyString(), eq(director), any(), anyBoolean())).thenReturn(plan(normalSql));
        assertThat(awaitState(submit(), "SUCCESS").result().charts()).hasSize(2);
    }

    @Test
    void cancelStopsRunningMysqlAndIsIdempotentAndOwned() throws Exception {
        when(model.interpret(anyString(), eq(director), any(), anyBoolean())).thenReturn(plan(slowSql));
        String id = submit();
        awaitState(id, "EXECUTING");
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
        while (!probeRunning() && System.nanoTime() < until) Thread.sleep(10);
        assertThat(probeRunning()).isTrue();
        assertThat(service.cancel(id, director, "cancel-test").status()).isEqualTo("CANCELLED");
        awaitProbeStopped();
        assertThat(service.cancel(id, director, "repeat-cancel").status()).isEqualTo("CANCELLED");
        var other = new CurrentUser(1L, "manager01", "经理", RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");
        assertThatThrownBy(() -> service.cancel(id, other, "wrong-owner"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.code()).isEqualTo(404001));
        verify(model, never()).repair(anyString(), any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void realSqlErrorsAreRepairedAtMostTwiceBeforeSafeTemplateExecution() throws Exception {
        var broken = plan(normalSql.replace("AVG(c.total_asset_amount)", "SUM(COUNT(c.customer_id))"));
        when(model.interpret(anyString(), eq(director), any(), anyBoolean())).thenReturn(broken);
        when(model.repair(anyString(), eq(director), anyString(), anyString(), anyBoolean())).thenReturn(broken);
        var status = awaitState(submit(), "DEGRADED");
        assertThat(status.repairAttempts()).isEqualTo(2);
        assertThat(status.result().fallback().dataAvailable()).isTrue();
        assertThat(status.result().charts()).hasSize(2);
        assertThat(status.repairs()).hasSize(2).allSatisfy(repair->{
            assertThat(repair.triggerPhase()).isEqualTo("EXECUTION");
            assertThat(repair.repairReason()).contains("MySQL表达错误");
            assertThat(repair.originalSql()).isNotBlank();
        });
        assertThat(conversations.detail(status.sessionId(),director,0,100).get("context")).isNotNull();
        verify(model, times(2)).repair(anyString(), eq(director), anyString(), anyString(), anyBoolean());
    }

    @Test
    void cancellationWinsAgainstLateModelResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(model.interpret(anyString(), eq(director), any(), anyBoolean())).thenAnswer(call -> {
            entered.countDown(); release.await(3, TimeUnit.SECONDS); return plan(normalSql);
        });
        String id = submit();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        try { assertThat(service.cancel(id, director, "cancel-model").status()).isEqualTo("CANCELLED"); }
        finally { release.countDown(); }
        Thread.sleep(200);
        var status = service.status(id, director);
        assertThat(status.status()).isEqualTo("CANCELLED"); assertThat(status.result()).isNull();
    }
    @Test
    void duplicateConcurrentSubmissionsCreateOneTaskAndRejectChangedPayload() throws Exception {
        when(model.interpret(anyString(),eq(director),any(),anyBoolean())).thenReturn(plan(normalSql));
        String session=UUID.randomUUID().toString(),key=UUID.randomUUID().toString();
        var body=new SubmitQueryRequest(session,"分析各年龄段客户数量和平均资产","AUTO",false, null);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(8);
        try{
            var calls=new java.util.ArrayList<java.util.concurrent.Callable<String>>();
            for(int i=0;i<8;i++)calls.add(()->service.submit(body,director,"duplicate-test",key).taskId());
            var ids=new java.util.HashSet<String>();for(var result:pool.invokeAll(calls))ids.add(result.get());
            assertThat(ids).hasSize(1);String id=ids.iterator().next();awaitState(id,"SUCCESS");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_task WHERE user_id=? AND idempotency_key=?",Integer.class,director.userId(),key)).isEqualTo(1);
            assertThatThrownBy(()->service.submit(new SubmitQueryRequest(session,"不同问题","AUTO",false, null),director,"changed",key)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(409005));
            verify(model,times(1)).interpret(anyString(),eq(director),any(),eq(false));
        }finally{pool.shutdownNow();}
    }
    @Test
    void customerSelectionPersistsMessagesAndRestoresPronounContextWithoutModel() throws Exception {
        var manager=new CurrentUser(1L,"manager01","经理",RoleCode.CUSTOMER_MANAGER,"EAST","B001","M0001");
        for(String id:java.util.List.of("C99000001","C99000002"))jdbc.update("INSERT INTO dim_customer(customer_id,customer_name,customer_name_masked,gender_code,age,age_band_code,mobile_masked,customer_level_code,vip_flag,risk_level_code,occupation_code,region_code,branch_id,manager_id,total_asset_amount,asset_change_3m_rate,open_date,status_code,snapshot_date) VALUES(?, '李验甲','李**','M',35,'A26_35','90000008877','NORMAL',false,'R2','OTHER','EAST','B001','M0001',100000,0,CURRENT_DATE,'ACTIVE',CURRENT_DATE) ON DUPLICATE KEY UPDATE customer_name='李验甲'",id);
        String session=UUID.randomUUID().toString();
        String id=service.submit(new SubmitQueryRequest(session,"查询李验甲的资产信息","AUTO",true, null),manager,"identity",UUID.randomUUID().toString()).taskId();
        TaskStatusResponse asking=waitForUser(id,manager,"ASKING");
        assertThat(asking.question().type()).isEqualTo("CUSTOMER_SELECTION");assertThat(asking.question().candidates()).hasSize(2);
        assertThat(asking.question().candidates()).allSatisfy(c->assertThat(c.name()).isEqualTo("李验甲"));
        var workers=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var actions=new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for(int i=0;i<8;i++)actions.add(()->{
                try {service.clarify(session,new ClarificationRequest(id,asking.question().questionId(),"C99000001",java.util.List.of()),manager,"select");return true;}
                catch(BusinessException conflict){assertThat(conflict.code()).isIn(409001,409002,409004);return false;}
            });
            int accepted=0;for(var action:workers.invokeAll(actions))if(action.get())accepted++;
            assertThat(accepted).isEqualTo(1);
        }finally{workers.shutdownNow();}
        var result=waitForUser(id,manager,"SUCCESS");assertThat(result.result().rows()).hasSize(1);
        assertThat(result.result().sqlPreview()).doesNotContain("李验甲");
        var detail=conversations.detail(session,manager,0,100);assertThat(detail.get("context").toString()).contains("C99000001");
        assertThat((java.util.List<?>)detail.get("messages")).hasSize(4);
        String next=service.submit(new SubmitQueryRequest(session,"看看他的产品持有","AUTO",true, null),manager,"followup",UUID.randomUUID().toString()).taskId();
        assertThat(waitForUser(next,manager,"SUCCESS").displayQuery()).contains("C99000001");
        String changed=service.submit(new SubmitQueryRequest(session,"再查询C99000002的资产信息","AUTO",true, null),manager,"change-customer",UUID.randomUUID().toString()).taskId();
        assertThat(waitForUser(changed,manager,"SUCCESS").result().rows().get(0).get("customer_id")).isEqualTo("C99000002");
        assertThatThrownBy(()->conversations.detail(session,director,0,100)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(model);
    }
    @Test
    void sameSessionRejectsAnotherTaskUntilFirstTaskEndsAndEventsAreDurable() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(model.interpret(anyString(),eq(director),any(),anyBoolean())).thenAnswer(call->{entered.countDown();release.await(5,TimeUnit.SECONDS);return plan(normalSql);});
        String session=UUID.randomUUID().toString();var request=new SubmitQueryRequest(session,"分析各年龄段客户数量和平均资产","AUTO",true, null);
        String id=service.submit(request,director,"active",UUID.randomUUID().toString()).taskId();
        assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
        try{
            assertThatThrownBy(()->service.submit(request,director,"another",UUID.randomUUID().toString())).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(409006));
            service.cancel(id,director,"cancel");
        }finally{release.countDown();}
        var events=conversations.events(id,0);assertThat(events.size()).isGreaterThanOrEqualTo(3);
        long last=((Number)events.get(events.size()-1).get("event_id")).longValue();assertThat(conversations.events(id,last)).isEmpty();
        assertThat(events.get(events.size()-1).get("payload_json").toString()).contains("CANCELLED");
    }
    private TaskStatusResponse waitForUser(String id,CurrentUser user,String expected)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){var state=service.status(id,user);if(expected.equals(state.status()))return state;if(java.util.Set.of("FAILED","TIMED_OUT","CANCELLED","DEGRADED").contains(state.status()))throw new AssertionError(state.toString());Thread.sleep(25);}
        throw new AssertionError("等待状态超时："+service.status(id,user));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
        "WITH a AS(SELECT c.customer_id,c.total_asset_amount FROM dim_customer c WHERE c.region_code='EAST') SELECT a.customer_id,ROW_NUMBER() OVER(ORDER BY a.total_asset_amount DESC) AS ranking FROM a LIMIT 10",
        "SELECT c.customer_id FROM dim_customer c WHERE c.region_code='EAST' UNION ALL SELECT d.customer_id FROM dim_customer d WHERE d.region_code='EAST' LIMIT 10",
        "SELECT q.customer_count FROM (SELECT COUNT(*) AS customer_count FROM dim_customer c WHERE c.region_code='EAST') q LIMIT 10",
        "SELECT c.customer_id,(SELECT COUNT(t.transaction_id) FROM fct_transaction t WHERE t.customer_id=c.customer_id) AS transaction_count FROM dim_customer c WHERE c.region_code='EAST' LIMIT 10"
    })
    void complexAstPlansExecuteOnMysql(String sql)throws Exception{
        when(model.interpret(anyString(),eq(director),any(),anyBoolean())).thenReturn(plan(sql));
        var state=awaitState(submit(),"SUCCESS");
        assertThat(state.result().rows()).isNotEmpty().hasSizeLessThanOrEqualTo(10);
    }

    @Test void concurrentConfirmationsConsumeTokenOnce()throws Exception{
        String sql="SELECT c.customer_id FROM dim_customer c JOIN dim_customer d ON d.customer_id=c.customer_id JOIN dim_customer e ON e.customer_id=c.customer_id JOIN dim_customer f ON f.customer_id=c.customer_id WHERE c.region_code='EAST' LIMIT 10";
        when(model.interpret(anyString(),eq(director),any(),anyBoolean())).thenReturn(plan(sql));
        String id=submit();var state=awaitState(id,"CONFIRMING");
        String token=state.confirmation().get("confirm_token").toString();
        var workers=java.util.concurrent.Executors.newFixedThreadPool(8);
        try{
            var actions=new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
            for(int i=0;i<8;i++)actions.add(()->{
                try{service.confirm(id,new ConfirmationRequest(token,"CONFIRM",null),director,"parallel-confirm");return true;}
                catch(BusinessException conflict){assertThat(conflict.code()).isIn(409003,409004);return false;}
            });
            int accepted=0;for(var action:workers.invokeAll(actions))if(action.get())accepted++;
            assertThat(accepted).isEqualTo(1);
        }finally{workers.shutdownNow();}
        awaitState(id,"SUCCESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_task_event WHERE task_id=? AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.status'))='EXECUTING'",Integer.class,id)).isEqualTo(1);
        verify(model,times(1)).interpret(anyString(),eq(director),any(),anyBoolean());
    }

    @Test void rejectedSqlIsLoggedButOnlySafeFallbackIsExecuted()throws Exception{
        // 使用真正不在白名单中的列，验证拒绝后走降级模板
        String sql="SELECT c.customer_secret FROM dim_customer c WHERE c.region_code='EAST' LIMIT 10";
        when(model.interpret(anyString(),eq(director),any(),anyBoolean())).thenReturn(plan(sql));
        String id=submit();var state=awaitState(id,"DEGRADED");
        assertThat(state.repairAttempts()).isEqualTo(1);
        assertThat(state.repairs()).singleElement().satisfies(repair->assertThat(repair.status()).isEqualTo("MODEL_FAILED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_task_event WHERE task_id=? AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.status'))='EXECUTING'",Integer.class,id)).isEqualTo(1);
        var file=java.nio.file.Path.of(environment.getProperty("app.query.sql-log-dir","logs"),"sql-review.log");
        var lines=java.nio.file.Files.readAllLines(file,java.nio.charset.StandardCharsets.UTF_8).stream().filter(line->line.contains(id)).toList();
        assertThat(lines).hasSize(5);
        assertThat(lines.get(0)).contains("GENERATED",sql);assertThat(lines.get(1)).contains("REJECTED","422104",sql);
        assertThat(lines.stream().filter(line->line.contains("\"source\":\"DEEPSEEK\"")&&line.contains("\"phase\":\"EXECUTING\""))).isEmpty();
        assertThat(lines.stream().filter(line->line.contains("\"source\":\"TEMPLATE_FALLBACK\"")&&line.contains("\"phase\":\"EXECUTED\""))).hasSize(1);
        for(String line:lines)assertThatCode(()->java.time.OffsetDateTime.parse(line.substring(0,line.indexOf(' ')))).doesNotThrowAnyException();
        verify(model,times(1)).repair(anyString(),eq(director),eq(sql),anyString(),anyBoolean());
    }
}

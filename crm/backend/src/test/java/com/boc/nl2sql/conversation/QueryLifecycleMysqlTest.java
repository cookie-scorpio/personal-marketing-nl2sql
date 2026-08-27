package com.boc.nl2sql.conversation;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.authorization.domain.RoleCode;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.conversation.api.*;
import com.boc.nl2sql.conversation.application.QueryApplicationService;
import com.boc.nl2sql.model.ModelGateway;
import com.boc.nl2sql.model.QueryInterpretation;
import com.boc.nl2sql.nl2sql.application.RuleBasedSemanticParser;
import org.junit.jupiter.api.Test;
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
    @MockitoBean ModelGateway model;
    private final CurrentUser director = new CurrentUser(3L, "director01", "负责人", RoleCode.ORG_MANAGER, "EAST", null, null);
    private final String normalSql = "SELECT c.age_band_code, COUNT(*) AS customer_count, AVG(c.total_asset_amount) AS avg_asset_amount FROM dim_customer c WHERE c.region_code = 'EAST' GROUP BY c.age_band_code LIMIT 100";
    private final String slowSql = "SELECT SUM(c.total_asset_amount + d.total_asset_amount + e.total_asset_amount) AS v11_cancel_probe FROM dim_customer c JOIN dim_customer d ON d.region_code=c.region_code JOIN dim_customer e ON e.region_code=c.region_code WHERE c.region_code = 'EAST' LIMIT 100";
    private QueryInterpretation plan(String sql) {
        return new QueryInterpretation(new RuleBasedSemanticParser().parse("分析各年龄段客户数量和平均资产"),
                "DEEPSEEK", 0.95, sql, "v1.1数据库验收", "AUTO", null);
    }
    private String submit() {
        return service.submit(new SubmitQueryRequest(UUID.randomUUID().toString(), "分析各年龄段客户数量和平均资产", "AUTO"), director, "v11-mysql-test").taskId();
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
        when(model.interpret(anyString(), eq(director), any())).thenReturn(plan(slowSql));
        String id = submit();
        var status = awaitState(id, "TIMED_OUT");
        assertThat(status.repairAttempts()).isZero();
        verify(model, never()).repair(anyString(), any(), anyString(), anyString());
        awaitProbeStopped();
        when(model.interpret(anyString(), eq(director), any())).thenReturn(plan(normalSql));
        assertThat(awaitState(submit(), "SUCCESS").result().charts()).hasSize(2);
    }

    @Test
    void cancelStopsRunningMysqlAndIsIdempotentAndOwned() throws Exception {
        when(model.interpret(anyString(), eq(director), any())).thenReturn(plan(slowSql));
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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM query_history WHERE task_id=?", Integer.class, id)).isEqualTo(1);
        verify(model, never()).repair(anyString(), any(), anyString(), anyString());
    }

    @Test
    void realSqlErrorsAreRepairedAtMostTwiceBeforeSafeTemplateExecution() throws Exception {
        var broken = plan(normalSql.replace("c.total_asset_amount", "c.missing_asset_for_test"));
        when(model.interpret(anyString(), eq(director), any())).thenReturn(broken);
        when(model.repair(anyString(), eq(director), anyString(), anyString())).thenReturn(broken);
        var status = awaitState(submit(), "DEGRADED");
        assertThat(status.repairAttempts()).isEqualTo(2);
        assertThat(status.result().fallback().dataAvailable()).isTrue();
        assertThat(status.result().charts()).hasSize(2);
        verify(model, times(2)).repair(anyString(), eq(director), anyString(), anyString());
    }

    @Test
    void cancellationWinsAgainstLateModelResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(model.interpret(anyString(), eq(director), any())).thenAnswer(call -> {
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
}

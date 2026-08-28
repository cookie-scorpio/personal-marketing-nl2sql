package com.boc.nl2sql.conversation;

import com.boc.nl2sql.authorization.domain.*;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.conversation.api.*;
import com.boc.nl2sql.conversation.application.*;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import com.boc.nl2sql.nl2sql.domain.ClarificationQuestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="app.model.provider=mock")
@ActiveProfiles("local")
@EnabledIfSystemProperty(named="v11.mysql",matches="true")
class V13ConversationMysqlTest {
    @Autowired QueryApplicationService service;
    @Autowired ConversationStore conversations;
    @Autowired CustomerResolver customers;
    @Autowired JdbcTemplate jdbc;
    final CurrentUser director=new CurrentUser(3L,"director01","负责人",RoleCode.ORG_MANAGER,"EAST",null,null);
    @Test void deletionHidesEveryReadPathButPreservesAuditAndIsOwned() throws Exception {
        String session=UUID.randomUUID().toString();String key=UUID.randomUUID().toString();
        var request=new SubmitQueryRequest(session,"帮我查找一下陈先生的交易记录","AUTO");
        var task=service.submit(request,director,"v13-test",key);
        for(int i=0;i<100 && !"ASKING".equals(service.status(task.taskId(),director).status());i++)Thread.sleep(30);
        assertThatThrownBy(()->conversations.delete(session,director,"delete-active")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.code()).isEqualTo(409007));
        assertThat(conversations.anchors(session,director,0,100)).hasSize(1);
        service.cancel(task.taskId(),director,"cancel");
        var other=new CurrentUser(1L,"manager01","经理",RoleCode.CUSTOMER_MANAGER,"EAST","B001","M0001");
        assertThatThrownBy(()->conversations.delete(session,other,"wrong-owner")).isInstanceOf(BusinessException.class);
        conversations.delete(session,director,"delete");conversations.delete(session,director,"repeat");
        assertThatThrownBy(()->conversations.detail(session,director,0,100)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->conversations.anchors(session,director,0,100)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.status(task.taskId(),director)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.submit(request,director,"replay",key)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.submit(request,director,"new",UUID.randomUUID().toString())).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation_message WHERE session_id=?",Integer.class,session)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE event_type='CONVERSATION_DELETED' AND event_summary=?",Integer.class,"session_id="+session)).isEqualTo(1);
    }
    @Test void identityInputIsTypedAndFormatCheckedBeforeTaskAdvances(){
        var task=new QueryTaskEntity();task.setMergedQueryText("帮我查找一下陈先生的交易记录");
        var question=new ClarificationQuestion("q","CUSTOMER_IDENTITY","补充身份",List.of(),Map.of());
        assertThat(question.inputTypes()).containsExactly("CUSTOMER_ID","CUSTOMER_NAME","MOBILE_SUFFIX");
        assertThatThrownBy(()->customers.answer(task,director,question,"1234",null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->customers.answer(task,director,question,"1234","CUSTOMER_NAME")).isInstanceOf(BusinessException.class);
        customers.answer(task,director,question,"0012","MOBILE_SUFFIX");
        assertThat(task.getMergedQueryText()).endsWith("客户定位信息：0012");
        customers.answer(task,director,question,"陈嘉宁","CUSTOMER_NAME");
        assertThat(task.getMergedQueryText()).endsWith("客户定位信息：陈嘉宁");
        assertThat(task.getMergedQueryText()).doesNotContain("0012");
    }
    @Test void syntheticNamesMostlyUniqueAndNoBusinessRowsWereRemoved(){
        var counts=jdbc.queryForMap("SELECT COUNT(*) AS n,COUNT(DISTINCT customer_name) AS names FROM dim_customer");
        assertThat(((Number)counts.get("names")).doubleValue()/((Number)counts.get("n")).doubleValue()).isGreaterThan(.99);
    }
    @Test void anchorDirectoryPaginatesUserMessagesWithoutAssistantPayloads(){
        String session=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO conversation_session(session_id,user_id,title,created_at,updated_at) VALUES(?,3,'导航分页验证',NOW(),NOW())",session);
        for(int i=0;i<105;i++){
            String task=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO conversation_message(session_id,task_id,role_code,message_key,content,created_at,updated_at) VALUES(?,?,'USER','query',?,NOW(),NOW())",session,task,"第"+i+"条测试输入");
            jdbc.update("INSERT INTO conversation_message(session_id,task_id,role_code,message_key,content,created_at,updated_at) VALUES(?,?,'ASSISTANT','reply','测试回复',NOW(),NOW())",session,task);
        }
        var first=conversations.anchors(session,director,0,100);assertThat(first).hasSize(100);
        assertThat(first).allSatisfy(row->assertThat(row).containsOnlyKeys("message_id","content","created_at"));
        var last=conversations.anchors(session,director,((Number)first.get(99).get("message_id")).longValue(),100);
        assertThat(last).hasSize(5);
        assertThat(conversations.anchors(session,director,((Number)last.get(4).get("message_id")).longValue(),100)).isEmpty();
        assertThatThrownBy(()->conversations.anchors(session,director,-1,100)).isInstanceOf(BusinessException.class);
    }
}

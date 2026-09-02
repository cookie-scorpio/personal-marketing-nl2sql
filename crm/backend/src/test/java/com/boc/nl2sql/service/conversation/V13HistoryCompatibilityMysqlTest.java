package com.boc.nl2sql.service.conversation;

import com.boc.nl2sql.domain.authorization.*;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.service.conversation.ConversationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="app.model.provider=mock")
@ActiveProfiles("local")
@EnabledIfSystemProperty(named="v11.mysql",matches="true")
class V13HistoryCompatibilityMysqlTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ConversationStore store;
    final CurrentUser user=new CurrentUser(3L,"director01","负责人",RoleCode.ORG_MANAGER,"EAST",null,null);
    final CurrentUser other=new CurrentUser(1L,"manager01","经理",RoleCode.CUSTOMER_MANAGER,"EAST","B001","M0001");
    String session(long owner,String created,String updated){
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO conversation_session(session_id,user_id,title,created_at,updated_at) VALUES(?,?,'兼容测试',?,?)",id,owner,created,updated);return id;
    }
    String legacy(String session,String at,String result){
        String task=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO query_task(task_id,session_id,user_id,query_text,merged_query_text,status_code,progress,stage_message,result_json,created_at,updated_at) VALUES(?,?,3,'旧问题','旧问题','SUCCESS',100,'已保存的旧版回答',?,?,DATE_ADD(?,INTERVAL 1 SECOND))",task,session,result,at,at);
        jdbc.update("INSERT INTO conversation_message(session_id,task_id,role_code,message_key,content,created_at,updated_at) VALUES(?,?,'USER','query','旧问题',?,?)",session,task,at,at);return task;
    }
    void recover()throws Exception{
        String sql=new ClassPathResource("db/migration/V8__legacy_replies_and_message_feedback.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(sql.substring(sql.indexOf("INSERT INTO conversation_message")));
    }
    @SuppressWarnings("unchecked")
    @Test void legacyRecoveryIsIdempotentAndInterleavesRepliesByActualTime()throws Exception{
        String session=session(3,"2026-01-01 10:00:00","2026-01-01 10:01:01");
        String first=legacy(session,"2026-01-01 10:00:00","{\"title\":\"旧版结果\",\"summary\":\"保存了42人\",\"rows\":[{\"customer_count\":42}],\"columns\":[{\"key\":\"customer_count\",\"label\":\"人数\"}]}");
        legacy(session,"2026-01-01 10:01:00",null);
        recover();recover();
        var page=(List<Map<String,Object>>)store.detail(session,user,0,2).get("messages");
        assertThat(page).extracting(r->r.get("role_code")).containsExactly("USER","ASSISTANT");
        var old=(List<Map<String,Object>>)store.detail(session,user,((Number)page.get(0).get("message_id")).longValue(),2).get("messages");
        assertThat(old).extracting(r->r.get("role_code")).containsExactly("USER","ASSISTANT");
        assertThat(old.get(1).get("payload").toString()).contains("保存了42人","customer_count=42");
        assertThat(page.get(1).get("payload").toString()).contains("仅恢复已保存的状态或摘要");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation_message WHERE task_id=? AND role_code='ASSISTANT'",Integer.class,first)).isEqualTo(1);
        assertThatThrownBy(()->store.detail(session,other,0,100)).isInstanceOf(BusinessException.class);
        String hidden=session(3,"2026-01-02 10:00:00","2026-01-02 10:00:01");
        String hiddenTask=legacy(hidden,"2026-01-02 10:00:00",null);
        jdbc.update("UPDATE conversation_session SET deleted_at=NOW(3) WHERE session_id=?",hidden);
        recover();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conversation_message WHERE task_id=? AND role_code='ASSISTANT'",Integer.class,hiddenTask)).isZero();
        long foreignCursor=jdbc.queryForObject("SELECT message_id FROM conversation_message WHERE task_id=?",Long.class,hiddenTask);
        assertThatThrownBy(()->store.detail(session,user,foreignCursor,2)).isInstanceOf(BusinessException.class).hasMessageContaining("游标不属于当前会话");
    }
    @Test void sessionOrderUsesCreationTimeAndNeverIncludesAnotherAccount(){
        String older=session(3,"2090-01-01 10:00:00","2090-03-01 10:00:00");
        String newer=session(3,"2090-02-01 10:00:00","2090-02-01 10:00:00");
        String foreign=session(1,"2090-04-01 10:00:00","2090-04-01 10:00:00");
        var before=store.list(user,1,100).stream().map(r->r.get("session_id")).toList();
        assertThat(before.indexOf(newer)).isLessThan(before.indexOf(older));assertThat(before).doesNotContain(foreign);
        store.list(other,1,100);
        assertThat(store.list(user,1,100).stream().map(r->r.get("session_id")).toList()).isEqualTo(before);
        // 仅清理该测试拥有的三条空会话，避免未来日期夹具影响页面验收。
        jdbc.update("DELETE FROM conversation_session WHERE session_id IN (?,?,?)",older,newer,foreign);
    }
    @SuppressWarnings("unchecked")
    @Test void feedbackIsOwnedExclusiveReversibleAndDoesNotReorderConversation()throws Exception{
        String session=session(3,"2026-01-01 11:00:00","2026-01-01 11:00:01");legacy(session,"2026-01-01 11:00:00",null);recover();
        var rows=(List<Map<String,Object>>)store.detail(session,user,0,100).get("messages");
        long id=((Number)rows.get(1).get("message_id")).longValue();
        Object updated=jdbc.queryForObject("SELECT updated_at FROM conversation_session WHERE session_id=?",Object.class,session);
        Object messageTime=jdbc.queryForObject("SELECT updated_at FROM conversation_message WHERE message_id=?",Object.class,id);
        assertThatThrownBy(()->store.feedback(session,id,"UNKNOWN",user)).isInstanceOf(BusinessException.class);
        jdbc.update("UPDATE conversation_message SET payload_json=JSON_SET(payload_json,'$.status','EXECUTING') WHERE message_id=?",id);
        assertThatThrownBy(()->store.feedback(session,id,"LIKE",user)).isInstanceOf(BusinessException.class).hasMessageContaining("尚未完成");
        jdbc.update("UPDATE conversation_message SET payload_json=JSON_SET(payload_json,'$.status','SUCCESS') WHERE message_id=?",id);
        assertThat(store.feedback(session,id,"LIKE",user)).containsEntry("feedback","LIKE");
        assertThat(store.feedback(session,id,"DISLIKE",user)).containsEntry("feedback","DISLIKE");
        assertThat(((List<Map<String,Object>>)store.detail(session,user,0,100).get("messages")).get(1)).containsEntry("feedback","DISLIKE");
        store.feedback(session,id,"NONE",user);
        assertThat(jdbc.queryForObject("SELECT feedback_code FROM quality_feedback_current WHERE message_id=?",String.class,id)).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT feedback_code FROM conversation_message WHERE message_id=?",String.class,id)).isNull();
        assertThatThrownBy(()->store.feedback(session,id,"LIKE",other)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->store.feedback(session,((Number)rows.get(0).get("message_id")).longValue(),"LIKE",user)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM conversation_session WHERE session_id=?",Object.class,session)).isEqualTo(updated);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM conversation_message WHERE message_id=?",Object.class,id)).isEqualTo(messageTime);
        store.delete(session,user,"delete-feedback-test");
        assertThatThrownBy(()->store.feedback(session,id,"LIKE",user)).isInstanceOf(BusinessException.class);
    }
}

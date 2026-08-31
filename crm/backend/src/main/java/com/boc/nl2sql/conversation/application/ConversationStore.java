package com.boc.nl2sql.conversation.application;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.conversation.domain.ConversationContext;
import com.boc.nl2sql.conversation.domain.QueryStatus;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** 所有写入由调用方事务包裹：状态、消息、事件在同一 MySQL 事务中提交。 */
@Service
public class ConversationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TaskSnapshots snapshots;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.boc.nl2sql.audit.ConversationLog conversationLog;
    public ConversationStore(JdbcTemplate jdbc,ObjectMapper json,TaskSnapshots snapshots){this.jdbc=jdbc;this.json=json;this.snapshots=snapshots;}
    public Map<String,Object> lock(String sessionId,CurrentUser user,String title){
        // ON DUPLICATE KEY取得写锁，避免INSERT IGNORE的共享锁随后升级而产生并发死锁。
        jdbc.update("INSERT INTO conversation_session(session_id,user_id,title,created_at,updated_at) VALUES(?,?,?,NOW(3),NOW(3)) ON DUPLICATE KEY UPDATE session_id=VALUES(session_id)",sessionId,user.userId(),title.substring(0,Math.min(160,title.length())));
        var rows=jdbc.queryForList("SELECT * FROM conversation_session WHERE session_id=? FOR UPDATE",sessionId);
        if(rows.isEmpty() || ((Number)rows.get(0).get("user_id")).longValue()!=user.userId() || rows.get(0).get("deleted_at")!=null)throw new BusinessException(404001,"会话不存在");
        return rows.get(0);
    }
    public void own(String id,CurrentUser user){visible(id,user.userId());}
    public void visible(String id,long userId){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM conversation_session WHERE session_id=? AND user_id=? AND deleted_at IS NULL",Integer.class,id,userId)!=1)
            throw new BusinessException(404001,"会话不存在");
    }
    public void lockTask(QueryTaskEntity task){jdbc.queryForList("SELECT session_id FROM conversation_session WHERE session_id=? AND user_id=? FOR UPDATE",task.getSessionId(),task.getUserId());}
    public List<Map<String,Object>> list(CurrentUser user,int page,int size){
        if(page<1||size<1||size>100)throw new BusinessException(400001,"分页参数不正确");
        return jdbc.queryForList("SELECT session_id,title,active_task_id,state_version,created_at,updated_at FROM conversation_session WHERE user_id=? AND deleted_at IS NULL ORDER BY created_at DESC,session_id DESC LIMIT ? OFFSET ?",user.userId(),size,(long)(page-1)*size);
    }
    /** @客户名单：批量校验编号是否在用户数据范围内；返回 (在范围内, 不在范围内) 两个集合。 */
    public record IdScope(java.util.Set<String> inScope, java.util.Set<String> outOfScope) {}
    public IdScope checkIdsScope(java.util.Collection<String> ids, CurrentUser user) {
        if (ids.isEmpty()) return new IdScope(java.util.Set.of(), java.util.Set.of());
        String safeIds = ids.stream().filter(i -> i.matches("C[0-9]{8}")).map(i -> "'" + i + "'")
                .reduce((a, b) -> a + "," + b).orElse("''");
        String condition = switch (user.role()) {
            case CUSTOMER_MANAGER -> "manager_id = '" + user.managerId() + "'";
            case TEAM_LEAD -> "branch_id = '" + user.branchId() + "'";
            case ORG_MANAGER -> "region_code = '" + user.regionCode() + "'";
        };
        var found = new java.util.HashSet<>(jdbc.queryForList(
                "SELECT customer_id FROM dim_customer WHERE status_code='ACTIVE' AND " + condition + " AND customer_id IN (" + safeIds + ")",
                String.class));
        var inScope = new java.util.LinkedHashSet<String>();
        var outOfScope = new java.util.LinkedHashSet<String>();
        for (String id : ids) (found.contains(id) ? inScope : outOfScope).add(id);
        return new IdScope(inScope, outOfScope);
    }

    /** v1.5 客户检索前置校验：返回服务端保存的固定条件，前端无权覆盖。 */
    public CustomerResolver.SearchScope requireActiveCustomerClarification(String id,CurrentUser user){
        own(id,user);
        var session=jdbc.queryForMap("SELECT active_task_id,deleted_at FROM conversation_session WHERE session_id=?",id);
        if(session.get("deleted_at")!=null)throw new BusinessException(404001,"会话不存在");
        var rows=jdbc.queryForList("SELECT status_code,question_json FROM query_task WHERE task_id=?",session.get("active_task_id"));
        if(rows.isEmpty())throw new BusinessException(409001,"当前没有待处理的客户定位");
        String status=(String)rows.get(0).get("status_code");
        Object q=rows.get(0).get("question_json");
        Map<?,?> question=q==null?Map.of():json.readValue(q.toString(),Map.class);
        Object rawType = question.get("type");
        String type = rawType == null ? "" : String.valueOf(rawType);
        if(!"ASKING".equals(status) || !type.startsWith("CUSTOMER_"))throw new BusinessException(409001,"当前没有待处理的客户定位");
        Object rawSlots=question.get("recognized_slots");
        Map<String,String> slots=new LinkedHashMap<>();
        if(rawSlots instanceof Map<?,?> values)values.forEach((key,value)->slots.put(String.valueOf(key),String.valueOf(value)));
        return CustomerResolver.scopeFromSlots(slots);
    }

    public Map<String,Object> detail(String id,CurrentUser user,long before,int size){
        own(id,user);
        if(size<1||size>100)throw new BusinessException(400001,"page_size 必须为1至100");
        var session=jdbc.queryForMap("SELECT session_id,title,active_task_id,state_version,context_json FROM conversation_session WHERE session_id=?",id);
        var rows=before<=0
                ?jdbc.queryForList("SELECT message_id,task_id,role_code,content,payload_json,created_at,updated_at,feedback_code AS feedback FROM conversation_message WHERE session_id=? ORDER BY created_at DESC,message_id DESC LIMIT ?",id,size)
                :jdbc.queryForList("SELECT message_id,task_id,role_code,content,payload_json,created_at,updated_at,feedback_code AS feedback FROM conversation_message WHERE session_id=? AND (created_at,message_id)<(?,?) ORDER BY created_at DESC,message_id DESC LIMIT ?",id,cursorTime(id,before),before,size);
        rows.forEach(row->{Object payload=row.remove("payload_json");row.put("payload",payload==null?null:json.readValue(payload.toString(),Map.class));});
        java.util.Collections.reverse(rows);
        Object context=session.remove("context_json");session.put("context",context==null?null:json.readValue(context.toString(),Map.class));
        session.put("messages",rows);session.put("has_more",rows.size()==size);return session;
    }
    @org.springframework.transaction.annotation.Transactional
    public void delete(String id,CurrentUser user,String requestId){
        var rows=jdbc.queryForList("SELECT user_id,active_task_id,deleted_at FROM conversation_session WHERE session_id=? FOR UPDATE",id);
        if(rows.isEmpty() || ((Number)rows.get(0).get("user_id")).longValue()!=user.userId())throw new BusinessException(404001,"会话不存在");
        var session=rows.get(0);
        if(session.get("deleted_at")!=null)return;
        // v1.5 级联删除：会话有未结束任务时，同一事务内先取消任务再删除。
        // 取消落库后执行器轮询即停，迟到的模型/SQL结果因任务已终态被丢弃，不会复活已删除会话。
        if(session.get("active_task_id")!=null)cascadeCancel(id,(String)session.get("active_task_id"),user,requestId);
        jdbc.update("UPDATE conversation_session SET deleted_at=NOW(3),active_task_id=NULL,state_version=state_version+1 WHERE session_id=?",id);
        jdbc.update("UPDATE query_history h JOIN query_task q ON h.task_id=q.task_id SET h.deleted=TRUE WHERE q.session_id=?",id);
        jdbc.update("INSERT INTO audit_event(request_id,user_id,event_type,event_summary) VALUES(?,?,?,?)",requestId,user.userId(),"CONVERSATION_DELETED","session_id="+id);
    }

    /** 级联取消：非终态任务CAS置为CANCELLED并落终态事件与消息；已是终态则跳过。 */
    private void cascadeCancel(String sessionId,String taskId,CurrentUser user,String requestId){
        var rows=jdbc.queryForList("SELECT status_code,state_version,progress FROM query_task WHERE task_id=? FOR UPDATE",taskId);
        if(rows.isEmpty())return;
        var task=rows.get(0);
        String status=(String)task.get("status_code");
        if(java.util.Set.of("SUCCESS","FAILED","CANCELLED","TIMED_OUT","DEGRADED").contains(status))return;
        long newVersion=((Number)task.get("state_version")).longValue()+1;
        int updated=jdbc.update("UPDATE query_task SET status_code='CANCELLED',progress=100,stage_message='会话已删除，查询已自动取消',updated_at=NOW(3),state_version=? WHERE task_id=? AND state_version=?",
                newVersion,taskId,((Number)task.get("state_version")).longValue());
        if(updated==0)return;
        jdbc.update("INSERT INTO query_task_event(task_id,state_version,payload_json,created_at) VALUES(?,?,?,NOW(3))",taskId,newVersion,
                json.writeValueAsString(Map.of("task_id",taskId,"status","CANCELLED","progress",100,"message","会话已删除，查询已自动取消")));
        jdbc.update("INSERT INTO conversation_message(session_id,task_id,role_code,message_key,content,created_at,updated_at) VALUES(?,?,?,?,?,NOW(3),NOW(3))",
                sessionId,taskId,"ASSISTANT","cancel-cascade","会话已删除，查询已自动取消");
        jdbc.update("INSERT INTO audit_event(request_id,user_id,event_type,event_summary) VALUES(?,?,?,?)",requestId,user.userId(),"QUERY_CANCELLED","cascade by session delete, task_id="+taskId);
    }

    /** 用户消息目录不含助手结果，可按需加载历史页后定位。 */
    public List<Map<String,Object>> anchors(String id,CurrentUser user,long after,int size){
        own(id,user);
        if(after<0||size<1||size>100)throw new BusinessException(400001,"消息目录分页参数不正确");
        return after==0?jdbc.queryForList("SELECT message_id,content,created_at FROM conversation_message WHERE session_id=? AND role_code='USER' ORDER BY created_at,message_id LIMIT ?",id,size)
                :jdbc.queryForList("SELECT message_id,content,created_at FROM conversation_message WHERE session_id=? AND role_code='USER' AND (created_at,message_id)>(?,?) ORDER BY created_at,message_id LIMIT ?",id,cursorTime(id,after),after,size);
    }
    private Object cursorTime(String id,long cursor){
        var rows=jdbc.queryForList("SELECT created_at FROM conversation_message WHERE session_id=? AND message_id=?",id,cursor);
        if(rows.isEmpty())throw new BusinessException(400001,"消息游标不属于当前会话，请刷新后重试");
        return rows.get(0).get("created_at");
    }
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> feedback(String session,long messageId,String value,CurrentUser user){
        // 与删除共用会话锁；不允许删除后写入反馈。
        jdbc.queryForList("SELECT session_id FROM conversation_session WHERE session_id=? AND user_id=? FOR UPDATE",session,user.userId());own(session,user);
        if(value==null || !List.of("LIKE","DISLIKE","NONE").contains(value))throw new BusinessException(400001,"feedback只能为LIKE、DISLIKE或NONE");
        var rows=jdbc.queryForList("SELECT role_code,payload_json FROM conversation_message WHERE session_id=? AND message_id=?",session,messageId);
        if(rows.isEmpty() || !"ASSISTANT".equals(rows.get(0).get("role_code")))throw new BusinessException(404001,"助手回复不存在");
        Object payload=rows.get(0).get("payload_json");
        String state=payload==null?"":json.readTree(payload.toString()).path("status").asText();
        if(!List.of("ASKING","CONFIRMING","SUCCESS","FAILED","CANCELLED","TIMED_OUT","DEGRADED").contains(state))throw new BusinessException(409008,"回复尚未完成，请稍后评价");
        jdbc.update("UPDATE conversation_message SET feedback_code=?,feedback_updated_at=NOW(3) WHERE message_id=?",value.equals("NONE")?null:value,messageId);
        return Map.of("message_id",messageId,"feedback",value);
    }
    public ConversationContext context(Map<String,Object> session){
        var value=session.get("context_json");return value==null?ConversationContext.empty():json.readValue(value.toString(),ConversationContext.class);
    }
    public void activate(String session,String task){jdbc.update("UPDATE conversation_session SET active_task_id=?,state_version=state_version+1,updated_at=NOW(3) WHERE session_id=?",task,session);}
    public void userMessage(QueryTaskEntity task,String key,String text){
        if(conversationLog!=null)conversationLog.record(task,"USER",text);
        jdbc.update("INSERT INTO conversation_message(session_id,task_id,role_code,message_key,content,created_at,updated_at) VALUES(?,?,'USER',?,?,NOW(3),NOW(3))",task.getSessionId(),task.getTaskId(),key,text);
    }
    public void record(QueryTaskEntity task){
        if(conversationLog!=null)conversationLog.record(task,"ASSISTANT",task.getStageMessage());
        String payload=json.writeValueAsString(snapshots.of(task));
        jdbc.update("INSERT INTO query_task_event(task_id,state_version,payload_json,created_at) VALUES(?,?,?,NOW(3))",task.getTaskId(),task.getStateVersion(),payload);
        jdbc.update("INSERT INTO conversation_message(session_id,task_id,role_code,message_key,content,payload_json,created_at,updated_at) VALUES(?,?,'ASSISTANT',?,?,?,NOW(3),NOW(3)) ON DUPLICATE KEY UPDATE content=VALUES(content),payload_json=VALUES(payload_json),updated_at=NOW(3)",
                task.getSessionId(),task.getTaskId(),"assistant-"+task.getClarificationRound(),task.getStageMessage(),payload);
        if(QueryStatus.terminal(task.getStatusCode())){
            // 只在有可用结果时更新后续上下文。取消/失败不能污染上一次成功条件。
            boolean usable="SUCCESS".equals(task.getStatusCode()) || ("DEGRADED".equals(task.getStatusCode()) && task.getResultJson()!=null && json.readTree(task.getResultJson()).path("fallback").path("data_available").asBoolean(false));
            if(usable)jdbc.update("UPDATE conversation_session SET context_json=? WHERE session_id=? AND active_task_id=?",
                    json.writeValueAsString(new ConversationContext(task.getMergedQueryText(),task.getResolvedCustomerId(),task.getTaskId())),task.getSessionId(),task.getTaskId());
            jdbc.update("UPDATE conversation_session SET active_task_id=NULL,state_version=state_version+1,updated_at=NOW(3) WHERE session_id=? AND active_task_id=?",task.getSessionId(),task.getTaskId());
        }else jdbc.update("UPDATE conversation_session SET updated_at=NOW(3) WHERE session_id=?",task.getSessionId());
    }
    public List<Map<String,Object>> events(String task,long after){return jdbc.queryForList("SELECT event_id,payload_json FROM query_task_event WHERE task_id=? AND event_id>? ORDER BY event_id LIMIT 100",task,after);}
}

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

/** 所有写入由调用方事务包裹：状态、消息、事件在同一 MySQL 事务中提交。 */
@Service
public class ConversationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TaskSnapshots snapshots;
    public ConversationStore(JdbcTemplate jdbc,ObjectMapper json,TaskSnapshots snapshots){this.jdbc=jdbc;this.json=json;this.snapshots=snapshots;}
    public Map<String,Object> lock(String sessionId,CurrentUser user,String title){
        // ON DUPLICATE KEY取得写锁，避免INSERT IGNORE的共享锁随后升级而产生并发死锁。
        jdbc.update("INSERT INTO conversation_session(session_id,user_id,title,created_at,updated_at) VALUES(?,?,?,NOW(3),NOW(3)) ON DUPLICATE KEY UPDATE session_id=VALUES(session_id)",sessionId,user.userId(),title.substring(0,Math.min(160,title.length())));
        var rows=jdbc.queryForList("SELECT * FROM conversation_session WHERE session_id=? FOR UPDATE",sessionId);
        if(rows.isEmpty() || ((Number)rows.get(0).get("user_id")).longValue()!=user.userId())throw new BusinessException(404001,"会话不存在");
        return rows.get(0);
    }
    public void own(String id,CurrentUser user){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM conversation_session WHERE session_id=? AND user_id=?",Integer.class,id,user.userId())!=1)
            throw new BusinessException(404001,"会话不存在");
    }
    public void lockTask(QueryTaskEntity task){jdbc.queryForList("SELECT session_id FROM conversation_session WHERE session_id=? AND user_id=? FOR UPDATE",task.getSessionId(),task.getUserId());}
    public List<Map<String,Object>> list(CurrentUser user,int page,int size){
        if(page<1||size<1||size>100)throw new BusinessException(400001,"分页参数不正确");
        return jdbc.queryForList("SELECT session_id,title,active_task_id,state_version,created_at,updated_at FROM conversation_session WHERE user_id=? ORDER BY updated_at DESC,session_id LIMIT ? OFFSET ?",user.userId(),size,(long)(page-1)*size);
    }
    public Map<String,Object> detail(String id,CurrentUser user,long before,int size){
        own(id,user);
        if(size<1||size>100)throw new BusinessException(400001,"page_size 必须为1至100");
        var session=jdbc.queryForMap("SELECT session_id,title,active_task_id,state_version,context_json FROM conversation_session WHERE session_id=?",id);
        var rows=jdbc.queryForList("SELECT message_id,task_id,role_code,content,payload_json,created_at FROM conversation_message WHERE session_id=? AND message_id<? ORDER BY message_id DESC LIMIT ?",id,before<=0?Long.MAX_VALUE:before,size);
        rows.forEach(row->{Object payload=row.remove("payload_json");row.put("payload",payload==null?null:json.readValue(payload.toString(),Map.class));});
        java.util.Collections.reverse(rows);
        Object context=session.remove("context_json");session.put("context",context==null?null:json.readValue(context.toString(),Map.class));
        session.put("messages",rows);session.put("has_more",rows.size()==size);return session;
    }
    public ConversationContext context(Map<String,Object> session){
        var value=session.get("context_json");return value==null?ConversationContext.empty():json.readValue(value.toString(),ConversationContext.class);
    }
    public void activate(String session,String task){jdbc.update("UPDATE conversation_session SET active_task_id=?,state_version=state_version+1,updated_at=NOW(3) WHERE session_id=?",task,session);}
    public void userMessage(QueryTaskEntity task,String key,String text){
        jdbc.update("INSERT INTO conversation_message(session_id,task_id,role_code,message_key,content,created_at,updated_at) VALUES(?,?,'USER',?,?,NOW(3),NOW(3))",task.getSessionId(),task.getTaskId(),key,text);
    }
    public void record(QueryTaskEntity task){
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

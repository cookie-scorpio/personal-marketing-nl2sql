package com.boc.nl2sql.audit;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import java.util.LinkedHashMap;

@Component
public class ConversationLog {
    private final ObjectMapper json;
    public ConversationLog(ObjectMapper json){this.json=json;}
    public void record(QueryTaskEntity task,String role,String text){
        var data=new LinkedHashMap<String,Object>();
        data.put("request_id",org.slf4j.MDC.get("requestId"));data.put("session_id",task.getSessionId());data.put("task_id",task.getTaskId());
        data.put("user_id",task.getUserId());data.put("role",role);data.put("status",task.getStatusCode());
        data.put("state_version",task.getStateVersion());data.put("content",redact(text));data.put("failure_reason",redact(task.getErrorMessage()));
        if("ASSISTANT".equals(role)){
            if(task.getQuestionJson()!=null){
                var question=json.readTree(task.getQuestionJson());
                data.put("clarification_type",question.path("type").asText());
                data.put("clarification_prompt",redact(question.path("prompt").asText()));
            }
            if(task.getResultJson()!=null){
                var result=json.readTree(task.getResultJson());
                data.put("result_summary",redact(result.path("summary").asText()));
                data.put("analysis",redact(result.path("analysis").toString()));
            }
        }
        String line=json.writeValueAsString(data);
        Runnable write=()->LoggerFactory.getLogger("CONVERSATION").info(line);
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){write.run();}});
        else write.run();
    }
    public static String redact(String text){
        if(text==null)return null;
        return text.replaceAll("(?<![0-9])[0-9]{11,18}[0-9Xx]?(?![0-9])","[REDACTED]")
                .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._-]+","$1[REDACTED]")
                .replaceAll("(?i)((?:api[_-]?key|password|secret|token|密码|密钥)\\s*[:=：]\\s*)[^\\s,，]+","$1[REDACTED]");
    }
}

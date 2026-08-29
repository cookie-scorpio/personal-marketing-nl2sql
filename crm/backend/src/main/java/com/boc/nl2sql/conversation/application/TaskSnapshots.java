package com.boc.nl2sql.conversation.application;

import com.boc.nl2sql.conversation.api.TaskStatusResponse;
import com.boc.nl2sql.conversation.domain.QueryStatus;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import com.boc.nl2sql.execution.domain.QueryResult;
import com.boc.nl2sql.nl2sql.domain.ClarificationQuestion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;

@Component
public class TaskSnapshots {
    private final ObjectMapper json;
    private final int timeout;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.boc.nl2sql.execution.application.SqlRepairStore repairs;
    public TaskSnapshots(ObjectMapper json, @Value("${app.query.execution-timeout-seconds:60}") int timeout) {
        this.json=json; this.timeout=timeout;
    }
    @SuppressWarnings("unchecked")
    public TaskStatusResponse of(QueryTaskEntity task) {
        Map<String,Object> confirmation=null;
        if ("CONFIRMING".equals(task.getStatusCode()) && task.getConfirmationToken()!=null) {
            Map<String,Object> risk=read(task.getRiskJson(),Map.class);
            confirmation=Map.of("confirm_token",task.getConfirmationToken(),"risk_level",risk==null?"MEDIUM":risk.getOrDefault("level","MEDIUM"),
                    "message","该 SQL 涉及较大数据范围，请确认后执行。","reasons",risk==null?List.of():risk.getOrDefault("reasons",List.of()));
        }
        return new TaskStatusResponse(task.getTaskId(),task.getSessionId(),task.getStatusCode(),task.getProgress(),
                task.getStageMessage(),task.getIntentCode(),task.getClarificationRound(),read(task.getQuestionJson(),ClarificationQuestion.class),
                confirmation,read(task.getResultJson(),QueryResult.class),task.getErrorMessage()==null?null:Map.of("message",task.getErrorMessage()),
                task.getRepairAttempts()==null?0:task.getRepairAttempts(),repairs==null?List.of():repairs.list(task.getTaskId()),
                timeout,!QueryStatus.terminal(task.getStatusCode()),
                task.getStateVersion(),Boolean.TRUE.equals(task.getThinkingEnabled()),task.getDisplayQuery(),task.getCreatedAt(),task.getUpdatedAt());
    }
    private <T>T read(String value,Class<T> type){return value==null?null:json.readValue(value,type);}
}

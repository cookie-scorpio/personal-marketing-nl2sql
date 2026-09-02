package com.boc.nl2sql.service.conversation;

import com.boc.nl2sql.controller.conversation.TaskStatusResponse;
import com.boc.nl2sql.domain.conversation.QueryStatus;
import com.boc.nl2sql.domain.conversation.QueryTaskEntity;
import com.boc.nl2sql.domain.execution.QueryResult;
import com.boc.nl2sql.domain.nl2sql.ClarificationQuestion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;

/** 将任务表中的 JSON 状态和修复事实组装为稳定的客户端状态快照。 */
@Component
public class TaskSnapshots {
    private final ObjectMapper json;
    private final int timeout;
    private final CustomerResolver customers;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.boc.nl2sql.dao.quality.RepairFactStore repairs;
    public TaskSnapshots(ObjectMapper json, @Value("${app.query.execution-timeout-seconds:60}") int timeout,
                         CustomerResolver customers) {
        this.json=json; this.timeout=timeout; this.customers=customers;
    }
    /** 仅在对应状态暴露确认信息，并为缺失的可选集合提供空列表。 */
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
                task.getRepairAttempts()==null?0:task.getRepairAttempts(),repairs==null?List.of():repairs.list(task.getTaskId()).stream()
                        .map(repair->new com.boc.nl2sql.controller.conversation.SqlRepairResponse(repair.repairId(),repair.attemptNo(),
                                repair.triggerPhase(),repair.status(),repair.originalSql(),repair.failureReason(),repair.repairReason(),
                                repair.repairedSql(),repair.createdAt(),repair.updatedAt())).toList(),
                timeout,!QueryStatus.terminal(task.getStatusCode()),
                task.getStateVersion(),Boolean.TRUE.equals(task.getThinkingEnabled()),task.getDisplayQuery(),customerCard(task),task.getCreatedAt(),task.getUpdatedAt());
    }
    private TaskStatusResponse.CustomerCard customerCard(QueryTaskEntity task){
        return customers.card(task.getResolvedCustomerId())
                .map(card->new TaskStatusResponse.CustomerCard(card.customerId(),card.name(),card.branchId(),card.mobile()))
                .orElse(null);
    }
    private <T>T read(String value,Class<T> type){return value==null?null:json.readValue(value,type);}
}

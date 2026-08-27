package com.boc.nl2sql.conversation.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.boc.nl2sql.audit.AuditService;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.conversation.api.ClarificationRequest;
import com.boc.nl2sql.conversation.api.ConfirmationRequest;
import com.boc.nl2sql.conversation.api.SubmitQueryRequest;
import com.boc.nl2sql.conversation.api.SubmitQueryResponse;
import com.boc.nl2sql.conversation.api.TaskStatusResponse;
import com.boc.nl2sql.conversation.domain.QueryStatus;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskMapper;
import com.boc.nl2sql.execution.domain.QueryResult;
import com.boc.nl2sql.nl2sql.domain.ClarificationQuestion;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class QueryApplicationService {
    private final QueryTaskMapper taskMapper;
    private final QueryTaskProcessor processor;
    private final SessionContextStore contextStore;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TaskStateStore states;
    private final com.boc.nl2sql.execution.QueryExecutionGateway execution;
    private final com.boc.nl2sql.history.application.HistoryService history;
    private final int timeoutSeconds;

    public QueryApplicationService(QueryTaskMapper taskMapper, QueryTaskProcessor processor,
                                   SessionContextStore contextStore, AuditService auditService,
                                   ObjectMapper objectMapper, TaskStateStore states,
                                   com.boc.nl2sql.execution.QueryExecutionGateway execution,
                                   com.boc.nl2sql.history.application.HistoryService history,
                                   @org.springframework.beans.factory.annotation.Value("${app.query.execution-timeout-seconds:60}") int timeoutSeconds) {
        this.taskMapper = taskMapper;
        this.processor = processor;
        this.contextStore = contextStore;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.states = states; this.execution = execution; this.history = history; this.timeoutSeconds = timeoutSeconds;
    }

    public SubmitQueryResponse submit(SubmitQueryRequest request, CurrentUser user, String requestId) {
        String taskId = UUID.randomUUID().toString();
        QueryTaskEntity task = new QueryTaskEntity();
        task.setTaskId(taskId);
        task.setSessionId(request.sessionId());
        task.setUserId(user.userId());
        task.setQueryText(request.queryText().trim());
        task.setMergedQueryText(request.queryText().trim());
        task.setStatusCode(QueryStatus.RECEIVED.name());
        task.setProgress(0);
        task.setStageMessage("查询请求已接收");
        task.setClarificationRound(0);
        task.setStateVersion(0L);
        task.setRepairAttempts(0);
        task.setConfirmed(false);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.insert(task);
        contextStore.rememberTask(user.userId(), request.sessionId(), taskId);
        auditService.record(requestId, taskId, user.userId(), "QUERY_RECEIVED", "provider request accepted");
        processor.processAsync(taskId, user, requestId);
        return new SubmitQueryResponse(taskId, request.sessionId(), QueryStatus.RECEIVED.name(), 0,
                "/api/v1/queries/" + taskId + "/status");
    }

    public TaskStatusResponse status(String taskId, CurrentUser user) {
        QueryTaskEntity task = ownedTask(taskId, user);
        return new TaskStatusResponse(task.getTaskId(), task.getSessionId(), task.getStatusCode(),
                task.getProgress(), task.getStageMessage(), task.getIntentCode(), task.getClarificationRound(),
                read(task.getQuestionJson(), ClarificationQuestion.class),
                QueryStatus.CONFIRMING.name().equals(task.getStatusCode())
                        ? confirmation(task) : null,
                read(task.getResultJson(), QueryResult.class),
                task.getErrorMessage() == null ? null : Map.of("message", task.getErrorMessage()),
                task.getRepairAttempts(), timeoutSeconds, !QueryStatus.terminal(task.getStatusCode()));
    }

    private Map<String, Object> confirmation(QueryTaskEntity task) {
        @SuppressWarnings("unchecked")
        Map<String, Object> risk = read(task.getRiskJson(), Map.class);
        Object level = risk == null ? "MEDIUM" : risk.getOrDefault("level", "MEDIUM");
        Object reasons = risk == null ? java.util.List.of("查询范围较大")
                : risk.getOrDefault("reasons", java.util.List.of("查询范围较大"));
        return Map.of("confirm_token", task.getConfirmationToken(), "risk_level", level,
                "message", "该SQL可能涉及大量数据或较长查询时延，请确认后执行。", "reasons", reasons);
    }

    public SubmitQueryResponse clarify(String sessionId, ClarificationRequest request,
                                       CurrentUser user, String requestId) {
        QueryTaskEntity task = ownedTask(request.taskId(), user);
        if (!task.getSessionId().equals(sessionId) || !QueryStatus.ASKING.name().equals(task.getStatusCode())) {
            throw new BusinessException(409001, "当前任务不在等待补充状态");
        }
        String answer = request.mergedAnswer();
        if (answer.isBlank()) throw new BusinessException(400002, "请填写补充条件或选择一个选项");
        ClarificationQuestion question = read(task.getQuestionJson(), ClarificationQuestion.class);
        if (question == null || !question.questionId().equals(request.questionId())) {
            throw new BusinessException(409002, "反问已失效，请刷新任务状态");
        }
        String connector = "CONFLICT".equals(question.type()) ? "，最终条件为："
                : "TIME_BASIS".equals(question.type()) ? "，时间口径：" : "，补充条件：";
        task.setMergedQueryText(task.getMergedQueryText() + connector + answer);
        task.setClarificationRound(task.getClarificationRound() + 1);
        task.setQuestionJson(null);
        task.setStatusCode(QueryStatus.RECEIVED.name());
        task.setProgress(10);
        task.setStageMessage("已收到补充条件，正在重新解析");
        saveOrConflict(task);
        auditService.record(requestId, task.getTaskId(), user.userId(), "QUERY_CLARIFIED", question.type());
        processor.processAsync(task.getTaskId(), user, requestId);
        return new SubmitQueryResponse(task.getTaskId(), sessionId, task.getStatusCode(), task.getProgress(),
                "/api/v1/queries/" + task.getTaskId() + "/status");
    }

    public TaskStatusResponse confirm(String taskId, ConfirmationRequest request,
                                      CurrentUser user, String requestId) {
        QueryTaskEntity task = ownedTask(taskId, user);
        if (!QueryStatus.CONFIRMING.name().equals(task.getStatusCode())
                || !request.confirmToken().equals(task.getConfirmationToken())) {
            throw new BusinessException(409003, "确认令牌无效或已过期");
        }
        if ("REJECT".equalsIgnoreCase(request.decision())) {
            return cancel(taskId, user, requestId);
        }
        if (!"CONFIRM".equalsIgnoreCase(request.decision())) {
            throw new BusinessException(400003, "decision 仅支持 CONFIRM 或 REJECT");
        }
        task.setConfirmed(true);
        task.setConfirmationToken(null);
        task.setStatusCode(QueryStatus.RECEIVED.name());
        task.setStageMessage("已确认，准备执行查询");
        saveOrConflict(task);
        auditService.record(requestId, taskId, user.userId(), "QUERY_CONFIRMED", "user confirmed high scope query");
        processor.processAsync(taskId, user, requestId);
        return status(taskId, user);
    }

    public TaskStatusResponse cancel(String taskId, CurrentUser user, String requestId) {
        QueryTaskEntity task = ownedTask(taskId, user);
        int changed = taskMapper.update(null, Wrappers.<QueryTaskEntity>lambdaUpdate()
                .eq(QueryTaskEntity::getTaskId, taskId).eq(QueryTaskEntity::getUserId, user.userId())
                .notIn(QueryTaskEntity::getStatusCode, "SUCCESS", "FAILED", "CANCELLED", "TIMED_OUT", "DEGRADED")
                .set(QueryTaskEntity::getStatusCode, "CANCELLED").set(QueryTaskEntity::getProgress, 100)
                .set(QueryTaskEntity::getStageMessage, "查询已取消，不会继续修复或降级执行")
                .set(QueryTaskEntity::getConfirmationToken, null).set(QueryTaskEntity::getQuestionJson, null)
                .set(QueryTaskEntity::getUpdatedAt, LocalDateTime.now()).setSql("state_version = state_version + 1"));
        if (changed == 1) {
            execution.cancel(taskId);
            try {
                history.save(taskId, user.userId(), task.getQueryText(), task.getIntentCode(), "CANCELLED", task.getSqlText(), "查询已取消");
                auditService.record(requestId, taskId, user.userId(), "QUERY_CANCELLED", "USER_REQUEST");
            } catch (RuntimeException recordFailure) {
                org.slf4j.LoggerFactory.getLogger(QueryApplicationService.class)
                        .error("取消任务的附属记录写入失败：taskId={}", taskId);
            }
        }
        return status(taskId, user);
    }

    private void saveOrConflict(QueryTaskEntity task) {
        if (!states.trySave(task)) throw new BusinessException(409004, "任务状态已变更，请刷新后重试");
    }

    private QueryTaskEntity ownedTask(String taskId, CurrentUser user) {
        QueryTaskEntity task = taskMapper.selectOne(Wrappers.<QueryTaskEntity>lambdaQuery()
                .eq(QueryTaskEntity::getTaskId, taskId)
                .eq(QueryTaskEntity::getUserId, user.userId())
                .last("LIMIT 1"));
        if (task == null) throw new BusinessException(404001, "查询任务不存在");
        return task;
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored task JSON is invalid", exception);
        }
    }
}

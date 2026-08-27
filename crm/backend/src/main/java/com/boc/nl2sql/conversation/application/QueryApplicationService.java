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

    public QueryApplicationService(QueryTaskMapper taskMapper, QueryTaskProcessor processor,
                                   SessionContextStore contextStore, AuditService auditService,
                                   ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.processor = processor;
        this.contextStore = contextStore;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
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
                task.getErrorMessage() == null ? null : Map.of("message", task.getErrorMessage()));
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
        String connector = "CONFLICT".equals(question.type()) ? "，最终条件为：" : "，补充条件：";
        task.setMergedQueryText(task.getMergedQueryText() + connector + answer);
        task.setClarificationRound(task.getClarificationRound() + 1);
        task.setQuestionJson(null);
        task.setStatusCode(QueryStatus.RECEIVED.name());
        task.setProgress(10);
        task.setStageMessage("已收到补充条件，正在重新解析");
        taskMapper.updateById(task);
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
            task.setStatusCode(QueryStatus.CANCELLED.name());
            task.setProgress(100);
            task.setStageMessage("查询已取消");
            taskMapper.updateById(task);
            auditService.record(requestId, taskId, user.userId(), "QUERY_REJECTED", "user rejected high scope query");
            return status(taskId, user);
        }
        if (!"CONFIRM".equalsIgnoreCase(request.decision())) {
            throw new BusinessException(400003, "decision 仅支持 CONFIRM 或 REJECT");
        }
        task.setConfirmed(true);
        task.setConfirmationToken(null);
        task.setStatusCode(QueryStatus.RECEIVED.name());
        task.setStageMessage("已确认，准备执行查询");
        taskMapper.updateById(task);
        auditService.record(requestId, taskId, user.userId(), "QUERY_CONFIRMED", "user confirmed high scope query");
        processor.processAsync(taskId, user, requestId);
        return status(taskId, user);
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

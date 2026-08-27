package com.boc.nl2sql.conversation.application;

import com.boc.nl2sql.audit.AuditService;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.conversation.domain.QueryStatus;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskMapper;
import com.boc.nl2sql.execution.QueryExecutionGateway;
import com.boc.nl2sql.execution.application.ResultAssembler;
import com.boc.nl2sql.execution.application.SqlPlanner;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import com.boc.nl2sql.execution.domain.QueryResult;
import com.boc.nl2sql.history.application.HistoryService;
import com.boc.nl2sql.model.ModelGateway;
import com.boc.nl2sql.nl2sql.application.CompletenessValidator;
import com.boc.nl2sql.nl2sql.domain.ClarificationQuestion;
import com.boc.nl2sql.nl2sql.domain.SemanticQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 驱动查询任务状态机。每个阶段只向前流转，ASKING/CONFIRMING 必须等待用户动作后才能继续。
 */
@Service
public class QueryTaskProcessor {
    private final QueryTaskMapper taskMapper;
    private final ModelGateway modelGateway;
    private final CompletenessValidator completenessValidator;
    private final SqlPlanner sqlPlanner;
    private final QueryExecutionGateway executionGateway;
    private final ResultAssembler resultAssembler;
    private final HistoryService historyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final int maxClarificationRounds;

    public QueryTaskProcessor(QueryTaskMapper taskMapper, ModelGateway modelGateway,
                              CompletenessValidator completenessValidator, SqlPlanner sqlPlanner,
                              QueryExecutionGateway executionGateway, ResultAssembler resultAssembler,
                              HistoryService historyService, AuditService auditService, ObjectMapper objectMapper,
                              @Value("${app.query.max-clarification-rounds:2}") int maxClarificationRounds) {
        this.taskMapper = taskMapper;
        this.modelGateway = modelGateway;
        this.completenessValidator = completenessValidator;
        this.sqlPlanner = sqlPlanner;
        this.executionGateway = executionGateway;
        this.resultAssembler = resultAssembler;
        this.historyService = historyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.maxClarificationRounds = maxClarificationRounds;
    }

    @Async("queryExecutor")
    public void processAsync(String taskId, CurrentUser user, String requestId) {
        QueryTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) return;
        try {
            stage(task, QueryStatus.INTENT_ANALYZING, 20, "正在识别业务意图和查询条件");
            SemanticQuery semantic = modelGateway.interpret(task.getMergedQueryText());
            task.setIntentCode(semantic.intent().name());
            taskMapper.updateById(task);

            var question = completenessValidator.validate(semantic);
            if (question.isPresent()) {
                if (task.getClarificationRound() >= maxClarificationRounds) {
                    fail(task, "补充条件后仍无法形成唯一查询，请重新描述问题", requestId);
                    return;
                }
                task.setQuestionJson(objectMapper.writeValueAsString(question.get()));
                stage(task, QueryStatus.ASKING, 30, question.get().prompt());
                auditService.record(requestId, taskId, user.userId(), "QUERY_ASKING", question.get().type());
                return;
            }

            stage(task, QueryStatus.SQL_GENERATING, 45, "正在生成受控查询计划");
            PlannedQuery planned = sqlPlanner.plan(semantic, user);
            task.setSqlText(planned.sql());
            task.setSqlParametersJson(objectMapper.writeValueAsString(planned.parameters()));
            task.setQuestionJson(null);
            taskMapper.updateById(task);

            stage(task, QueryStatus.VALIDATING, 60, "正在执行只读、权限和对象白名单校验");
            if (planned.highRisk() && !Boolean.TRUE.equals(task.getConfirmed())) {
                task.setConfirmationToken(UUID.randomUUID().toString().replace("-", ""));
                stage(task, QueryStatus.CONFIRMING, 65, "查询范围较大，需要确认后执行");
                auditService.record(requestId, taskId, user.userId(), "QUERY_CONFIRMING", "HIGH_SCOPE");
                return;
            }

            stage(task, QueryStatus.EXECUTING, 75, "正在查询已授权的营销数据");
            List<Map<String, Object>> rows = executionGateway.execute(planned);
            stage(task, QueryStatus.PACKAGING, 90, "正在脱敏并整理查询结果");
            QueryResult result = resultAssembler.assemble(planned, rows);
            task.setResultJson(objectMapper.writeValueAsString(result));
            task.setErrorMessage(null);
            stage(task, QueryStatus.SUCCESS, 100, result.summary());
            historyService.save(taskId, user.userId(), task.getQueryText(), task.getIntentCode(),
                    QueryStatus.SUCCESS.name(), compactSql(planned.sql()), result.summary());
            auditService.record(requestId, taskId, user.userId(), "QUERY_SUCCESS", "resultRows=" + rows.size());
        } catch (Exception exception) {
            fail(task, safeMessage(exception), requestId);
        }
    }

    private void stage(QueryTaskEntity task, QueryStatus status, int progress, String message) {
        task.setStatusCode(status.name());
        task.setProgress(progress);
        task.setStageMessage(message);
        taskMapper.updateById(task);
    }

    private void fail(QueryTaskEntity task, String message, String requestId) {
        task.setErrorMessage(message);
        stage(task, QueryStatus.FAILED, 100, "查询未完成");
        historyService.save(task.getTaskId(), task.getUserId(), task.getQueryText(), task.getIntentCode(),
                QueryStatus.FAILED.name(), compactSql(task.getSqlText()), message);
        auditService.record(requestId, task.getTaskId(), task.getUserId(), "QUERY_FAILED", message);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "查询执行失败，请调整条件后重试";
        // 数据库连接、SQL堆栈等内部细节不得直接返回前端。
        if (message.contains("Communications link") || message.contains("Connection")) {
            return "分析数据库暂不可用，请稍后重试";
        }
        return message.length() > 240 ? "查询执行失败，请稍后重试" : message;
    }

    private String compactSql(String sql) {
        return sql == null ? null : sql.strip().replaceAll("\\s+", " ");
    }
}

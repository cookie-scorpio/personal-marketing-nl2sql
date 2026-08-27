package com.boc.nl2sql.conversation.application;

import com.boc.nl2sql.audit.AuditService;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.conversation.domain.QueryStatus;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskEntity;
import com.boc.nl2sql.conversation.infrastructure.QueryTaskMapper;
import com.boc.nl2sql.execution.QueryExecutionGateway;
import com.boc.nl2sql.execution.application.ResultAssembler;
import com.boc.nl2sql.execution.application.GeneratedSqlScopeValidator;
import com.boc.nl2sql.execution.application.SqlPlanner;
import com.boc.nl2sql.execution.application.SqlRiskEvaluator;
import com.boc.nl2sql.execution.application.SqlSafetyValidator;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import com.boc.nl2sql.execution.domain.QueryResult;
import com.boc.nl2sql.history.application.HistoryService;
import com.boc.nl2sql.model.ModelGateway;
import com.boc.nl2sql.model.QueryInterpretation;
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
    private final SqlRiskEvaluator riskEvaluator;
    private final SqlSafetyValidator safetyValidator;
    private final GeneratedSqlScopeValidator generatedSqlScopeValidator;
    private final QueryExecutionGateway executionGateway;
    private final ResultAssembler resultAssembler;
    private final HistoryService historyService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final int maxClarificationRounds;

    public QueryTaskProcessor(QueryTaskMapper taskMapper, ModelGateway modelGateway,
                              CompletenessValidator completenessValidator, SqlPlanner sqlPlanner,
                              SqlRiskEvaluator riskEvaluator, SqlSafetyValidator safetyValidator,
                              GeneratedSqlScopeValidator generatedSqlScopeValidator,
                              QueryExecutionGateway executionGateway, ResultAssembler resultAssembler,
                              HistoryService historyService, AuditService auditService, ObjectMapper objectMapper,
                              @Value("${app.query.max-clarification-rounds:2}") int maxClarificationRounds) {
        this.taskMapper = taskMapper;
        this.modelGateway = modelGateway;
        this.completenessValidator = completenessValidator;
        this.sqlPlanner = sqlPlanner;
        this.riskEvaluator = riskEvaluator;
        this.safetyValidator = safetyValidator;
        this.generatedSqlScopeValidator = generatedSqlScopeValidator;
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
            // 用户确认的是已经展示过风险的计划。确认后直接执行持久化SQL，禁止再次调用模型生成另一条SQL。
            if (Boolean.TRUE.equals(task.getConfirmed()) && task.getSqlText() != null) {
                executeConfirmedPlan(task, user, requestId);
                return;
            }
            stage(task, QueryStatus.INTENT_ANALYZING, 20, "正在识别业务意图和查询条件");
            QueryInterpretation interpretation = modelGateway.interpret(task.getMergedQueryText(), user);
            SemanticQuery semantic = interpretation.semantic();
            task.setIntentCode(semantic.intent().name());
            task.setInterpretationSource(interpretation.source());
            task.setInterpretationConfidence(interpretation.confidence());
            task.setPreferredDisplay(interpretation.preferredDisplay());
            taskMapper.updateById(task);

            // 模型可发现开放问题中的缺失条件；确定性规则仍负责统一的矛盾与必填项兜底。
            var question = interpretation.clarification() == null
                    ? completenessValidator.validate(semantic, interpretation.hasGeneratedSql())
                    : java.util.Optional.of(interpretation.clarification());
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
            PlannedQuery planned = interpretation.hasGeneratedSql()
                    ? new PlannedQuery(interpretation.generatedSql(), Map.of(), interpretation.preferredDisplay(),
                    interpretation.title(), false)
                    : sqlPlanner.plan(semantic, user);
            planned = planned.withRisk(riskEvaluator.assess(planned));
            task.setPreferredDisplay(planned.resultType());
            task.setSqlText(planned.sql());
            task.setSqlParametersJson(objectMapper.writeValueAsString(planned.parameters()));
            task.setRiskJson(objectMapper.writeValueAsString(planned.risk()));
            task.setQuestionJson(null);
            taskMapper.updateById(task);

            stage(task, QueryStatus.VALIDATING, 60, "正在执行只读、权限和对象白名单校验");
            safetyValidator.validate(planned.sql());
            if (interpretation.hasGeneratedSql()) generatedSqlScopeValidator.validate(planned.sql(), user);
            if (planned.highRisk() && !Boolean.TRUE.equals(task.getConfirmed())) {
                task.setConfirmationToken(UUID.randomUUID().toString().replace("-", ""));
                stage(task, QueryStatus.CONFIRMING, 65, "查询范围较大，需要确认后执行");
                auditService.record(requestId, taskId, user.userId(), "QUERY_CONFIRMING", "HIGH_SCOPE");
                return;
            }

            executePlan(task, planned, user, requestId, interpretation.source(), interpretation.confidence());
        } catch (Exception exception) {
            fail(task, safeMessage(exception), requestId);
        }
    }

    @SuppressWarnings("unchecked")
    private void executeConfirmedPlan(QueryTaskEntity task, CurrentUser user, String requestId) {
        Map<String, Object> parameters = task.getSqlParametersJson() == null ? Map.of()
                : objectMapper.readValue(task.getSqlParametersJson(), Map.class);
        PlannedQuery stored = new PlannedQuery(task.getSqlText(), parameters,
                task.getPreferredDisplay() == null ? "AUTO" : task.getPreferredDisplay(), "已确认查询结果", false);
        safetyValidator.validate(stored.sql());
        if ("DEEPSEEK".equals(task.getInterpretationSource())) generatedSqlScopeValidator.validate(stored.sql(), user);
        executePlan(task, stored, user, requestId, task.getInterpretationSource(),
                task.getInterpretationConfidence() == null ? 1.0 : task.getInterpretationConfidence());
    }

    private void executePlan(QueryTaskEntity task, PlannedQuery planned, CurrentUser user,
                             String requestId, String source, double confidence) {
        stage(task, QueryStatus.EXECUTING, 75, "正在查询已授权的营销数据");
        List<Map<String, Object>> rows = executionGateway.execute(planned);
        stage(task, QueryStatus.PACKAGING, 90, "正在整理图表与数据分析");
        QueryResult result = resultAssembler.assemble(planned, rows, source, confidence);
        task.setResultJson(objectMapper.writeValueAsString(result));
        task.setErrorMessage(null);
        stage(task, QueryStatus.SUCCESS, 100, result.summary());
        historyService.save(task.getTaskId(), user.userId(), task.getQueryText(), task.getIntentCode(),
                QueryStatus.SUCCESS.name(), compactSql(planned.sql()), result.summary());
        auditService.record(requestId, task.getTaskId(), user.userId(), "QUERY_SUCCESS", "resultRows=" + rows.size());
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

package com.boc.nl2sql.service.evaluation;

import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.dao.evaluation.EvalDatasetItemMapper;
import com.boc.nl2sql.dao.evaluation.EvalRunItemMapper;
import com.boc.nl2sql.dao.evaluation.EvalRunMapper;
import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.domain.evaluation.EvalDatasetItemEntity;
import com.boc.nl2sql.domain.evaluation.EvalOutcome;
import com.boc.nl2sql.domain.evaluation.EvalRunEntity;
import com.boc.nl2sql.domain.evaluation.EvalRunItemEntity;
import com.boc.nl2sql.domain.evaluation.EvalRunStatus;
import com.boc.nl2sql.domain.execution.PlannedQuery;
import com.boc.nl2sql.domain.execution.QueryRisk;
import com.boc.nl2sql.domain.execution.ResultColumnHint;
import com.boc.nl2sql.domain.quality.QualityEventType;
import com.boc.nl2sql.domain.quality.QualityFact;
import com.boc.nl2sql.model.ModelGateway;
import com.boc.nl2sql.model.QueryInterpretation;
import com.boc.nl2sql.service.execution.GeneratedSqlScopeValidator;
import com.boc.nl2sql.service.execution.SqlPlanner;
import com.boc.nl2sql.service.execution.SqlSafetyValidator;
import com.boc.nl2sql.service.quality.QualityFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;

/**
 * 评测运行器：对已发布评测集逐条重放问题并对照金标打分。
 *
 * <p>重放管线与 {@code QueryTaskProcessor} 的在线链路保持同一套模型网关、规划器与校验器，
 * 但不创建会话和查询任务，评测痕迹只落在评测表与审计事实中。执行串行进行，
 * 避免并发压垮模型配额和业务库。</p>
 */
@Component
public class EvaluationRunner {
    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);
    /** 结果一致性对比的每侧最大取行数；两侧都达到上限时行数视为相等。 */
    private static final int COMPARE_ROW_CAP = 2000;

    private final EvalRunMapper runs;
    private final EvalRunItemMapper runItems;
    private final EvalDatasetItemMapper datasetItems;
    private final ModelGateway modelGateway;
    private final SqlPlanner planner;
    private final SqlSafetyValidator safety;
    private final GeneratedSqlScopeValidator scope;
    private final NamedParameterJdbcTemplate queryJdbc;
    private final JdbcTemplate jdbcTemplate;
    private final QualityFacts facts;
    private final Executor executor;
    private final int timeoutSeconds;

    public EvaluationRunner(EvalRunMapper runs, EvalRunItemMapper runItems, EvalDatasetItemMapper datasetItems,
            ModelGateway modelGateway, SqlPlanner planner, SqlSafetyValidator safety,
            GeneratedSqlScopeValidator scope, NamedParameterJdbcTemplate queryJdbc, JdbcTemplate jdbcTemplate,
            QualityFacts facts, @Qualifier("evaluationExecutor") Executor executor,
            @Value("${app.query.execution-timeout-seconds:60}") int timeoutSeconds) {
        this.runs = runs;
        this.runItems = runItems;
        this.datasetItems = datasetItems;
        this.modelGateway = modelGateway;
        this.planner = planner;
        this.safety = safety;
        this.scope = scope;
        this.queryJdbc = queryJdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.facts = facts;
        this.executor = executor;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 提交一次评测运行。调用方可能仍处于发布事务中，因此推迟到事务提交后再入队；
     * 通过专用线程池显式提交而非 {@code @Async}，避免同类内部调用绕过代理后在请求线程上同步执行。
     */
    public void startAsync(long runId, CurrentUser user) {
        Runnable dispatch = () -> submit(runId, user);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch.run(); }
            });
        } else {
            dispatch.run();
        }
    }

    private void submit(long runId, CurrentUser user) {
        try {
            executor.execute(() -> execute(runId, user));
        } catch (TaskRejectedException rejected) {
            // 队列满时立即判失败，避免运行永远停留在 PENDING。
            EvalRunEntity run = runs.selectById(runId);
            if (run != null && EvalRunStatus.PENDING.name().equals(run.getStatus())) {
                run.setStatus(EvalRunStatus.FAILED.name());
                run.setErrorMessage("评测任务队列已满，请稍后重新评测");
                run.setFinishedAt(LocalDateTime.now());
                runs.updateById(run);
            }
        }
    }

    private void execute(long runId, CurrentUser user) {
        EvalRunEntity run = runs.selectById(runId);
        // 只有 PENDING 能进入 RUNNING：重复提交和重启后的残留运行都会被拒绝。
        if (run == null || !EvalRunStatus.PENDING.name().equals(run.getStatus())) return;
        run.setStatus(EvalRunStatus.RUNNING.name());
        run.setStartedAt(LocalDateTime.now());
        runs.updateById(run);
        fact(user, QualityEventType.EVAL_RUN_STARTED, "run started", null);

        List<EvalDatasetItemEntity> items = datasetItems.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EvalDatasetItemEntity>()
                        .eq(EvalDatasetItemEntity::getDatasetId, run.getDatasetId())
                        .orderByAsc(EvalDatasetItemEntity::getId));
        try {
            for (EvalDatasetItemEntity item : items) {
                EvalRunItemEntity record = evaluateOne(run.getId(), item, user);
                runItems.insert(record);
                boolean passed = EvalOutcome.PASSED.name().equals(record.getOutcome());
                jdbcTemplate.update(
                        "UPDATE eval_run SET finished_items = finished_items + 1, passed_items = passed_items + ? WHERE id = ?",
                        passed ? 1 : 0, runId);
                fact(user, QualityEventType.EVAL_RUN_ITEM_COMPLETED, record.getOutcome(),
                        Map.of("item_id", item.getId(), "outcome", record.getOutcome()));
            }
            run.setStatus(EvalRunStatus.SUCCESS.name());
            run.setFinishedAt(LocalDateTime.now());
            runs.updateById(run);
            fact(user, QualityEventType.EVAL_RUN_FINISHED, "run finished", null);
        } catch (RuntimeException error) {
            log.error("评测运行中断：runId={}", runId, error);
            run.setStatus(EvalRunStatus.FAILED.name());
            run.setErrorMessage(brief(error));
            run.setFinishedAt(LocalDateTime.now());
            runs.updateById(run);
            fact(user, QualityEventType.EVAL_RUN_FINISHED, "run failed: " + brief(error), null);
        }
    }

    /** 单条评测：解释、规划、校验、执行、对比金标，任何阶段的失败都记录为该条的结论。 */
    private EvalRunItemEntity evaluateOne(long runId, EvalDatasetItemEntity item, CurrentUser user) {
        EvalRunItemEntity record = new EvalRunItemEntity();
        record.setRunId(runId);
        record.setItemId(item.getId());
        record.setQuestionText(item.getQuestionText());
        record.setExpectedSql(item.getExpectedSql());
        record.setExecutionSuccess(false);
        long startedAt = System.currentTimeMillis();

        PlannedQuery plan;
        boolean generatedByModel;
        try {
            QueryInterpretation interpretation = modelGateway.interpret(item.getQuestionText(), user, () -> true);
            if (interpretation.clarification() != null) {
                record.setOutcome(EvalOutcome.CLARIFICATION_NEEDED.name());
                record.setFailureStage("INTERPRET");
                record.setErrorMessage(shorten(interpretation.clarification().prompt()));
                return record;
            }
            plan = interpretation.hasGeneratedSql()
                    ? new PlannedQuery(interpretation.generatedSql(), Map.of(), interpretation.preferredDisplay(),
                            interpretation.title(), QueryRisk.low(), interpretation.columnHints())
                    : planner.plan(interpretation.semantic(), user);
            // 与在线链路一致：模型自由生成的 SQL 才需要账号范围证明，规则模板天然受限。
            String source = interpretation.source();
            generatedByModel = source != null && !"RULE".equals(source) && !"TEMPLATE_FALLBACK".equals(source);
            record.setGeneratedSql(plan.sql());
        } catch (RuntimeException failure) {
            record.setOutcome(EvalOutcome.INTERPRET_FAILED.name());
            record.setFailureStage("INTERPRET");
            record.setErrorMessage(shorten(brief(failure)));
            return finishTiming(record, startedAt);
        }

        try {
            safety.validate(plan.sql());
            if (generatedByModel) scope.validate(plan.sql(), user);
        } catch (RuntimeException rejected) {
            record.setOutcome(EvalOutcome.VALIDATION_FAILED.name());
            record.setFailureStage("VALIDATE");
            record.setErrorMessage(shorten(brief(rejected)));
            return finishTiming(record, startedAt);
        }

        List<Map<String, Object>> actualRows;
        try {
            actualRows = executeReadonly(plan.sql(), plan.parameters());
        } catch (RuntimeException error) {
            record.setOutcome(EvalOutcome.EXECUTION_FAILED.name());
            record.setFailureStage("EXECUTE");
            record.setErrorMessage(shorten(brief(error)));
            return finishTiming(record, startedAt);
        }
        record.setExecutionSuccess(true);
        record.setElapsedMs(System.currentTimeMillis() - startedAt);

        record.setSqlMatch(normalizedSql(plan.sql()).equals(normalizedSql(item.getExpectedSql())));
        ResultComparison comparison = compareWithExpected(item.getExpectedSql(), actualRows);
        record.setResultConsistent(comparison.consistent());
        record.setActualRows(actualRows.size());
        record.setExpectedRows(comparison.expectedRows());

        if (Boolean.FALSE.equals(comparison.consistent())) {
            record.setOutcome(EvalOutcome.RESULT_MISMATCH.name());
            record.setFailureStage("COMPARE");
        } else if (Boolean.FALSE.equals(record.getSqlMatch())) {
            record.setOutcome(EvalOutcome.SQL_MISMATCH.name());
        } else {
            record.setOutcome(EvalOutcome.PASSED.name());
        }
        return record;
    }

    private EvalRunItemEntity finishTiming(EvalRunItemEntity record, long startedAt) {
        record.setElapsedMs(System.currentTimeMillis() - startedAt);
        return record;
    }

    private record ResultComparison(Boolean consistent, Integer expectedRows) { }

    /**
     * 执行金标 SQL 并与实际行集对比：行数与归一化后的内容多重集合都一致才算一致。
     * 金标执行失败不判错，记为 null 留给人工判读，避免把环境差异当成系统错误。
     */
    private ResultComparison compareWithExpected(String expectedSql, List<Map<String, Object>> actualRows) {
        List<Map<String, Object>> expectedRows;
        try {
            safety.validate(expectedSql);
            expectedRows = executeReadonly(expectedSql, Map.of());
        } catch (RuntimeException error) {
            return new ResultComparison(null, null);
        }
        boolean sameCount = rowCountBucket(expectedRows) == rowCountBucket(actualRows);
        boolean sameContent = rowDigest(expectedRows).equals(rowDigest(actualRows));
        return new ResultComparison(sameCount && sameContent, expectedRows.size());
    }

    private int rowCountBucket(List<Map<String, Object>> rows) {
        return rows.size() > COMPARE_ROW_CAP ? COMPARE_ROW_CAP + 1 : rows.size();
    }

    /** 只读执行：与在线执行相同的超时保护，最多取 CAP+1 行用于截断判定。 */
    private List<Map<String, Object>> executeReadonly(String sql, Map<String, Object> parameters) {
        return queryJdbc.execute(sql, parameters, statement -> {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(COMPARE_ROW_CAP + 1);
            var rowMapper = new ColumnMapRowMapper();
            try (var resultSet = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                while (resultSet.next() && rows.size() <= COMPARE_ROW_CAP) rows.add(rowMapper.mapRow(resultSet, rows.size()));
                return rows;
            }
        });
    }

    /** 行集摘要：每行按键名排序后序列化，行间排序消除顺序差异，再取 SHA-256。 */
    private String rowDigest(List<Map<String, Object>> rows) {
        List<String> normalized = rows.stream().map(row -> {
            Map<String, Object> sorted = new TreeMap<>();
            row.forEach((key, value) -> sorted.put(key, normalizeValue(value)));
            StringBuilder line = new StringBuilder();
            sorted.forEach((key, value) -> line.append(key).append('=').append(value).append('|'));
            return line.toString();
        }).sorted().toList();
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String line : normalized) digest.update(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException unreachable) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", unreachable);
        }
    }

    /** 数值去掉尾零、时间统一 toString，避免同一数值因精度展示不同被判为不一致。 */
    private String normalizeValue(Object value) {
        if (value instanceof java.math.BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        if (value instanceof Number number) return number.toString();
        return String.valueOf(value);
    }

    private String normalizedSql(String sql) {
        if (sql == null) return "";
        return sql.strip().replaceAll("\\s+", " ").toLowerCase().replaceAll(";$", "");
    }

    private void fact(CurrentUser user, QualityEventType type, String summary, Map<String, Object> details) {
        QualityFact.Builder builder = QualityFact.builder(type, "QUALITY").userId(user.userId()).summary(summary);
        if (details != null) builder.details(details);
        facts.publish(builder.build());
    }

    private String brief(Throwable error) {
        if (error instanceof BusinessException business) return business.code() + " " + business.getMessage();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private String shorten(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() > 900 ? value.substring(0, 900) : value;
    }
}

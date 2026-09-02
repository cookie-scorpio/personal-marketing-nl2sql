package com.boc.nl2sql.service.quality;

import com.boc.nl2sql.domain.quality.QualityEventType;
import com.boc.nl2sql.domain.quality.QualityFact;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQL 诊断日志、结构化事实和连续指标的统一记录器。
 *
 * <p>查询处理器在正式校验和执行阶段调用，模型工具预检也会调用。组件只保存阶段和结果，
 * 不判断 SQL 是否允许执行。</p>
 */
@Component
public class SqlFactRecorder {
    private final ObjectMapper json;
    private final QualityFacts facts;
    private final MeterRegistry meters;

    public SqlFactRecorder(ObjectMapper json, QualityFacts facts, MeterRegistry meters) {
        this.json = json;
        this.facts = facts;
        this.meters = meters;
    }

    /**
     * 同时记录 SQL_REVIEW 日志、SQL_ATTEMPT_RECORDED 正式事实和阶段计数指标。
     *
     * @param source SQL 来源，例如规则、模型或模板
     * @param phase GENERATED、REJECTED、EXECUTING、EXECUTED、SQL_ERROR 等处理阶段
     * @param sql 当前候选 SQL；工具参数非法时可以为空
     * @param parameters SQL 参数或模型工具的完整参数
     * @param outcome 校验、执行或结果复核结论
     */
    public void record(String taskId, String requestId, Long userId, String source, String phase,
                       String sql, Map<String, Object> parameters, String outcome) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("task_id", taskId);
        entry.put("request_id", requestId);
        entry.put("source", source);
        entry.put("phase", phase);
        entry.put("sql", sql);
        entry.put("parameters", parameters == null ? Map.of() : parameters);
        entry.put("outcome", outcome);
        LoggerFactory.getLogger("SQL_REVIEW").info(json.writeValueAsString(entry));

        facts.publish(QualityFact.builder(QualityEventType.SQL_ATTEMPT_RECORDED, "EXECUTION")
                .requestId(requestId).taskId(taskId).userId(userId).sqlAttemptId(attemptId(taskId, sql))
                .summary(phase + (outcome == null ? "" : " " + outcome))
                .details(entry).evaluationCandidate(isCandidate(phase)).build());
        meters.counter("nl2sql.sql.attempts", "phase", phase == null ? "UNKNOWN" : phase).increment();
    }

    /** 将拒绝、SQL 错误、结果不匹配和超时阶段标记为评测候选。 */
    private boolean isCandidate(String phase) {
        return phase != null && java.util.Set.of("REJECTED", "SQL_ERROR", "RESULT_MISMATCH", "TIMED_OUT").contains(phase);
    }

    /**
     * 根据任务和 SQL 内容生成稳定关联编号，使同一候选的生成、校验和执行事实可以串联。
     */
    private String attemptId(String taskId, String sql) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(((taskId == null ? "" : taskId) + "\n" + (sql == null ? "" : sql)).getBytes(StandardCharsets.UTF_8));
            return (taskId == null ? "sql" : taskId) + ":" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception impossible) {
            return (taskId == null ? "sql" : taskId) + ":unknown";
        }
    }
}

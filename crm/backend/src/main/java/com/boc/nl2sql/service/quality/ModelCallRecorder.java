package com.boc.nl2sql.service.quality;

import com.boc.nl2sql.domain.quality.QualityEventType;
import com.boc.nl2sql.domain.quality.QualityFact;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保存完整模型请求、响应和调用指标，并落一条专用的大模型调用日志。
 *
 * <p>调用方可以提交提示词、消息、工具定义和原始响应，但不得把 Authorization、API Key、
 * 密码等传输凭据放入 request 或 response。</p>
 *
 * <p>除事实库外，每次调用都会通过 MODEL_CALL 记录器写一行 JSON 到 model.log，
 * 内容包含完整提示词（输入）与模型响应（输出），供日志中心的大模型日志页签排查异常。</p>
 */
@Component
public class ModelCallRecorder {
    private static final Logger MODEL_LOG = LoggerFactory.getLogger("MODEL_CALL");
    private final QualityFacts facts;
    private final MeterRegistry meters;
    private final ObjectMapper json;

    public ModelCallRecorder(QualityFacts facts, MeterRegistry meters, ObjectMapper json) {
        this.facts = facts;
        this.meters = meters;
        this.json = json;
    }

    /**
     * 记录一次成功获得 HTTP 响应的模型调用，并按提供方、用途和结果记录耗时。
     * HTTP 成功不等于响应一定能形成可执行计划，后续拒绝可另记 MODEL_RESPONSE_REJECTED。
     */
    public void completed(String callId, String purpose, String provider, String model, Long userId,
                          int attempt, int round, Map<String, Object> request, Map<String, Object> response,
                          long elapsedNanos) {
        Map<String, Object> payload = base(purpose, provider, model, attempt, round, request);
        payload.put("response", response);
        payload.put("elapsed_ms", Duration.ofNanos(elapsedNanos).toMillis());
        if (response != null && response.get("usage") != null) payload.put("usage", response.get("usage"));
        writeModelLog("completed", callId, userId, payload, null);
        facts.publish(baseFact(QualityEventType.MODEL_CALL_COMPLETED, callId, userId, purpose, payload, false).build());
        meters.timer("nl2sql.model.call.duration", "provider", safe(provider), "purpose", safe(purpose), "outcome", "success")
                .record(Duration.ofNanos(elapsedNanos));
    }

    /** 记录模型连接、超时或 HTTP 调用失败；失败事实自动进入评测候选池。 */
    public void failed(String callId, String purpose, String provider, String model, Long userId,
                       int attempt, int round, Map<String, Object> request, Throwable error, long elapsedNanos) {
        Map<String, Object> payload = base(purpose, provider, model, attempt, round, request);
        payload.put("elapsed_ms", Duration.ofNanos(elapsedNanos).toMillis());
        payload.put("error_type", error.getClass().getName());
        payload.put("error_message", String.valueOf(error.getMessage()));
        writeModelLog("failed", callId, userId, payload, error);
        facts.publish(baseFact(QualityEventType.MODEL_CALL_FAILED, callId, userId, purpose, payload, true).build());
        meters.timer("nl2sql.model.call.duration", "provider", safe(provider), "purpose", safe(purpose), "outcome", "failed")
                .record(Duration.ofNanos(elapsedNanos));
    }

    /**
     * 记录已经返回但被协议或内容检查拒绝的模型响应。第一阶段已提供能力，当前生产路径尚未接入。
     */
    public void rejected(String callId, String purpose, String provider, String model, Long userId,
                         Map<String, Object> response, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("purpose", safe(purpose));
        payload.put("provider", safe(provider));
        payload.put("model", safe(model));
        payload.put("response", response == null ? Map.of() : response);
        payload.put("reason", reason == null ? "" : reason);
        writeModelLog("rejected", callId, userId, payload, null);
        facts.publish(baseFact(QualityEventType.MODEL_RESPONSE_REJECTED, callId, userId, purpose, payload, true).build());
    }

    /**
     * 大模型调用日志：一行 JSON，包含输入提示词（request.messages）、模型输出（response）
     * 或失败原因，便于在日志中心直接检索异常调用的完整上下文。日志写入失败不影响事实库。
     */
    private void writeModelLog(String outcome, String callId, Long userId, Map<String, Object> payload, Throwable error) {
        try {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("outcome", outcome);
            entry.put("call_id", callId);
            entry.put("task_id", MDC.get("taskId"));
            entry.put("request_id", MDC.get("requestId"));
            entry.put("user_id", userId);
            entry.putAll(payload);
            MODEL_LOG.info(json.writeValueAsString(entry));
        } catch (RuntimeException ignored) {
            // 日志序列化失败只影响本条诊断日志，不阻断调用与事实记录。
        }
    }

    /** 模型调用用途的中文释义，写入事实摘要供数据回流页展示。 */
    private static final Map<String, String> PURPOSE_LABELS = Map.of(
            "INTERPRET", "意图识别与计划生成", "REPAIR", "SQL 修复", "RESULT_REVIEW", "结果结构复核");

    /** 从 MDC 取得当前请求和任务编号，构造模型事实公共信封。 */
    private QualityFact.Builder baseFact(QualityEventType type, String callId, Long userId, String purpose,
                                         Map<String, Object> payload, boolean candidate) {
        return QualityFact.builder(type, "MODEL")
                .requestId(MDC.get("requestId")).taskId(MDC.get("taskId")).userId(userId).modelCallId(callId)
                .summary(PURPOSE_LABELS.getOrDefault(safe(purpose), safe(purpose)))
                .details(payload).evaluationCandidate(candidate);
    }

    /** 组装成功和失败事实共享的模型调用参数。 */
    private Map<String, Object> base(String purpose, String provider, String model, int attempt, int round,
                                     Map<String, Object> request) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("purpose", safe(purpose));
        payload.put("provider", safe(provider));
        payload.put("model", safe(model));
        payload.put("attempt", attempt);
        payload.put("round", round);
        payload.put("request", request == null ? Map.of() : request);
        return payload;
    }

    /** 指标标签和摘要不能为空，缺失值统一使用 UNKNOWN。 */
    private String safe(String value) { return value == null || value.isBlank() ? "UNKNOWN" : value; }
}

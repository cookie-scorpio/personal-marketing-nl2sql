package com.boc.nl2sql.quality.collection;

import com.boc.nl2sql.quality.event.QualityEventType;
import com.boc.nl2sql.quality.event.QualityFact;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 保存完整模型请求/响应，但调用方不得传入 Authorization、API Key 等传输凭据。 */
@Component
public class ModelCallRecorder {
    private final QualityFacts facts;
    private final MeterRegistry meters;

    public ModelCallRecorder(QualityFacts facts, MeterRegistry meters) {
        this.facts = facts;
        this.meters = meters;
    }

    public void completed(String callId, String purpose, String provider, String model, Long userId,
                          int attempt, int round, Map<String, Object> request, Map<String, Object> response,
                          long elapsedNanos) {
        Map<String, Object> payload = base(purpose, provider, model, attempt, round, request);
        payload.put("response", response);
        payload.put("elapsed_ms", Duration.ofNanos(elapsedNanos).toMillis());
        if (response != null && response.get("usage") != null) payload.put("usage", response.get("usage"));
        facts.publish(baseFact(QualityEventType.MODEL_CALL_COMPLETED, callId, userId, purpose, payload, false).build());
        meters.timer("nl2sql.model.call.duration", "provider", safe(provider), "purpose", safe(purpose), "outcome", "success")
                .record(Duration.ofNanos(elapsedNanos));
    }

    public void failed(String callId, String purpose, String provider, String model, Long userId,
                       int attempt, int round, Map<String, Object> request, Throwable error, long elapsedNanos) {
        Map<String, Object> payload = base(purpose, provider, model, attempt, round, request);
        payload.put("elapsed_ms", Duration.ofNanos(elapsedNanos).toMillis());
        payload.put("error_type", error.getClass().getName());
        payload.put("error_message", String.valueOf(error.getMessage()));
        facts.publish(baseFact(QualityEventType.MODEL_CALL_FAILED, callId, userId, purpose, payload, true).build());
        meters.timer("nl2sql.model.call.duration", "provider", safe(provider), "purpose", safe(purpose), "outcome", "failed")
                .record(Duration.ofNanos(elapsedNanos));
    }

    public void rejected(String callId, String purpose, String provider, String model, Long userId,
                         Map<String, Object> response, String reason) {
        facts.publish(baseFact(QualityEventType.MODEL_RESPONSE_REJECTED, callId, userId, purpose,
                Map.of("provider", safe(provider), "model", safe(model), "response", response == null ? Map.of() : response,
                        "reason", reason == null ? "" : reason), true).build());
    }

    private QualityFact.Builder baseFact(QualityEventType type, String callId, Long userId, String purpose,
                                         Map<String, Object> payload, boolean candidate) {
        return QualityFact.builder(type, "MODEL")
                .requestId(MDC.get("requestId")).taskId(MDC.get("taskId")).userId(userId).modelCallId(callId)
                .summary(safe(purpose)).details(payload).evaluationCandidate(candidate);
    }

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

    private String safe(String value) { return value == null || value.isBlank() ? "UNKNOWN" : value; }
}

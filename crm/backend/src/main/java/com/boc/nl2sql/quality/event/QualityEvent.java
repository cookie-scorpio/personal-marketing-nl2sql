package com.boc.nl2sql.quality.event;

import java.time.LocalDateTime;
import java.util.Map;

/** F 内部持久化事件信封。 */
public record QualityEvent(
        String eventId,
        int schemaVersion,
        String eventType,
        String sourceModule,
        String eventSource,
        String requestId,
        String sessionId,
        String taskId,
        Long messageId,
        Long userId,
        String modelCallId,
        String sqlAttemptId,
        String evaluationRunId,
        boolean evaluationCandidate,
        String summary,
        LocalDateTime occurredAt,
        Map<String, Object> payload
) { }

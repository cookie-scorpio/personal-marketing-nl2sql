package com.boc.nl2sql.conversation.api;

import com.boc.nl2sql.execution.domain.QueryResult;
import com.boc.nl2sql.nl2sql.domain.ClarificationQuestion;

import java.util.Map;

public record TaskStatusResponse(
        String taskId,
        String sessionId,
        String status,
        int progress,
        String message,
        String intent,
        int clarificationRound,
        ClarificationQuestion question,
        Map<String, Object> confirmation,
        QueryResult result,
        Map<String, String> error
) {
}

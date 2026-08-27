package com.boc.nl2sql.conversation.api;

public record SubmitQueryResponse(
        String taskId,
        String sessionId,
        String status,
        int progress,
        String statusUrl
) {
}

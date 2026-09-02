package com.boc.nl2sql.conversation.api;

/** 异步查询提交结果；客户端通过 statusUrl 或事件流继续观察任务。 */
public record SubmitQueryResponse(
        String taskId,
        String sessionId,
        String status,
        int progress,
        String statusUrl
) {
}

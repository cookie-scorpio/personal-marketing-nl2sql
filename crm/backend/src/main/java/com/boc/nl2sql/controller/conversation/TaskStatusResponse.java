package com.boc.nl2sql.controller.conversation;

import com.boc.nl2sql.domain.execution.QueryResult;
import com.boc.nl2sql.domain.nl2sql.ClarificationQuestion;

import java.util.Map;
import java.util.List;

/**
 * 可持久化查询任务的完整客户端快照。
 * 澄清、确认、结果和错误按任务状态互斥出现，stateVersion 用于识别状态推进。
 */
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
        Map<String, String> error,
        int repairAttempts,
        List<SqlRepairResponse> repairs,
        int executionTimeoutSeconds,
        boolean cancellable,
        long stateVersion,
        boolean thinkingEnabled,
        String displayQuery,
        CustomerCard resolvedCustomer,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt
) {
    public record CustomerCard(String customerId,String name,String branchId,String mobile) {}
}

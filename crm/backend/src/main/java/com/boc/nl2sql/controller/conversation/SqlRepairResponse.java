package com.boc.nl2sql.controller.conversation;

import java.time.LocalDateTime;

/** 当前用户可查看的SQL修复轨迹；不包含模型思考正文或查询结果值。 */
public record SqlRepairResponse(
        long repairId,
        int attemptNo,
        String triggerPhase,
        String status,
        String originalSql,
        String failureReason,
        String repairReason,
        String repairedSql,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) { }

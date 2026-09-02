package com.boc.nl2sql.controller.conversation;

import jakarta.validation.constraints.NotBlank;

/** 对高风险或大范围查询的确认决定；confirmToken 防止确认内容与原计划错配。 */
public record ConfirmationRequest(
        @NotBlank String confirmToken,
        @NotBlank String decision,
        String reason
) {
}

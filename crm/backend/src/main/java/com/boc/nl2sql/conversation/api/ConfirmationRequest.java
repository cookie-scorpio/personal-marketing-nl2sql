package com.boc.nl2sql.conversation.api;

import jakarta.validation.constraints.NotBlank;

public record ConfirmationRequest(
        @NotBlank String confirmToken,
        @NotBlank String decision,
        String reason
) {
}

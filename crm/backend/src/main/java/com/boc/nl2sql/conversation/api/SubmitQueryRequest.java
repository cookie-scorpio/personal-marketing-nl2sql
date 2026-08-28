package com.boc.nl2sql.conversation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitQueryRequest(
        @NotBlank @Size(max = 36) String sessionId,
        @NotBlank @Size(max = 1000) String queryText,
        String preferredDisplay,
        Boolean thinkingEnabled
) {
    public SubmitQueryRequest(String sessionId,String queryText,String preferredDisplay) {
        this(sessionId,queryText,preferredDisplay,true);
    }
}

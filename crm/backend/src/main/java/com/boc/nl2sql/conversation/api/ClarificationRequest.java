package com.boc.nl2sql.conversation.api;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ClarificationRequest(
        @NotBlank String taskId,
        @NotBlank String questionId,
        String answerText,
        List<String> selectedOptions
) {
    public String mergedAnswer() {
        if (answerText != null && !answerText.isBlank()) return answerText.trim();
        return selectedOptions == null ? "" : String.join("、", selectedOptions);
    }
}

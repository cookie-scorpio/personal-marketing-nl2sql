package com.boc.nl2sql.conversation.api;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ClarificationRequest(
        @NotBlank String taskId,
        @NotBlank String questionId,
        String answerText,
        List<String> selectedOptions,
        String identityType
) {
    public ClarificationRequest(String taskId,String questionId,String answerText,List<String> selectedOptions) {
        this(taskId,questionId,answerText,selectedOptions,null);
    }
    public String mergedAnswer() {
        if (answerText != null && !answerText.isBlank()) return answerText.trim();
        return selectedOptions == null ? "" : String.join("、", selectedOptions);
    }
}

package com.boc.nl2sql.conversation.api;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 用户对澄清问题的回答；既支持自由文本，也支持一个或多个预设选项。 */
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
    /** 自由文本优先，未填写文本时再把多选项合并为领域层可处理的答案。 */
    public String mergedAnswer() {
        if (answerText != null && !answerText.isBlank()) return answerText.trim();
        return selectedOptions == null ? "" : String.join("、", selectedOptions);
    }
}

package com.boc.nl2sql.nl2sql.domain;

import java.util.List;
import java.util.Map;

public record ClarificationQuestion(
        String questionId,
        String type,
        String prompt,
        List<String> options,
        Map<String, String> recognizedSlots
) {
}

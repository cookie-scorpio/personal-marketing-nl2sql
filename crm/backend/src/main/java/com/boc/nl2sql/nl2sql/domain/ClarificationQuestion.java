package com.boc.nl2sql.nl2sql.domain;

import java.util.List;
import java.util.Map;

public record ClarificationQuestion(
        String questionId,
        String type,
        String prompt,
        List<String> options,
        Map<String, String> recognizedSlots,
        List<com.boc.nl2sql.conversation.application.CustomerResolver.Candidate> candidates
) {
    public ClarificationQuestion { candidates=candidates==null?List.of():List.copyOf(candidates); }
    public ClarificationQuestion(String id,String type,String prompt,List<String> options,Map<String,String> slots){this(id,type,prompt,options,slots,List.of());}
}

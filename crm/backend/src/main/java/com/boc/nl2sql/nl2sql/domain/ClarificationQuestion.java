package com.boc.nl2sql.nl2sql.domain;

import java.util.List;
import java.util.Map;

public record ClarificationQuestion(
        String questionId,
        String type,
        String prompt,
        List<String> options,
        Map<String, String> recognizedSlots,
        List<com.boc.nl2sql.conversation.application.CustomerResolver.Candidate> candidates,
        List<String> inputTypes
) {
    public ClarificationQuestion {
        candidates=candidates==null?List.of():List.copyOf(candidates);
        inputTypes=type!=null && type.startsWith("CUSTOMER_") && !"CUSTOMER_SELECTION".equals(type)
                ? List.of("CUSTOMER_ID","CUSTOMER_NAME","MOBILE_SUFFIX") : List.of();
    }
    public ClarificationQuestion(String id,String type,String prompt,List<String> options,Map<String,String> slots,List<com.boc.nl2sql.conversation.application.CustomerResolver.Candidate> candidates){this(id,type,prompt,options,slots,candidates,List.of());}
    public ClarificationQuestion(String id,String type,String prompt,List<String> options,Map<String,String> slots){this(id,type,prompt,options,slots,List.of());}
}

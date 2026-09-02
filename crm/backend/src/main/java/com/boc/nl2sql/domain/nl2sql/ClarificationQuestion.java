package com.boc.nl2sql.domain.nl2sql;

import java.util.List;
import java.util.Map;

public record ClarificationQuestion(
        String questionId,
        String type,
        String prompt,
        List<String> options,
        Map<String, String> recognizedSlots,
        List<com.boc.nl2sql.service.conversation.CustomerResolver.Candidate> candidates,
        List<String> inputTypes,
        String recommendedOption
) {
    public ClarificationQuestion {
        candidates=candidates==null?List.of():List.copyOf(candidates);
        // 客户定位统一使用检索浮窗；inputTypes 只为兼容旧响应结构而保留空值。
        inputTypes=List.of();
    }
    public ClarificationQuestion(String id,String type,String prompt,List<String> options,Map<String,String> slots,List<com.boc.nl2sql.service.conversation.CustomerResolver.Candidate> candidates){this(id,type,prompt,options,slots,candidates,List.of(),null);}
    public ClarificationQuestion(String id,String type,String prompt,List<String> options,Map<String,String> slots){this(id,type,prompt,options,slots,List.of(),List.of(),null);}
    public ClarificationQuestion(String id,String type,String prompt,List<String> options,Map<String,String> slots,String recommended){this(id,type,prompt,options,slots,List.of(),List.of(),recommended);}
    /** 带推荐项：recommended 排到选项首位，前端据此标注“推荐”。 */
    public ClarificationQuestion withRecommended(String recommended){
        if(recommended==null || options.size()<2) return this;
        var ordered=new java.util.ArrayList<String>();
        ordered.add(recommended);
        for(String o:options) if(!o.equals(recommended)) ordered.add(o);
        return new ClarificationQuestion(questionId,type,prompt,List.copyOf(ordered),recognizedSlots,candidates,inputTypes,recommended);
    }
}

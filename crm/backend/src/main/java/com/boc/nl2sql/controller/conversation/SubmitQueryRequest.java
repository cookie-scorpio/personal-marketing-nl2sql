package com.boc.nl2sql.controller.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 新查询请求。customerIds 只表达用户明确选择的客户名单，最终数据范围仍由服务端授权校验。
 */
public record SubmitQueryRequest(
        @NotBlank @Size(max = 36) String sessionId,
        @NotBlank @Size(max = 1000) String queryText,
        String preferredDisplay,
        Boolean thinkingEnabled,
        List<String> customerIds,
        Integer pageNo,
        Integer pageSize,
        Integer limit,
        Long offset
) {
    public SubmitQueryRequest(String sessionId,String queryText,String preferredDisplay) {
        this(sessionId,queryText,preferredDisplay,true,null,null,null,null,null);
    }
    public SubmitQueryRequest(String sessionId,String queryText,String preferredDisplay,
                              Boolean thinkingEnabled,List<String> customerIds) {
        this(sessionId,queryText,preferredDisplay,thinkingEnabled,customerIds,null,null,null,null);
    }
}

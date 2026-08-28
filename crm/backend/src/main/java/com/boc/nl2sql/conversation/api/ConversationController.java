package com.boc.nl2sql.conversation.api;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.web.WebRequestSupport;
import com.boc.nl2sql.conversation.application.ConversationStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationStore store;
    public ConversationController(ConversationStore store){this.store=store;}
    @GetMapping
    public ApiResponse<List<Map<String,Object>>> list(@AuthenticationPrincipal CurrentUser user,@RequestParam(defaultValue="1",name="page_no")int page,@RequestParam(defaultValue="30",name="page_size")int size,HttpServletRequest request){
        return ApiResponse.success(store.list(user,page,size),WebRequestSupport.requestId(request));
    }
    @GetMapping("/{id}")
    public ApiResponse<Map<String,Object>> detail(@PathVariable String id,@AuthenticationPrincipal CurrentUser user,
            @RequestParam(defaultValue="0",name="before_message_id")long before,@RequestParam(defaultValue="100",name="page_size")int size,HttpServletRequest request){
        return ApiResponse.success(store.detail(id,user,before,size),WebRequestSupport.requestId(request));
    }
}

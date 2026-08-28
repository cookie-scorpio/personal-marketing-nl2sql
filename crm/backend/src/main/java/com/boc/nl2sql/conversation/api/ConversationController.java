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
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String,Object>> delete(@PathVariable String id,@AuthenticationPrincipal CurrentUser user,HttpServletRequest request){
        store.delete(id,user,WebRequestSupport.requestId(request));return ApiResponse.success(Map.of("session_id",id,"deleted",true),WebRequestSupport.requestId(request));
    }
    @GetMapping("/{id}/anchors")
    public ApiResponse<List<Map<String,Object>>> anchors(@PathVariable String id,@AuthenticationPrincipal CurrentUser user,
            @RequestParam(defaultValue="0",name="after_message_id") long after,@RequestParam(defaultValue="100",name="page_size") int size,HttpServletRequest request){
        return ApiResponse.success(store.anchors(id,user,after,size),WebRequestSupport.requestId(request));
    }
    public record FeedbackRequest(String feedback){}
    @PostMapping("/{id}/messages/{messageId}/feedback")
    public ApiResponse<Map<String,Object>> feedback(@PathVariable String id,@PathVariable long messageId,@RequestBody FeedbackRequest body,
            @AuthenticationPrincipal CurrentUser user,HttpServletRequest request){
        return ApiResponse.success(store.feedback(id,messageId,body.feedback(),user),WebRequestSupport.requestId(request));
    }
    @GetMapping("/{id}")
    public ApiResponse<Map<String,Object>> detail(@PathVariable String id,@AuthenticationPrincipal CurrentUser user,
            @RequestParam(defaultValue="0",name="before_message_id")long before,@RequestParam(defaultValue="100",name="page_size")int size,HttpServletRequest request){
        return ApiResponse.success(store.detail(id,user,before,size),WebRequestSupport.requestId(request));
    }
}

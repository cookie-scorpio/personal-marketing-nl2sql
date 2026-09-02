package com.boc.nl2sql.controller.conversation;

import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.web.WebRequestSupport;
import com.boc.nl2sql.service.conversation.QueryApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询任务的 HTTP 边界。
 * 用户身份始终取自认证上下文，客户端请求体不能替换任务所属用户。
 */
@RestController
@RequestMapping("/api/v1")
public class QueryController {
    private final QueryApplicationService service;

    public QueryController(QueryApplicationService service) {
        this.service = service;
    }

    @PostMapping("/queries")
    public ApiResponse<SubmitQueryResponse> submit(@Valid @RequestBody SubmitQueryRequest body,
                                                   @AuthenticationPrincipal CurrentUser user,
                                                   HttpServletRequest request) {
        return ApiResponse.success(service.submit(body, user, WebRequestSupport.requestId(request),request.getHeader("Idempotency-Key")),
                WebRequestSupport.requestId(request));
    }

    @GetMapping("/queries/{taskId}/status")
    public ApiResponse<TaskStatusResponse> status(@PathVariable String taskId,
                                                  @AuthenticationPrincipal CurrentUser user,
                                                  HttpServletRequest request) {
        return ApiResponse.success(service.status(taskId, user), WebRequestSupport.requestId(request));
    }

    @PostMapping("/conversations/{sessionId}/messages")
    public ApiResponse<SubmitQueryResponse> clarify(@PathVariable String sessionId,
                                                    @Valid @RequestBody ClarificationRequest body,
                                                    @AuthenticationPrincipal CurrentUser user,
                                                    HttpServletRequest request) {
        return ApiResponse.success(service.clarify(sessionId, body, user, WebRequestSupport.requestId(request)),
                WebRequestSupport.requestId(request));
    }

    @PostMapping("/queries/{taskId}/confirmations")
    public ApiResponse<TaskStatusResponse> confirm(@PathVariable String taskId,
                                                   @Valid @RequestBody ConfirmationRequest body,
                                                   @AuthenticationPrincipal CurrentUser user,
                                                   HttpServletRequest request) {
        return ApiResponse.success(service.confirm(taskId, body, user, WebRequestSupport.requestId(request)),
                WebRequestSupport.requestId(request));
    }

    @PostMapping("/queries/{taskId}/cancel")
    public ApiResponse<TaskStatusResponse> cancel(@PathVariable String taskId,
            @AuthenticationPrincipal CurrentUser user, HttpServletRequest request) {
        return ApiResponse.success(service.cancel(taskId, user, WebRequestSupport.requestId(request)),
                WebRequestSupport.requestId(request));
    }
}

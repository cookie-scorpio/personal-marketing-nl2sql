package com.boc.nl2sql.controller.conversation;

import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.web.WebRequestSupport;
import com.boc.nl2sql.domain.execution.QueryResult;
import com.boc.nl2sql.service.conversation.QueryApplicationService;
import com.boc.nl2sql.service.conversation.QueryResultPageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询任务的 HTTP 边界。
 * 用户身份始终取自认证上下文，客户端请求体不能替换任务所属用户。
 */
@RestController
@RequestMapping("/api/v1")
public class QueryController {
    private final QueryApplicationService service;
    private final QueryResultPageService resultPages;

    public QueryController(QueryApplicationService service, QueryResultPageService resultPages) {
        this.service = service;
        this.resultPages = resultPages;
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

    /**
     * 读取已完成任务的指定结果页。翻页不创建新会话消息，也不会再次调用大模型。
     */
    @GetMapping("/queries/{taskId}/results")
    public ApiResponse<QueryResult> resultPage(
            @PathVariable String taskId,
            @RequestParam(name = "page_no", defaultValue = "1") int pageNo,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @AuthenticationPrincipal CurrentUser user,
            HttpServletRequest request) {
        return ApiResponse.success(resultPages.page(taskId, pageNo, pageSize, user),
                WebRequestSupport.requestId(request));
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

package com.boc.nl2sql.conversation.api;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.web.WebRequestSupport;
import com.boc.nl2sql.conversation.application.QueryApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        return ApiResponse.success(service.submit(body, user, WebRequestSupport.requestId(request)),
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
}

package com.boc.nl2sql.common.exception;

import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.web.WebRequestSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.quality.collection.QualityFacts;
import com.boc.nl2sql.quality.event.QualityEventType;
import com.boc.nl2sql.quality.event.QualityFact;
import org.springframework.security.core.context.SecurityContextHolder;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private QualityFacts qualityFacts;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException exception, HttpServletRequest request) {
        HttpStatus status = statusOf(exception.code());
        if (qualityFacts != null && (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN)) {
            qualityFacts.publish(QualityFact.builder(QualityEventType.ACCESS_AUTHORIZATION_DENIED, "ACCESS")
                    .requestId(WebRequestSupport.requestId(request)).userId(currentUserId()).summary(exception.getMessage())
                    .detail("code", exception.code()).detail("method", request.getMethod())
                    .detail("path", request.getRequestURI()).build());
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.error(exception.code(), exception.getMessage(), WebRequestSupport.requestId(request)));
    }

    /** 业务码段与HTTP状态一一对应：前端与网关据此区分参数错误、权限拒绝、限流与上游故障。 */
    private HttpStatus statusOf(int code) {
        if (code == 401001) return HttpStatus.UNAUTHORIZED;
        if (code == 404001) return HttpStatus.NOT_FOUND;
        int prefix = code / 1000;
        if (prefix == 403) return HttpStatus.FORBIDDEN;
        if (prefix == 409) return HttpStatus.CONFLICT;
        if (prefix == 429) return HttpStatus.TOO_MANY_REQUESTS;
        if (prefix == 502 || prefix == 503) return HttpStatus.BAD_GATEWAY;
        return HttpStatus.BAD_REQUEST;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException exception,
                                                         HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + error.getDefaultMessage())
                .orElse("请求参数不正确");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400001, message, WebRequestSupport.requestId(request)));
    }

    /** 将不存在的 API 路径明确映射为 404，避免被兜底处理器误报成系统内部错误。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NoResourceFoundException exception,
                                                       HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(404001, "请求地址不存在", WebRequestSupport.requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unknown(Exception exception, HttpServletRequest request) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        log.error("Unhandled request error: type={}, message={}, rootType={}, rootMessage={}, frames={}",
                exception.getClass().getName(), exception.getMessage(), root.getClass().getName(), root.getMessage(),
                java.util.Arrays.toString(exception.getStackTrace()));
        if (qualityFacts != null) qualityFacts.publish(QualityFact.builder(QualityEventType.RUNTIME_FAILURE, "COMMON")
                .requestId(WebRequestSupport.requestId(request)).userId(currentUserId()).summary(exception.getClass().getSimpleName())
                .detail("method", request.getMethod()).detail("path", request.getRequestURI())
                .detail("error_type", exception.getClass().getName()).detail("error_message", exception.getMessage()).build());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500000, "系统暂时无法处理该请求，请稍后重试", WebRequestSupport.requestId(request)));
    }

    private Long currentUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof CurrentUser user ? user.userId() : null;
    }
}

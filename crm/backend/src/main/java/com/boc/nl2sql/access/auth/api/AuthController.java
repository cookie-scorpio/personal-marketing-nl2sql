package com.boc.nl2sql.access.auth.api;

import com.boc.nl2sql.access.auth.application.AuthService;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.web.WebRequestSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return ApiResponse.success(authService.login(body), WebRequestSupport.requestId(request));
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUser> me(@AuthenticationPrincipal CurrentUser user, HttpServletRequest request) {
        return ApiResponse.success(user, WebRequestSupport.requestId(request));
    }
}

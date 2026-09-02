package com.boc.nl2sql.controller.access;

import com.boc.nl2sql.service.access.AuthService;
import com.boc.nl2sql.service.access.PasswordCipher;
import com.boc.nl2sql.service.access.RegistrationService;
import com.boc.nl2sql.domain.authorization.CurrentUser;
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
    private final RegistrationService registrationService;
    private final PasswordCipher passwordCipher;

    public AuthController(AuthService authService, RegistrationService registrationService, PasswordCipher passwordCipher) {
        this.authService = authService;
        this.registrationService = registrationService;
        this.passwordCipher = passwordCipher;
    }

    /** 只公开 SPKI 公钥与密钥标识，私钥始终停留在部署环境。 */
    @GetMapping("/public-key")
    public ApiResponse<PasswordCipher.PublicKeyInfo> publicKey(HttpServletRequest request) {
        return ApiResponse.success(passwordCipher.publicKey(), WebRequestSupport.requestId(request));
    }

    @PostMapping("/register")
    public ApiResponse<RegistrationResponse> register(@Valid @RequestBody RegistrationRequest body,
                                                       HttpServletRequest request) {
        return ApiResponse.success(registrationService.register(body), WebRequestSupport.requestId(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return ApiResponse.success(authService.login(body), WebRequestSupport.requestId(request));
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUser> me(@AuthenticationPrincipal CurrentUser user, HttpServletRequest request) {
        return ApiResponse.success(user, WebRequestSupport.requestId(request));
    }

    /** 切换身份后返回新的短期令牌，前端刷新工作区以清除上一身份的暂存状态。 */
    @PostMapping("/switch-identity")
    public ApiResponse<LoginResponse> switchIdentity(@Valid @RequestBody SwitchIdentityRequest body,
                                                      @AuthenticationPrincipal CurrentUser user,
                                                      HttpServletRequest request) {
        return ApiResponse.success(authService.switchIdentity(user, body.role()), WebRequestSupport.requestId(request));
    }
}

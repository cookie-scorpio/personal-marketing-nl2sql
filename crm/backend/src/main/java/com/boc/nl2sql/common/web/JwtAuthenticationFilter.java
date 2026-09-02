package com.boc.nl2sql.common.web;

import com.boc.nl2sql.service.access.JwtService;
import com.boc.nl2sql.dao.authorization.UserAccountMapper;
import com.boc.nl2sql.domain.authorization.AccountStatus;
import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.common.web.WebRequestSupport;
import com.boc.nl2sql.service.quality.QualityFacts;
import com.boc.nl2sql.domain.quality.QualityEventType;
import com.boc.nl2sql.domain.quality.QualityFact;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * 将 Bearer JWT 转换为 Spring Security 身份。
 * 无效令牌在进入控制器前被转换为统一 API 错误，同时留下认证失败事实。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserAccountMapper accounts;
    private final ObjectMapper objectMapper;
    private final QualityFacts qualityFacts;

    public JwtAuthenticationFilter(JwtService jwtService, UserAccountMapper accounts,
                                   ObjectMapper objectMapper, QualityFacts qualityFacts) {
        this.jwtService = jwtService;
        this.accounts = accounts;
        this.objectMapper = objectMapper;
        this.qualityFacts = qualityFacts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            try {
                CurrentUser user = jwtService.verify(authorization.substring(7));
                /*
                 * JWT 验签只能证明令牌由本系统签发，不能证明账号此刻仍然存在。
                 * 每次请求复核账号状态，确保管理员删除账号后，其尚未过期的旧令牌也立即失效。
                 */
                var account = accounts.selectById(user.userId());
                if (account == null || !Boolean.TRUE.equals(account.getEnabled())
                        || !AccountStatus.ACTIVE.name().equals(account.getAccountStatus())) {
                    throw new BusinessException(401001, "登录状态已失效，请重新登录");
                }
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        user, "", List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (BusinessException exception) {
                qualityFacts.publish(QualityFact.builder(QualityEventType.ACCESS_AUTHENTICATION_FAILED, "ACCESS")
                        .requestId(WebRequestSupport.requestId(request)).summary("invalid token")
                        .detail("method", request.getMethod()).detail("path", request.getRequestURI())
                        .detail("code", exception.code()).build());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        ApiResponse.error(exception.code(), exception.getMessage(), WebRequestSupport.requestId(request))));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

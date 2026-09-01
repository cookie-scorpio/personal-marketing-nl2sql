package com.boc.nl2sql.access.security;

import com.boc.nl2sql.access.auth.application.JwtService;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.common.web.WebRequestSupport;
import com.boc.nl2sql.quality.collection.QualityFacts;
import com.boc.nl2sql.quality.event.QualityEventType;
import com.boc.nl2sql.quality.event.QualityFact;
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

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final QualityFacts qualityFacts;

    public JwtAuthenticationFilter(JwtService jwtService, ObjectMapper objectMapper, QualityFacts qualityFacts) {
        this.jwtService = jwtService;
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

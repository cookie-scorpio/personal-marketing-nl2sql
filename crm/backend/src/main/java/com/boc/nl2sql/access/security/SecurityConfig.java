package com.boc.nl2sql.access.security;

import com.boc.nl2sql.common.api.ApiResponse;
import com.boc.nl2sql.common.web.WebRequestSupport;
import com.boc.nl2sql.quality.collection.QualityFacts;
import com.boc.nl2sql.quality.event.QualityEventType;
import com.boc.nl2sql.quality.event.QualityFact;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
                                            ObjectMapper objectMapper, QualityFacts qualityFacts) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                    // 缺少 JWT 与无效 JWT 使用同一结构，前端可统一清理登录状态并引导重新登录。
                    qualityFacts.publish(QualityFact.builder(QualityEventType.ACCESS_AUTHENTICATION_FAILED, "ACCESS")
                            .requestId(WebRequestSupport.requestId(request)).summary("authentication required")
                            .detail("method", request.getMethod()).detail("path", request.getRequestURI()).build());
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(objectMapper.writeValueAsString(
                            ApiResponse.error(401001, "请先登录后再访问", WebRequestSupport.requestId(request))));
                }).accessDeniedHandler((request,response,exception)->{
                    var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    Long userId=authentication!=null&&authentication.getPrincipal() instanceof CurrentUser user?user.userId():null;
                    qualityFacts.publish(QualityFact.builder(QualityEventType.ACCESS_AUTHORIZATION_DENIED,"ACCESS")
                            .requestId(WebRequestSupport.requestId(request)).userId(userId).summary("access denied")
                            .detail("method",request.getMethod()).detail("path",request.getRequestURI()).build());
                    response.setStatus(403);response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(objectMapper.writeValueAsString(
                            ApiResponse.error(403001,"无权访问该功能",WebRequestSupport.requestId(request))));
                }))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/public-key",
                                "/actuator/health").permitAll()
                        .requestMatchers("/api/v1/auth/me", "/api/v1/auth/switch-identity").authenticated()
                        .requestMatchers("/api/v1/quality/**", "/actuator/metrics/**").hasAnyRole("QUALITY_AUDITOR", "QUALITY_ADMIN")
                        .requestMatchers("/api/v1/permission-admin/**").hasRole("PERMISSION_ADMIN")
                        .requestMatchers("/api/v1/**").access((authentication, context) -> {
                            var current = authentication.get();
                            // 质量审计员与客户经理都可进入问数接口；后续业务层仍会强制追加各自的数据范围。
                            boolean queryIdentity = current.getAuthorities().stream().anyMatch(authority ->
                                    "ROLE_CUSTOMER_MANAGER".equals(authority.getAuthority())
                                            || "ROLE_TEAM_LEAD".equals(authority.getAuthority())
                                            || "ROLE_ORG_MANAGER".equals(authority.getAuthority())
                                            || "ROLE_QUALITY_AUDITOR".equals(authority.getAuthority())
                                            || "ROLE_QUALITY_ADMIN".equals(authority.getAuthority()));
                            return new AuthorizationDecision(current.isAuthenticated() && queryIdentity);
                        })
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@org.springframework.beans.factory.annotation.Value("${app.security.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(java.util.Arrays.stream(origins.split(",")).map(String::trim).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-ID", "Last-Event-ID"));
        configuration.setExposedHeaders(List.of("X-Request-ID"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

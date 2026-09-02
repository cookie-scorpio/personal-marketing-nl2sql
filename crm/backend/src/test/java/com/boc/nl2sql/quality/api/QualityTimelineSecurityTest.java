package com.boc.nl2sql.quality.api;

import com.boc.nl2sql.access.security.JwtAuthenticationFilter;
import com.boc.nl2sql.access.security.SecurityConfig;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.authorization.domain.RoleCode;
import com.boc.nl2sql.quality.collection.QualityFacts;
import com.boc.nl2sql.quality.query.QualityTimelineQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QualityTimelineController.class)
@Import(SecurityConfig.class)
class QualityTimelineSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean QualityTimelineQuery timeline;
    @MockitoBean QualityFacts facts;
    @MockitoBean JwtAuthenticationFilter jwtFilter;

    @BeforeEach
    void letRequestsPassThroughMockedJwtFilter() throws Exception {
        doAnswer(invocation -> {
            var request = invocation.getArgument(0, jakarta.servlet.ServletRequest.class);
            var response = invocation.getArgument(1, jakarta.servlet.ServletResponse.class);
            var chain = invocation.getArgument(2, jakarta.servlet.FilterChain.class);
            chain.doFilter(request, response);
            return null;
        }).when(jwtFilter).doFilter(any(), any(), any());
    }

    @Test
    void qualityAdminCanViewTaskFacts() throws Exception {
        when(timeline.taskTimeline("task-1", 0, 200)).thenReturn(List.of());

        mvc.perform(get("/api/v1/quality/tasks/task-1/events").with(authentication(qualityAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void ordinaryUserCannotViewTaskFacts() throws Exception {
        mvc.perform(get("/api/v1/quality/tasks/task-1/events").with(authentication(businessUser())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403001));
    }

    @Test
    void qualityAuditorPassesBusinessApiSecurityBoundary() throws Exception {
        // 本 WebMvc 切片不加载会话控制器，因此通过安全过滤链后应落到 404，而不是被角色规则拦成 403。
        mvc.perform(get("/api/v1/conversations").with(authentication(qualityAdmin())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404001));
    }

    private UsernamePasswordAuthenticationToken qualityAdmin() {
        var user = new CurrentUser(9L, "quality01", "质量管理员", RoleCode.QUALITY_ADMIN,
                null, null, null);
        return UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_QUALITY_ADMIN")));
    }

    private UsernamePasswordAuthenticationToken businessUser() {
        var user = new CurrentUser(1L, "manager01", "客户经理", RoleCode.CUSTOMER_MANAGER,
                "EAST", "B001", "M0001");
        return UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER_MANAGER")));
    }
}

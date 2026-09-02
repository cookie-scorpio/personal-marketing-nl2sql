package com.boc.nl2sql.common.web;

import com.boc.nl2sql.dao.authorization.UserAccountMapper;
import com.boc.nl2sql.domain.authorization.AccountStatus;
import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.domain.authorization.RoleCode;
import com.boc.nl2sql.domain.authorization.UserAccountEntity;
import com.boc.nl2sql.service.access.JwtService;
import com.boc.nl2sql.service.quality.QualityFacts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证账号删除或停用后，即使旧 JWT 仍在有效期内也不能继续访问系统。 */
class JwtAuthenticationFilterTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsSignedTokenWhenItsAccountHasAlreadyBeenDeleted() throws Exception {
        JwtService jwt = mock(JwtService.class);
        UserAccountMapper accounts = mock(UserAccountMapper.class);
        CurrentUser current = new CurrentUser(8L, "manager08", "林书言",
                RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0008");
        when(jwt.verify("signed-token")).thenReturn(current);
        when(accounts.selectById(8L)).thenReturn(null);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwt, accounts, JsonMapper.builder().build(), mock(QualityFacts.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/conversations");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer signed-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedApplication = new AtomicBoolean(false);

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> reachedApplication.set(true));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("登录状态已失效，请重新登录");
        assertThat(reachedApplication).isFalse();
    }

    @Test
    void acceptsSignedTokenOnlyWhileItsAccountRemainsActive() throws Exception {
        JwtService jwt = mock(JwtService.class);
        UserAccountMapper accounts = mock(UserAccountMapper.class);
        CurrentUser current = new CurrentUser(8L, "manager08", "林书言",
                RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0008");
        UserAccountEntity account = new UserAccountEntity();
        account.setId(8L);
        account.setEnabled(true);
        account.setAccountStatus(AccountStatus.ACTIVE.name());
        when(jwt.verify("signed-token")).thenReturn(current);
        when(accounts.selectById(8L)).thenReturn(account);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwt, accounts, JsonMapper.builder().build(), mock(QualityFacts.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/conversations");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer signed-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reachedApplication = new AtomicBoolean(false);

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> reachedApplication.set(true));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(reachedApplication).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(current);
    }
}

package com.boc.nl2sql.access.auth.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.boc.nl2sql.access.auth.api.LoginRequest;
import com.boc.nl2sql.access.auth.api.LoginResponse;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.authorization.domain.RoleCode;
import com.boc.nl2sql.authorization.infrastructure.UserAccountEntity;
import com.boc.nl2sql.authorization.infrastructure.UserAccountMapper;
import com.boc.nl2sql.common.exception.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserAccountMapper userAccountMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        UserAccountEntity account = userAccountMapper.selectOne(Wrappers.<UserAccountEntity>lambdaQuery()
                .eq(UserAccountEntity::getUsername, request.username())
                .last("LIMIT 1"));
        // 账号不存在和密码错误使用同一提示，避免暴露有效用户名。
        if (account == null || !Boolean.TRUE.equals(account.getEnabled())
                || !passwordEncoder.matches(request.password(), account.getPasswordHash())) {
            throw new BusinessException(401001, "用户名或密码不正确");
        }
        CurrentUser user = toCurrentUser(account);
        return new LoginResponse(jwtService.issue(user), "Bearer", jwtService.ttlSeconds(), user);
    }

    static CurrentUser toCurrentUser(UserAccountEntity account) {
        return new CurrentUser(account.getId(), account.getUsername(), account.getDisplayName(),
                RoleCode.valueOf(account.getRoleCode()), account.getRegionCode(), account.getBranchId(),
                account.getManagerId());
    }
}

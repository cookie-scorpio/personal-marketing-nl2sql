package com.boc.nl2sql.access.auth.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.boc.nl2sql.access.auth.api.LoginRequest;
import com.boc.nl2sql.access.auth.api.LoginResponse;
import com.boc.nl2sql.authorization.domain.AccountStatus;
import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.authorization.domain.RoleCode;
import com.boc.nl2sql.authorization.infrastructure.UserAccountEntity;
import com.boc.nl2sql.authorization.infrastructure.UserAccountMapper;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.quality.collection.QualityFacts;
import com.boc.nl2sql.quality.event.QualityEventType;
import com.boc.nl2sql.quality.event.QualityFact;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final PasswordCipher passwordCipher;
    private final UsernamePolicy usernamePolicy;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private QualityFacts qualityFacts;

    public AuthService(UserAccountMapper userAccountMapper, PasswordEncoder passwordEncoder, JwtService jwtService,
                       PasswordCipher passwordCipher, UsernamePolicy usernamePolicy) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.passwordCipher = passwordCipher;
        this.usernamePolicy = usernamePolicy;
    }

    public LoginResponse login(LoginRequest request) {
        String username = usernamePolicy.normalizeAndValidate(request.username());
        if (request.password() == null) {
            throw new BusinessException(400013, "请提供加密后的密码");
        }
        String password = passwordCipher.decrypt(request.password().keyId(), request.password().encryptedPassword());
        UserAccountEntity account = userAccountMapper.selectOne(Wrappers.<UserAccountEntity>lambdaQuery()
                .eq(UserAccountEntity::getUsername, username)
                .last("LIMIT 1"));
        // 账号不存在和密码错误使用同一提示，避免暴露有效用户名。
        if (account == null || !passwordEncoder.matches(password, account.getPasswordHash())) {
            access(QualityEventType.ACCESS_LOGIN_FAILED, null, username, "invalid credentials");
            throw new BusinessException(401001, "用户名或密码不正确");
        }
        // 仅在凭据正确后告知待审批状态，既满足用户提示要求，也不为外部探测提供账号状态。
        if (AccountStatus.PENDING.name().equals(account.getAccountStatus())) {
            access(QualityEventType.ACCESS_LOGIN_FAILED, account.getId(), username, "account pending");
            throw new BusinessException(403101, "账号待审批，暂不可登录");
        }
        if (!Boolean.TRUE.equals(account.getEnabled()) || !AccountStatus.ACTIVE.name().equals(account.getAccountStatus())) {
            access(QualityEventType.ACCESS_LOGIN_FAILED, account.getId(), username, "account disabled");
            throw new BusinessException(401001, "用户名或密码不正确");
        }
        CurrentUser user = toCurrentUser(account);
        access(QualityEventType.ACCESS_LOGIN_SUCCEEDED, account.getId(), username, user.role().name());
        return new LoginResponse(jwtService.issue(user), "Bearer", jwtService.ttlSeconds(), user);
    }

    private void access(QualityEventType type, Long userId, String username, String outcome) {
        if (qualityFacts == null) return;
        qualityFacts.publish(QualityFact.builder(type, "ACCESS").requestId(org.slf4j.MDC.get("requestId"))
                .userId(userId).summary(outcome).detail("username", username).detail("outcome", outcome).build());
    }

    static CurrentUser toCurrentUser(UserAccountEntity account) {
        return new CurrentUser(account.getId(), account.getUsername(), account.getDisplayName(),
                RoleCode.valueOf(account.getRoleCode()), account.getRegionCode(), account.getBranchId(),
                account.getManagerId());
    }
}

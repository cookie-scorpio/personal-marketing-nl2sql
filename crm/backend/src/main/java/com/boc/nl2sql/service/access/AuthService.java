package com.boc.nl2sql.service.access;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.boc.nl2sql.controller.access.LoginRequest;
import com.boc.nl2sql.controller.access.LoginResponse;
import com.boc.nl2sql.domain.authorization.AccountStatus;
import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.domain.authorization.RoleCode;
import com.boc.nl2sql.service.authorization.UserRoleGrantService;
import com.boc.nl2sql.domain.authorization.UserAccountEntity;
import com.boc.nl2sql.dao.authorization.UserAccountMapper;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.service.quality.QualityFacts;
import com.boc.nl2sql.domain.quality.QualityEventType;
import com.boc.nl2sql.domain.quality.QualityFact;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 校验本地账号凭据并签发应用自己的 JWT。
 *
 * <p>用户名不存在、密码错误和账号禁用共享同一外部提示，避免认证接口泄露账号有效性；
 * 待审批状态只在密码校验成功后暴露。</p>
 */
@Service
public class AuthService {
    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final PasswordCipher passwordCipher;
    private final UsernamePolicy usernamePolicy;
    private final UserRoleGrantService roleGrants;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private QualityFacts qualityFacts;

    public AuthService(UserAccountMapper userAccountMapper, PasswordEncoder passwordEncoder, JwtService jwtService,
                       PasswordCipher passwordCipher, UsernamePolicy usernamePolicy, UserRoleGrantService roleGrants) {
        this.userAccountMapper = userAccountMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.passwordCipher = passwordCipher;
        this.usernamePolicy = usernamePolicy;
        this.roleGrants = roleGrants;
    }

    /** 解密一次性密码信封、校验账号状态，并在成功后签发访问令牌。 */
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
        CurrentUser user = roleGrants.currentUser(account, null);
        access(QualityEventType.ACCESS_LOGIN_SUCCEEDED, account.getId(), username, user.role().name());
        return new LoginResponse(jwtService.issue(user), "Bearer", jwtService.ttlSeconds(), user);
    }

    /** 根据已登录用户的服务端授权记录重新签发“当前身份”令牌。 */
    public LoginResponse switchIdentity(CurrentUser current, RoleCode target) {
        UserAccountEntity account = userAccountMapper.selectById(current.userId());
        if (account == null || !Boolean.TRUE.equals(account.getEnabled())
                || !AccountStatus.ACTIVE.name().equals(account.getAccountStatus())) {
            throw new BusinessException(401001, "登录状态已失效，请重新登录");
        }
        CurrentUser switched = roleGrants.currentUser(account, target);
        access(QualityEventType.ACCESS_LOGIN_SUCCEEDED, account.getId(), account.getUsername(),
                "identity switched to " + switched.role().name());
        return new LoginResponse(jwtService.issue(switched), "Bearer", jwtService.ttlSeconds(), switched);
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

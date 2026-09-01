package com.boc.nl2sql.access.auth.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.boc.nl2sql.access.auth.api.RegistrationRequest;
import com.boc.nl2sql.access.auth.api.RegistrationResponse;
import com.boc.nl2sql.authorization.domain.AccountStatus;
import com.boc.nl2sql.authorization.infrastructure.UserAccountEntity;
import com.boc.nl2sql.authorization.infrastructure.UserAccountMapper;
import com.boc.nl2sql.common.exception.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 公开注册入口：只产生待审批账号，不允许请求方传入角色和任何数据范围。 */
@Service
public class RegistrationService {
    private final UserAccountMapper accounts;
    private final PasswordEncoder passwordEncoder;
    private final PasswordCipher passwordCipher;
    private final PasswordPolicy passwordPolicy;
    private final UsernamePolicy usernamePolicy;

    public RegistrationService(UserAccountMapper accounts, PasswordEncoder passwordEncoder, PasswordCipher passwordCipher,
                               PasswordPolicy passwordPolicy, UsernamePolicy usernamePolicy) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.passwordCipher = passwordCipher;
        this.passwordPolicy = passwordPolicy;
        this.usernamePolicy = usernamePolicy;
    }

    public RegistrationResponse register(RegistrationRequest request) {
        String username = usernamePolicy.normalizeAndValidate(request.username());
        String displayName = validateDisplayName(request.displayName());
        if (request.password() == null) {
            throw new BusinessException(400013, "请提供加密后的密码");
        }
        String password = passwordCipher.decrypt(request.password().keyId(), request.password().encryptedPassword());
        passwordPolicy.validate(password);

        // 先查询便于返回业务错误；数据库唯一索引仍是并发注册时的最终保护。
        if (accounts.selectCount(Wrappers.<UserAccountEntity>lambdaQuery()
                .eq(UserAccountEntity::getUsername, username)) > 0) {
            throw usernameAlreadyUsed();
        }

        UserAccountEntity account = new UserAccountEntity();
        account.setUsername(username);
        account.setDisplayName(displayName);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setEnabled(false);
        account.setAccountStatus(AccountStatus.PENDING.name());
        // role、region、branch、manager 均由未来的管理员分配；绝不相信公开注册请求中的这些字段。
        try {
            accounts.insert(account);
        } catch (DuplicateKeyException exception) {
            throw usernameAlreadyUsed();
        }
        return new RegistrationResponse(username, displayName, AccountStatus.PENDING.name(),
                "注册申请已提交，账号待审批，暂不可登录");
    }

    private String validateDisplayName(String value) {
        String displayName = value == null ? "" : value.trim();
        if (displayName.length() < 2 || displayName.length() > 64
                || displayName.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(400014, "姓名需为2至64个非控制字符");
        }
        return displayName;
    }

    private BusinessException usernameAlreadyUsed() {
        return new BusinessException(409010, "用户名已被使用，请更换后重试");
    }
}

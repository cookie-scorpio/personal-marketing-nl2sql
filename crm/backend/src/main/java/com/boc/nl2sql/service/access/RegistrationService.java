package com.boc.nl2sql.service.access;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.boc.nl2sql.controller.access.RegistrationRequest;
import com.boc.nl2sql.controller.access.RegistrationResponse;
import com.boc.nl2sql.domain.authorization.AccountStatus;
import com.boc.nl2sql.domain.authorization.UserAccountEntity;
import com.boc.nl2sql.dao.authorization.UserAccountMapper;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.service.quality.QualityFacts;
import com.boc.nl2sql.domain.quality.QualityEventType;
import com.boc.nl2sql.domain.quality.QualityFact;
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
    private final EmployeeNoPolicy employeeNoPolicy;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private QualityFacts qualityFacts;

    public RegistrationService(UserAccountMapper accounts, PasswordEncoder passwordEncoder, PasswordCipher passwordCipher,
                               PasswordPolicy passwordPolicy, UsernamePolicy usernamePolicy, EmployeeNoPolicy employeeNoPolicy) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.passwordCipher = passwordCipher;
        this.passwordPolicy = passwordPolicy;
        this.usernamePolicy = usernamePolicy;
        this.employeeNoPolicy = employeeNoPolicy;
    }

    public RegistrationResponse register(RegistrationRequest request) {
        String username = usernamePolicy.normalizeAndValidate(request.username());
        String employeeNo = employeeNoPolicy.normalizeAndValidate(request.employeeNo());
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
        if (accounts.selectCount(Wrappers.<UserAccountEntity>lambdaQuery()
                .eq(UserAccountEntity::getEmployeeNo, employeeNo)) > 0) {
            throw employeeNoAlreadyUsed();
        }

        UserAccountEntity account = new UserAccountEntity();
        account.setUsername(username);
        account.setEmployeeNo(employeeNo);
        // 注册页不再采集姓名；待授权前用工号生成中性展示名，避免错误显示未经核验的个人信息。
        account.setDisplayName("工号" + employeeNo);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setEnabled(false);
        account.setAccountStatus(AccountStatus.PENDING.name());
        // role、region、branch、manager 均由未来的管理员分配；绝不相信公开注册请求中的这些字段。
        try {
            accounts.insert(account);
        } catch (DuplicateKeyException exception) {
            // 唯一索引同时保护用户名与工号；为注册者提供可修复的统一提示。
            throw new BusinessException(409010, "用户名或工号已被使用，请更换后重试");
        }
        if (qualityFacts != null) qualityFacts.publish(QualityFact.builder(
                        QualityEventType.ACCESS_REGISTRATION_SUBMITTED, "ACCESS")
                .requestId(org.slf4j.MDC.get("requestId")).summary("registration pending")
                .detail("username", username).detail("employee_no", employeeNo)
                .detail("account_status", AccountStatus.PENDING.name()).build());
        return new RegistrationResponse(username, employeeNo, AccountStatus.PENDING.name(),
                "注册申请已提交，账号待审批，暂不可登录");
    }

    private BusinessException usernameAlreadyUsed() {
        return new BusinessException(409010, "用户名已被使用，请更换后重试");
    }

    private BusinessException employeeNoAlreadyUsed() {
        return new BusinessException(409011, "工号已被使用，请核对后重试");
    }
}

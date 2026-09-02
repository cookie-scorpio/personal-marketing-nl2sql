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
        String displayName = normalizeDisplayName(request.displayName());

        /*
         * 提交注册时先做用户名唯一性检查，让普通重复请求得到可修复的明确提示。
         * V1 已建立用户名唯一索引，后面的 DuplicateKeyException 分支继续处理并发注册竞态。
         */
        if (accounts.selectCount(Wrappers.<UserAccountEntity>lambdaQuery()
                .eq(UserAccountEntity::getUsername, username)) > 0) {
            throw usernameAlreadyUsed();
        }
        if (accounts.selectCount(Wrappers.<UserAccountEntity>lambdaQuery()
                .eq(UserAccountEntity::getEmployeeNo, employeeNo)) > 0) {
            throw employeeNoAlreadyUsed();
        }
        if (request.password() == null) {
            throw new BusinessException(400013, "请提供加密后的密码");
        }
        String password = passwordCipher.decrypt(request.password().keyId(), request.password().encryptedPassword());
        passwordPolicy.validate(password);

        UserAccountEntity account = new UserAccountEntity();
        account.setUsername(username);
        account.setEmployeeNo(employeeNo);
        // 姓名由注册者在必填字段中提交，权限管理员审批时直接读取这一账号资料，不再用工号代替姓名。
        account.setDisplayName(displayName);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setEnabled(false);
        account.setAccountStatus(AccountStatus.PENDING.name());
        // role、region、branch、manager 均由未来的管理员分配；绝不相信公开注册请求中的这些字段。
        try {
            accounts.insert(account);
        } catch (DuplicateKeyException exception) {
            /*
             * 预检查与插入之间仍可能有另一个请求抢先写入。数据库唯一索引拒绝后重新判断冲突字段，
             * 保证并发用户名重复也返回与普通重复相同的修改指引，而不是含糊的组合提示。
             */
            if (accounts.selectCount(Wrappers.<UserAccountEntity>lambdaQuery()
                    .eq(UserAccountEntity::getUsername, username)) > 0) throw usernameAlreadyUsed();
            if (accounts.selectCount(Wrappers.<UserAccountEntity>lambdaQuery()
                    .eq(UserAccountEntity::getEmployeeNo, employeeNo)) > 0) throw employeeNoAlreadyUsed();
            throw new BusinessException(409012, "注册信息与已有账号冲突，请核对后重新提交");
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
        return new BusinessException(409010, "用户名已存在，请修改用户名后重新提交");
    }

    private BusinessException employeeNoAlreadyUsed() {
        return new BusinessException(409011, "工号已被使用，请核对后重试");
    }

    /** 姓名是审批页的账号识别信息；服务层再次校验，不能只依赖控制器的 Bean Validation。 */
    private String normalizeDisplayName(String value) {
        String displayName = value == null ? "" : value.strip();
        if (displayName.isEmpty() || displayName.length() > 64
                || displayName.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(400019, "姓名不能为空、不能包含控制字符，且不能超过64个字符");
        }
        return displayName;
    }
}

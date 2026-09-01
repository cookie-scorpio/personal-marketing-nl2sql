package com.boc.nl2sql.access.auth.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.boc.nl2sql.authorization.domain.AccountStatus;
import com.boc.nl2sql.authorization.domain.RoleCode;
import com.boc.nl2sql.authorization.infrastructure.UserAccountEntity;
import com.boc.nl2sql.authorization.infrastructure.UserAccountMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 首次启动时写入三类演示账号；密码进入数据库前统一使用 BCrypt。 */
@Component
public class DemoAccountBootstrap implements ApplicationRunner {
    public static final String DEMO_PASSWORD = "Demo@123";
    private final UserAccountMapper mapper;
    private final PasswordEncoder passwordEncoder;

    public DemoAccountBootstrap(UserAccountMapper mapper, PasswordEncoder passwordEncoder) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        createIfMissing("manager01", "林书言", RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");
        createIfMissing("leader01", "周明远", RoleCode.TEAM_LEAD, "EAST", "B001", null);
        createIfMissing("director01", "陈思睿", RoleCode.ORG_MANAGER, "EAST", null, null);
    }

    private void createIfMissing(String username, String displayName, RoleCode role,
                                 String regionCode, String branchId, String managerId) {
        Long count = mapper.selectCount(Wrappers.<UserAccountEntity>lambdaQuery()
                .eq(UserAccountEntity::getUsername, username));
        if (count > 0) {
            return;
        }
        UserAccountEntity account = new UserAccountEntity();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
        account.setDisplayName(displayName);
        account.setRoleCode(role.name());
        account.setRegionCode(regionCode);
        account.setBranchId(branchId);
        account.setManagerId(managerId);
        account.setEnabled(true);
        account.setAccountStatus(AccountStatus.ACTIVE.name());
        account.setCreatedAt(LocalDateTime.now());
        mapper.insert(account);
    }
}

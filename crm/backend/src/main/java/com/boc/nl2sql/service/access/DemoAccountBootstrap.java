package com.boc.nl2sql.service.access;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.boc.nl2sql.domain.authorization.AccountStatus;
import com.boc.nl2sql.domain.authorization.RoleCode;
import com.boc.nl2sql.service.authorization.UserRoleGrantService;
import com.boc.nl2sql.domain.authorization.UserAccountEntity;
import com.boc.nl2sql.dao.authorization.UserAccountMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 首次启动时写入业务演示账号和只访问质量功能的管理员；密码入库前统一使用 BCrypt。 */
@Component
public class DemoAccountBootstrap implements ApplicationRunner {
    public static final String DEMO_PASSWORD = "Demo@123";
    private final UserAccountMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final UserRoleGrantService roleGrants;

    public DemoAccountBootstrap(UserAccountMapper mapper, PasswordEncoder passwordEncoder, UserRoleGrantService roleGrants) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.roleGrants = roleGrants;
    }

    @Override
    public void run(ApplicationArguments args) {
        // director01 额外拥有审计身份，用于演示“同一账号可切换身份”；默认仍是机构负责人业务身份。
        createIfMissing("manager01", "林书言", "10001", RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");
        createIfMissing("leader01", "周明远", "10002", RoleCode.TEAM_LEAD, "EAST", "B001", null);
        UserAccountEntity director = createIfMissing("director01", "陈思睿", "10003", RoleCode.ORG_MANAGER, "EAST", null, null);
        createIfMissing("quality01", "质量审计员", "10004", RoleCode.QUALITY_ADMIN, null, null, null);
        createIfMissing("admin01", "权限管理员", "10005", RoleCode.PERMISSION_ADMIN, null, null, null);
        if (director != null) {
            roleGrants.seedLegacyGrantIfMissing(director);
            // 演示账号的第二身份通过同一授权表插入，前端只会展示后端实际授予的身份。
            roleGrants.assignAdditionalDemoRole(director.getId(), RoleCode.QUALITY_AUDITOR);
        }
    }

    private UserAccountEntity createIfMissing(String username, String displayName, String employeeNo, RoleCode role,
                                              String regionCode, String branchId, String managerId) {
        UserAccountEntity account = mapper.selectOne(Wrappers.<UserAccountEntity>lambdaQuery()
                .eq(UserAccountEntity::getUsername, username).last("LIMIT 1"));
        if (account == null) {
            account = new UserAccountEntity();
            account.setUsername(username);
            account.setEmployeeNo(employeeNo);
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
        roleGrants.seedLegacyGrantIfMissing(account);
        return account;
    }
}

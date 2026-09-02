package com.boc.nl2sql.service.authorization;

import com.boc.nl2sql.domain.authorization.BusinessDataScopeLevel;
import com.boc.nl2sql.domain.authorization.RoleCode;
import com.boc.nl2sql.domain.authorization.UserAccountEntity;
import com.boc.nl2sql.dao.authorization.UserAccountMapper;
import com.boc.nl2sql.domain.authorization.UserRoleGrantEntity;
import com.boc.nl2sql.dao.authorization.UserRoleGrantMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证多角色账号的默认身份、显式切换和客户经理数据范围均只从授权记录导出。 */
class UserRoleGrantServiceTest {
    @Test
    void derivesOnlyGrantedIdentitiesAndKeepsBusinessScopeOnManagerIdentity() {
        UserAccountMapper accounts = mock(UserAccountMapper.class);
        UserRoleGrantMapper grants = mock(UserRoleGrantMapper.class);
        UserAccountEntity account = new UserAccountEntity();
        account.setId(7L);
        account.setUsername("director01");
        account.setDisplayName("陈思睿");
        account.setEmployeeNo("10003");
        account.setRegionCode("EAST");
        UserRoleGrantEntity business = grant(RoleCode.CUSTOMER_MANAGER, BusinessDataScopeLevel.ORG_MANAGER);
        UserRoleGrantEntity audit = grant(RoleCode.QUALITY_AUDITOR, null);
        when(grants.selectList(any())).thenReturn(List.of(business, audit));
        UserRoleGrantService service = new UserRoleGrantService(accounts, grants);

        var defaultUser = service.currentUser(account, null);
        assertThat(defaultUser.role()).isEqualTo(RoleCode.CUSTOMER_MANAGER);
        assertThat(defaultUser.businessScopeLevel()).isEqualTo(BusinessDataScopeLevel.ORG_MANAGER);
        assertThat(defaultUser.availableRoles()).containsExactly(RoleCode.CUSTOMER_MANAGER, RoleCode.QUALITY_AUDITOR);

        var auditUser = service.currentUser(account, RoleCode.QUALITY_AUDITOR);
        assertThat(auditUser.role()).isEqualTo(RoleCode.QUALITY_AUDITOR);
        assertThat(auditUser.businessScopeLevel()).isNull();
    }

    private UserRoleGrantEntity grant(RoleCode role, BusinessDataScopeLevel level) {
        UserRoleGrantEntity entity = new UserRoleGrantEntity();
        entity.setRoleCode(role.name());
        entity.setBusinessScopeLevel(level == null ? null : level.name());
        entity.setEnabled(true);
        return entity;
    }
}

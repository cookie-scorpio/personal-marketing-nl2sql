package com.boc.nl2sql.service.authorization;

import com.boc.nl2sql.domain.authorization.BusinessDataScopeLevel;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.domain.authorization.CurrentUser;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void rejectsRemovingTheCurrentAdministratorsOwnPermissionAdministratorRoleBeforeWriting() {
        UserAccountMapper accounts = mock(UserAccountMapper.class);
        UserRoleGrantMapper grants = mock(UserRoleGrantMapper.class);
        UserAccountEntity account = new UserAccountEntity();
        account.setId(7L);
        account.setUsername("admin01");
        when(accounts.selectById(7L)).thenReturn(account);
        UserRoleGrantService service = new UserRoleGrantService(accounts, grants);
        CurrentUser administrator = new CurrentUser(7L, "admin01", "权限管理员",
                RoleCode.PERMISSION_ADMIN, null, null, null);

        // 即使调用方绕过禁用复选框直接提交，也必须在删除任何旧授权前失败。
        BusinessException rejected = assertThrows(BusinessException.class, () -> service.assign(7L,
                new UserRoleGrantService.PermissionAssignment(List.of(RoleCode.QUALITY_AUDITOR),
                        null, null, null, null), administrator));

        assertThat(rejected.code()).isEqualTo(403108);
        assertThat(rejected.getMessage()).contains("不能撤销");
        verify(grants, never()).delete(any());
    }

    @Test
    void rejectsDeletingTheCurrentAdministratorBeforeReadingOrDeletingAccountData() {
        UserAccountMapper accounts = mock(UserAccountMapper.class);
        UserRoleGrantMapper grants = mock(UserRoleGrantMapper.class);
        UserRoleGrantService service = new UserRoleGrantService(accounts, grants);
        CurrentUser administrator = new CurrentUser(7L, "admin01", "周管理员",
                RoleCode.PERMISSION_ADMIN, null, null, null);

        // 自删保护必须早于账号查询和角色清理，保证失败请求不会产生任何部分删除。
        BusinessException rejected = assertThrows(BusinessException.class,
                () -> service.deleteAccount(7L, administrator));

        assertThat(rejected.code()).isEqualTo(403109);
        assertThat(rejected.getMessage()).isEqualTo("不能删除当前登录账号");
        verify(grants, never()).delete(any());
    }

    @Test
    void deletesAnotherAccountAndItsRoleGrantsButKeepsHistoricalBusinessDataUntouched() {
        UserAccountMapper accounts = mock(UserAccountMapper.class);
        UserRoleGrantMapper grants = mock(UserRoleGrantMapper.class);
        UserAccountEntity target = new UserAccountEntity();
        target.setId(8L);
        target.setUsername("manager08");
        when(accounts.selectById(8L)).thenReturn(target);
        when(accounts.deleteById(8L)).thenReturn(1);
        UserRoleGrantService service = new UserRoleGrantService(accounts, grants);
        CurrentUser administrator = new CurrentUser(7L, "admin01", "周管理员",
                RoleCode.PERMISSION_ADMIN, null, null, null);

        service.deleteAccount(8L, administrator);

        // 服务只操作账号与角色表；会话、任务和审计表没有被注入，也不会随账号删除被清理。
        verify(grants).delete(any());
        verify(accounts).deleteById(8L);
    }

    @Test
    void listsRegisteredNamesInEmployeeNumberOrderAndPutsMissingNumbersLast() {
        UserAccountMapper accounts = mock(UserAccountMapper.class);
        UserRoleGrantMapper grants = mock(UserRoleGrantMapper.class);
        UserAccountEntity later = account(11L, "10011", "jingtain123", "景天");
        UserAccountEntity earlier = account(1L, "10001", "manager01", "林书言");
        UserAccountEntity legacy = account(12L, null, "legacy01", "历史用户");
        when(accounts.selectList(any())).thenReturn(new java.util.ArrayList<>(List.of(later, legacy, earlier)));
        when(grants.selectList(any())).thenReturn(List.of());
        UserRoleGrantService service = new UserRoleGrantService(accounts, grants);
        CurrentUser administrator = new CurrentUser(7L, "admin01", "周管理员",
                RoleCode.PERMISSION_ADMIN, null, null, null);

        var result = service.listAccounts(administrator);

        assertThat(result).extracting(UserRoleGrantService.AccountOverview::employeeNo)
                .containsExactly("10001", "10011", null);
        // 姓名必须直接来自注册保存的 display_name，不能再退化为“工号XXXXX”。
        assertThat(result).extracting(UserRoleGrantService.AccountOverview::displayName)
                .containsExactly("林书言", "景天", "历史用户");
    }

    private UserRoleGrantEntity grant(RoleCode role, BusinessDataScopeLevel level) {
        UserRoleGrantEntity entity = new UserRoleGrantEntity();
        entity.setRoleCode(role.name());
        entity.setBusinessScopeLevel(level == null ? null : level.name());
        entity.setEnabled(true);
        return entity;
    }

    private UserAccountEntity account(Long id, String employeeNo, String username, String displayName) {
        UserAccountEntity entity = new UserAccountEntity();
        entity.setId(id);
        entity.setEmployeeNo(employeeNo);
        entity.setUsername(username);
        entity.setDisplayName(displayName);
        entity.setAccountStatus("PENDING");
        entity.setEnabled(false);
        return entity;
    }
}

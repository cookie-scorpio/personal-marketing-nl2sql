package com.boc.nl2sql.access.permission.api;

import com.boc.nl2sql.authorization.domain.BusinessDataScopeLevel;
import com.boc.nl2sql.authorization.domain.RoleCode;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 权限管理员覆盖一个账号的角色集合和客户经理数据范围。 */
public record PermissionAssignmentRequest(
        @NotEmpty List<RoleCode> roles,
        BusinessDataScopeLevel businessScopeLevel,
        String regionCode,
        String branchId,
        String managerId
) {
}

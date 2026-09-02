package com.boc.nl2sql.authorization.domain;

import java.io.Serializable;
import java.util.List;

/**
 * 认证后的可信用户摘要。核心业务只接收此对象，不接收前端传入的 userId。
 *
 * <p>{@code role} 表示本次令牌已激活的身份，{@code availableRoles} 仅用于展示可切换项；
 * 前端提交的切换请求仍会由服务端按授权表再次校验。</p>
 */
public record CurrentUser(
        Long userId,
        String username,
        String displayName,
        RoleCode role,
        BusinessDataScopeLevel businessScopeLevel,
        String regionCode,
        String branchId,
        String managerId,
        List<RoleCode> availableRoles,
        String employeeNo
) implements Serializable {
    public CurrentUser {
        role = role == null ? null : role.normalized();
        availableRoles = availableRoles == null ? List.of() : availableRoles.stream()
                .filter(value -> value != null && value.normalized().assignable())
                .map(RoleCode::normalized).distinct().toList();
        if (role != null && !availableRoles.contains(role)) {
            availableRoles = java.util.stream.Stream.concat(availableRoles.stream(), java.util.stream.Stream.of(role))
                    .distinct().toList();
        }
        if (businessScopeLevel == null && role != null && role.businessIdentity()) {
            businessScopeLevel = BusinessDataScopeLevel.CUSTOMER_MANAGER;
        }
    }

    /** 供历史代码与已签发旧令牌使用的兼容构造器。 */
    public CurrentUser(Long userId, String username, String displayName, RoleCode role,
                       String regionCode, String branchId, String managerId) {
        this(userId, username, displayName, role, BusinessDataScopeLevel.fromLegacyRole(role),
                regionCode, branchId, managerId,
                role == null ? List.of() : List.of(role.normalized()), null);
    }
}

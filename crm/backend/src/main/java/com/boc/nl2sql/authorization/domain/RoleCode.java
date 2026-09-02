package com.boc.nl2sql.authorization.domain;

/**
 * 系统认可的身份类别。
 *
 * <p>{@code TEAM_LEAD}、{@code ORG_MANAGER} 和 {@code QUALITY_ADMIN} 仅用于兼容历史令牌和
 * 已有测试；新授权记录只会写入三种可分配身份：客户经理、质量审计员和权限管理员。客户经理
 * 的三级业务数据范围由 {@link BusinessDataScopeLevel} 单独表达，避免把岗位身份和数据范围
 * 混成一个角色。</p>
 */
public enum RoleCode {
    CUSTOMER_MANAGER,
    TEAM_LEAD,
    ORG_MANAGER,
    QUALITY_ADMIN,
    QUALITY_AUDITOR,
    PERMISSION_ADMIN;

    /** 新增授权记录允许分配的三类身份。 */
    public boolean assignable() {
        return this == CUSTOMER_MANAGER || this == QUALITY_AUDITOR || this == PERMISSION_ADMIN;
    }

    /** 将旧角色规范化为新的身份类别，确保升级前的令牌和会话仍可读取。 */
    public RoleCode normalized() {
        return switch (this) {
            case TEAM_LEAD, ORG_MANAGER -> CUSTOMER_MANAGER;
            case QUALITY_ADMIN -> QUALITY_AUDITOR;
            default -> this;
        };
    }

    public boolean businessIdentity() {
        return normalized() == CUSTOMER_MANAGER;
    }

    /**
     * 智能问数允许客户经理和质量审计员进入。权限管理员仍只负责授权管理，不能因为已登录
     * 就访问业务数据；具体行级范围继续由 DataScopePolicy 统一追加和校验。
     */
    public boolean queryIdentity() {
        RoleCode current = normalized();
        return current == CUSTOMER_MANAGER || current == QUALITY_AUDITOR;
    }
}

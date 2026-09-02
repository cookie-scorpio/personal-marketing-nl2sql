package com.boc.nl2sql.domain.authorization;

/** 客户经理身份对应的三级业务数据范围；质量与权限身份不使用该字段。 */
public enum BusinessDataScopeLevel {
    CUSTOMER_MANAGER,
    TEAM_LEAD,
    ORG_MANAGER;

    /** 为升级前的单角色账号提供稳定的默认范围。 */
    public static BusinessDataScopeLevel fromLegacyRole(RoleCode role) {
        if (role == null) return null;
        return switch (role) {
            case CUSTOMER_MANAGER -> CUSTOMER_MANAGER;
            case TEAM_LEAD -> TEAM_LEAD;
            case ORG_MANAGER -> ORG_MANAGER;
            default -> null;
        };
    }
}

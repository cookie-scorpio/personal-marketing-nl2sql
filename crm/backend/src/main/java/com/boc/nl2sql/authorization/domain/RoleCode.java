package com.boc.nl2sql.authorization.domain;

/** 系统认可的授权角色；具体数据范围仍由用户的机构和客户经理属性共同限定。 */
public enum RoleCode {
    CUSTOMER_MANAGER,
    TEAM_LEAD,
    ORG_MANAGER,
    QUALITY_ADMIN
}

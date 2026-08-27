package com.boc.nl2sql.authorization.domain;

import java.io.Serializable;

/** 认证后的可信用户摘要。核心业务只接收此对象，不接收前端传入的 userId。 */
public record CurrentUser(
        Long userId,
        String username,
        String displayName,
        RoleCode role,
        String regionCode,
        String branchId,
        String managerId
) implements Serializable {
}

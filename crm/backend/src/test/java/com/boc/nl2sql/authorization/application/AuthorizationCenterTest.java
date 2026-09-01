package com.boc.nl2sql.authorization.application;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.authorization.domain.RoleCode;
import com.boc.nl2sql.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthorizationCenterTest {
    private final AuthorizationCenter authorization = new AuthorizationCenter(new DataScopePolicy());
    private final CurrentUser manager = new CurrentUser(1L, "manager01", "经理", RoleCode.CUSTOMER_MANAGER,
            "EAST", "B001", "M0001");

    @Test
    void centralizesOwnerAndRowScopeChecks() {
        assertDoesNotThrow(() -> authorization.requireOwner(manager, 1L, "资源不存在"));
        assertThrows(BusinessException.class, () -> authorization.requireOwner(manager, 2L, "资源不存在"));
        assertDoesNotThrow(() -> authorization.customerScope(manager));
    }
}

package com.boc.nl2sql.execution;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.authorization.domain.RoleCode;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.execution.application.GeneratedSqlScopeValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedSqlScopeValidatorTest {
    private final GeneratedSqlScopeValidator validator = new GeneratedSqlScopeValidator();
    private final CurrentUser manager = new CurrentUser(1L, "manager01", "演示经理",
            RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");

    @Test
    void checksServerAssignedScopeInModelSql() {
        assertThatCode(() -> validator.validate(
                "SELECT age_band_code FROM dim_customer c WHERE c.manager_id = 'M0001' LIMIT 100", manager))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(
                "SELECT age_band_code FROM dim_customer c WHERE c.branch_id = 'B001' LIMIT 100", manager))
                .isInstanceOf(BusinessException.class);
    }
}

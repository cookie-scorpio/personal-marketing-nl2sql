package com.boc.nl2sql.execution;

import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.execution.application.SqlSafetyValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlSafetyValidatorTest {
    private final SqlSafetyValidator validator = new SqlSafetyValidator();

    @Test
    void acceptsWhitelistedLimitedSelect() {
        assertThatCode(() -> validator.validate("SELECT customer_id FROM dim_customer LIMIT 100"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDmlAndUnknownTables() {
        assertThatThrownBy(() -> validator.validate("DELETE FROM dim_customer LIMIT 1"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validate("SELECT * FROM mysql.user LIMIT 10"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsSelectWithoutLimit() {
        assertThatThrownBy(() -> validator.validate("SELECT customer_id FROM dim_customer"))
                .isInstanceOf(BusinessException.class);
    }
}

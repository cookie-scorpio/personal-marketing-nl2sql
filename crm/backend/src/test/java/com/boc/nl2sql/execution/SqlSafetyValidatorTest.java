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

    @Test
    void rejectsWildcardAndDangerousFunctions() {
        assertThatThrownBy(() -> validator.validate("SELECT * FROM dim_customer LIMIT 10"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.validate("SELECT sleep(3) FROM dim_customer LIMIT 1"))
                .isInstanceOf(BusinessException.class);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
        "WITH a AS (SELECT c.customer_id,c.total_asset_amount FROM dim_customer c WHERE c.region_code='EAST') SELECT a.customer_id,ROW_NUMBER() OVER(ORDER BY a.total_asset_amount DESC) AS ranking FROM a LIMIT 10",
        "SELECT c.customer_id FROM dim_customer c WHERE EXISTS(SELECT t.transaction_id FROM fct_transaction t WHERE t.customer_id=c.customer_id) LIMIT 10",
        "SELECT customer_id FROM dim_customer UNION ALL SELECT customer_id FROM dim_customer LIMIT 10",
        "SELECT q.customer_count FROM (SELECT COUNT(*) AS customer_count FROM dim_customer) q LIMIT 10"
    })
    void acceptsComplexReadOnlyMysqlShapes(String sql){assertThatCode(()->validator.validate(sql)).doesNotThrowAnyException();}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
        "SELECT c.customer_name AS harmless FROM dim_customer c LIMIT 10",
        "SELECT CONCAT(c.customer_name,'x') AS label FROM dim_customer c LIMIT 10",
        "SELECT customer_id FROM dim_customer WHERE customer_id IN(SELECT user_id FROM sys_user_account) LIMIT 10",
        "SELECT c.customer_id AS selected_id FROM dim_customer c JOIN dim_customer d ON c.customer_id=d.customer_id ORDER BY customer_id LIMIT 10",
        "SELECT c.unknown_column FROM dim_customer c LIMIT 10",
        "SELECT c.customer_id FROM dim_customer c WINDOW w AS(ORDER BY SLEEP(3)) LIMIT 10",
        "SELECT SQL_CALC_FOUND_ROWS c.customer_id FROM dim_customer c LIMIT 10",
        "SELECT DISTINCT ON (SLEEP(3)) c.customer_id FROM dim_customer c LIMIT 10",
        "SELECT c.customer_id FROM dim_customer c WHERE c.customer_id IN(SELECT customer_id FROM dim_customer LIMIT 10)",
        "SELECT customer_id FROM dim_customer LIMIT 1000",
        "SELECT customer_id FROM dim_customer LIMIT 10 FOR UPDATE",
        "WITH RECURSIVE a AS(SELECT customer_id FROM dim_customer) SELECT a.customer_id FROM a LIMIT 10",
        "SELECT (SELECT load_file('/etc/passwd')) AS x FROM dim_customer LIMIT 10"
    })
    void rejectsUnsafeNestedOrAmbiguousSql(String sql){assertThatThrownBy(()->validator.validate(sql)).isInstanceOf(BusinessException.class);}
}

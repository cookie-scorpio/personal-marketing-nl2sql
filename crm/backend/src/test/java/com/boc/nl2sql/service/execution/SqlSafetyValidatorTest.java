package com.boc.nl2sql.service.execution;

import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.service.execution.SqlSafetyValidator;
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
    void reportsPreciseErrorCodesPerRejectionLayer() {
        // 表白名单 403102
        assertThatThrownBy(() -> validator.validate("SELECT customer_id FROM mysql.user LIMIT 10"))
                .isInstanceOfSatisfying(BusinessException.class, e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(403102));
        // 字段白名单 422104
        assertThatThrownBy(() -> validator.validate("SELECT c.unknown_column FROM dim_customer c LIMIT 10"))
                .isInstanceOfSatisfying(BusinessException.class, e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(422104));
        // 分页上限 422102
        assertThatThrownBy(() -> validator.validate("SELECT customer_id FROM dim_customer LIMIT 1000"))
                .isInstanceOfSatisfying(BusinessException.class, e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(422102));
        // 结构与白名单 422101
        assertThatThrownBy(() -> validator.validate("SELECT * FROM dim_customer LIMIT 10"))
                .isInstanceOfSatisfying(BusinessException.class, e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(422101));
    }

    @Test
    void acceptsCastWhitelistAndOffsetPagination() {
        assertThatCode(() -> validator.validate(
                "SELECT CAST(c.total_asset_amount AS CHAR) AS asset_text FROM dim_customer c LIMIT 10"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(
                "SELECT CAST(c.total_asset_amount AS BINARY) AS asset_bytes FROM dim_customer c LIMIT 10"))
                .isInstanceOf(BusinessException.class);
        assertThatCode(() -> validator.validate("SELECT customer_id FROM dim_customer LIMIT 10 OFFSET 5"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsSelectWithoutLimitBecauseExecutionAddsPagination() {
        assertThatCode(() -> validator.validate("SELECT customer_id FROM dim_customer"))
                .doesNotThrowAnyException();
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
        "SELECT q.customer_count FROM (SELECT COUNT(*) AS customer_count FROM dim_customer) q LIMIT 10",
        "SELECT c.age_band_code,SUM(CASE WHEN c.total_asset_amount BETWEEN 1000000 AND 5000000 THEN 1 ELSE 0 END) AS customer_count FROM dim_customer c WHERE c.open_date BETWEEN DATE('2025-01-01') AND DATE('2026-08-28') GROUP BY c.age_band_code HAVING COUNT(c.customer_id)>0 ORDER BY customer_count DESC LIMIT 100",
        "SELECT c.customer_id,DATE_FORMAT(c.snapshot_date,'%Y-%m') AS snapshot_month,ROW_NUMBER() OVER(PARTITION BY c.branch_id ORDER BY c.total_asset_amount DESC) AS ranking FROM dim_customer c WHERE c.customer_level_code IN('GOLD','PLATINUM') LIMIT 100",
        "WITH scoped AS(SELECT c.customer_id,c.age_band_code FROM dim_customer c WHERE c.manager_id='M0001') SELECT s.age_band_code,COUNT(DISTINCT s.customer_id) AS customer_count FROM scoped s GROUP BY s.age_band_code UNION ALL SELECT c.customer_level_code,COUNT(c.customer_id) AS customer_count FROM dim_customer c WHERE c.manager_id='M0001' GROUP BY c.customer_level_code LIMIT 100",
        "SELECT c.customer_name AS harmless FROM dim_customer c LIMIT 10",
        "SELECT CONCAT(c.customer_name,'x') AS label FROM dim_customer c LIMIT 10"
    })
    void acceptsComplexReadOnlyMysqlShapes(String sql){assertThatCode(()->validator.validate(sql)).doesNotThrowAnyException();}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
        "SELECT customer_id FROM dim_customer WHERE customer_id IN(SELECT user_id FROM sys_user_account) LIMIT 10",
        "SELECT c.customer_id AS selected_id FROM dim_customer c JOIN dim_customer d ON c.customer_id=d.customer_id ORDER BY customer_id LIMIT 10",
        "SELECT c.unknown_column FROM dim_customer c LIMIT 10",
        "SELECT c.customer_id FROM dim_customer c WINDOW w AS(ORDER BY SLEEP(3)) LIMIT 10",
        "SELECT SQL_CALC_FOUND_ROWS c.customer_id FROM dim_customer c LIMIT 10",
        "SELECT DISTINCT ON (SLEEP(3)) c.customer_id FROM dim_customer c LIMIT 10",
        "SELECT customer_id FROM dim_customer LIMIT 1000",
        "SELECT customer_id FROM dim_customer LIMIT 10 FOR UPDATE",
        "WITH RECURSIVE a AS(SELECT customer_id FROM dim_customer) SELECT a.customer_id FROM a LIMIT 10",
        "SELECT (SELECT load_file('/etc/passwd')) AS x FROM dim_customer LIMIT 10"
    })
    void rejectsUnsafeNestedOrAmbiguousSql(String sql){assertThatThrownBy(()->validator.validate(sql)).isInstanceOf(BusinessException.class);}
}

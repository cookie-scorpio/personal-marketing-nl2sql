package com.boc.nl2sql.service.execution;

import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.domain.authorization.RoleCode;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.service.execution.GeneratedSqlScopeValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedSqlScopeValidatorTest {
    private final GeneratedSqlScopeValidator validator = new GeneratedSqlScopeValidator();
    private final CurrentUser manager = new CurrentUser(1L, "manager01", "演示经理",
            RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");
    private final CurrentUser auditor = new CurrentUser(9L, "quality01", "质量审计员",
            RoleCode.QUALITY_AUDITOR, null, null, null);

    @Test
    void checksServerAssignedScopeInModelSql() {
        assertThatCode(() -> validator.validate(
                "SELECT age_band_code FROM dim_customer c WHERE c.manager_id = 'M0001' LIMIT 100", manager))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(
                "SELECT age_band_code FROM dim_customer c WHERE c.branch_id = 'B001' LIMIT 100", manager))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void qualityAuditorMustKeepTheAllActiveCustomerPredicate() {
        assertThatCode(() -> validator.validate(
                "SELECT c.customer_id FROM dim_customer c WHERE c.status_code = 'ACTIVE' LIMIT 100", auditor))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(
                "SELECT c.customer_id FROM dim_customer c LIMIT 100", auditor))
                .isInstanceOf(BusinessException.class);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
        "SELECT c.customer_id FROM dim_customer c WHERE c.manager_id='M0001' OR 1=1 LIMIT 10",
        "SELECT c.customer_id FROM dim_customer c WHERE c.customer_id='M0001' LIMIT 10",
        "SELECT c.customer_id FROM dim_customer c WHERE EXISTS(SELECT d.customer_id FROM dim_customer d WHERE d.manager_id='M0001') LIMIT 10",
        "SELECT c.customer_id FROM dim_customer c WHERE c.manager_id='M0001' UNION ALL SELECT d.customer_id FROM dim_customer d LIMIT 10",
        "WITH x AS(SELECT customer_id FROM dim_customer) SELECT x.customer_id FROM x LIMIT 10",
        "SELECT c.customer_id FROM dim_customer c LEFT JOIN fct_transaction t ON c.manager_id='M0001' AND c.customer_id=t.customer_id LIMIT 10",
        "SELECT (SELECT SUM(d.total_asset_amount) FROM dim_customer d) AS amount FROM dim_customer c WHERE c.manager_id='M0001' LIMIT 10"
    })
    void everyPhysicalSourceNeedsEffectiveScope(String sql){assertThatThrownBy(()->validator.validate(sql,manager)).isInstanceOf(BusinessException.class);}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
        "WITH x AS(SELECT c.customer_id FROM dim_customer c WHERE c.manager_id='M0001') SELECT x.customer_id FROM x LIMIT 10",
        "SELECT c.customer_id FROM dim_customer c WHERE c.manager_id='M0001' AND EXISTS(SELECT t.transaction_id FROM fct_transaction t WHERE t.customer_id=c.customer_id) LIMIT 10",
        "SELECT c.customer_id,t.amount_cny FROM dim_customer c LEFT JOIN fct_transaction t ON c.customer_id=t.customer_id WHERE c.manager_id='M0001' LIMIT 10",
        "SELECT c.customer_id FROM dim_customer c WHERE c.manager_id='M0001' UNION ALL SELECT d.customer_id FROM dim_customer d WHERE d.manager_id='M0001' LIMIT 10",
        "WITH scoped AS(SELECT c.customer_id,c.age_band_code FROM dim_customer c WHERE c.manager_id='M0001') SELECT s.age_band_code,SUM(t.amount_cny) AS amount_cny FROM scoped s JOIN fct_transaction t ON t.customer_id=s.customer_id JOIN dim_customer c ON c.customer_id=t.customer_id WHERE c.manager_id='M0001' AND t.transaction_date BETWEEN DATE('2026-01-01') AND DATE('2026-08-28') GROUP BY s.age_band_code HAVING SUM(t.amount_cny)>0 LIMIT 100",
        "SELECT c.customer_id,(SELECT MAX(t.amount_cny) FROM fct_transaction t WHERE t.customer_id=c.customer_id) AS max_amount FROM dim_customer c WHERE c.manager_id='M0001' AND c.customer_level_code IN('GOLD','PLATINUM') LIMIT 100"
    })
    void supportsScopedCteCorrelatedSubqueryAndLeftJoin(String sql){assertThatCode(()->validator.validate(sql,manager)).doesNotThrowAnyException();}
    @Test void enforcesResolvedCustomerEvenWhenSqlHasAccountScope(){
        assertThatThrownBy(()->validator.validateCustomer("SELECT c.customer_id FROM dim_customer c WHERE c.manager_id='M0001' LIMIT 10",java.util.Map.of(),"C00000001")).isInstanceOf(BusinessException.class);
        assertThatCode(()->validator.validateCustomer("SELECT c.customer_id FROM dim_customer c WHERE c.customer_id=:resolvedCustomerId LIMIT 10",java.util.Map.of("resolvedCustomerId","C00000001"),"C00000001")).doesNotThrowAnyException();
    }
}

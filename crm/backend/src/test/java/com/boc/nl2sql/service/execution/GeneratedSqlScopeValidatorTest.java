package com.boc.nl2sql.service.execution;

import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.domain.authorization.RoleCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 授权证明的分层测试：账号模式 → 客户模式 → 批量名单模式 → 混合模式。
 * 只读安全闸门（词法/结构/白名单）见 {@link SqlSafetyValidatorTest}。
 * 每种模式对应 proveAccountScope / proveCustomerBinding 两条独立证明链的调度组合。
 */
@DisplayName("GeneratedSqlScopeValidator：授权证明")
class GeneratedSqlScopeValidatorTest {
    private final GeneratedSqlScopeValidator validator = new GeneratedSqlScopeValidator();
    private final CurrentUser manager = new CurrentUser(1L, "manager01", "演示经理",
            RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");
    private final CurrentUser lead = new CurrentUser(2L, "lead01", "演示主管",
            RoleCode.TEAM_LEAD, "EAST", "B001", null);
    private final CurrentUser orgManager = new CurrentUser(3L, "orgmgr01", "演示机构长",
            RoleCode.ORG_MANAGER, "EAST", null, null);
    private final CurrentUser auditor = new CurrentUser(9L, "quality01", "质量审计员",
            RoleCode.QUALITY_AUDITOR, null, null, null);

    private void assertScopeRejected(String sql, CurrentUser user, int code) {
        assertThatThrownBy(() -> validator.validate(sql, user))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(code));
    }

    @Nested
    @DisplayName("账号模式：每个数据源必须可证明被账号数据范围约束")
    class AccountScopeMode {
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
        void scopeColumnIsBoundToRole() {
            // 各角色的范围列与范围值出自同一个 DataScopePolicy 映射：错列/错值/错角色组合全部拒绝
            assertThatCode(() -> validator.validate(
                    "SELECT c.customer_id FROM dim_customer c WHERE c.branch_id = 'B001' LIMIT 10", lead))
                    .doesNotThrowAnyException();
            assertThatCode(() -> validator.validate(
                    "SELECT c.customer_id FROM dim_customer c WHERE c.region_code = 'EAST' LIMIT 10", orgManager))
                    .doesNotThrowAnyException();
            assertScopeRejected("SELECT c.customer_id FROM dim_customer c WHERE c.manager_id = 'M0001' LIMIT 10", lead, 403104);
            assertScopeRejected("SELECT c.customer_id FROM dim_customer c WHERE c.branch_id = 'B001' LIMIT 10", orgManager, 403104);
            assertScopeRejected("SELECT c.customer_id FROM dim_customer c WHERE c.region_code = 'EAST' LIMIT 10", manager, 403104);
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

        @Test
        void auditorOrGivesNoGuarantee() {
            // OR 让 status_code 条件失去"对全部返回行成立"的资格
            assertScopeRejected("SELECT c.customer_id FROM dim_customer c "
                    + "WHERE c.status_code = 'ACTIVE' OR 1 = 1 LIMIT 10", auditor, 403104);
        }

        @ParameterizedTest(name = "拒绝：每个物理数据源都必须有生效范围条件——{0}")
        @ValueSource(strings = {
                // OR 分支不构成保证
                "SELECT c.customer_id FROM dim_customer c WHERE c.manager_id='M0001' OR 1=1 LIMIT 10",
                // 值对了列不对（M0001 是工号，不是 customer_id）
                "SELECT c.customer_id FROM dim_customer c WHERE c.customer_id='M0001' LIMIT 10",
                // 约束落在子查询里，外层仍然无约束
                "SELECT c.customer_id FROM dim_customer c WHERE EXISTS(SELECT d.customer_id FROM dim_customer d WHERE d.manager_id='M0001') LIMIT 10",
                // UNION 的第二个分支没有约束，不能搭第一个分支的便车
                "SELECT c.customer_id FROM dim_customer c WHERE c.manager_id='M0001' UNION ALL SELECT d.customer_id FROM dim_customer d LIMIT 10",
                // CTE 内部没有范围条件
                "WITH x AS(SELECT customer_id FROM dim_customer) SELECT x.customer_id FROM x LIMIT 10",
                // 范围条件写在 LEFT JOIN 的 ON 里，约束不到左表
                "SELECT c.customer_id FROM dim_customer c LEFT JOIN fct_transaction t ON c.manager_id='M0001' AND c.customer_id=t.customer_id LIMIT 10",
                // 无约束的聚合子查询（全行社客户总数）
                "SELECT c.customer_id, (SELECT COUNT(*) FROM dim_customer x) AS total FROM dim_customer c WHERE c.manager_id='M0001' LIMIT 10",
                // 两个特定客户各一个标量子查询：无权编号的子查询独立被拒
                "SELECT (SELECT SUM(t.amount_cny) FROM fct_transaction t WHERE t.customer_id='C00000001') AS mine, "
                        + "(SELECT SUM(t.amount_cny) FROM fct_transaction t WHERE t.customer_id='C00000009') AS others "
                        + "FROM dim_customer c WHERE c.manager_id='M0001' LIMIT 10",
                // IN 两个人（账号模式没有"确认名单"可核对，IN 不构成证明）
                "SELECT (SELECT SUM(t.amount_cny) FROM fct_transaction t "
                        + "WHERE t.customer_id IN ('C00000001','C00000009')) AS s "
                        + "FROM dim_customer c WHERE c.manager_id='M0001' LIMIT 10",
                // OR 两个人
                "SELECT (SELECT SUM(t.amount_cny) FROM fct_transaction t "
                        + "WHERE t.customer_id='C00000001' OR t.customer_id='C00000009') AS s "
                        + "FROM dim_customer c WHERE c.manager_id='M0001' LIMIT 10",
                // 差值追踪攻击：全行社总数 - 我名下总数
                "SELECT (SELECT COUNT(*) FROM dim_customer) - "
                        + "(SELECT COUNT(*) FROM dim_customer WHERE manager_id='M0001') AS others LIMIT 1",
                // 无 WHERE：收集阶段空转
                "SELECT c.customer_id FROM dim_customer c LIMIT 10"
        })
        void everyPhysicalSourceNeedsEffectiveScope(String sql) {
            assertScopeRejected(sql, manager, 403104);
        }

        @ParameterizedTest(name = "放行：受控 CTE / 关联子查询 / LEFT JOIN / 复合约束——{0}")
        @ValueSource(strings = {
                "WITH x AS(SELECT c.customer_id FROM dim_customer c WHERE c.manager_id='M0001') SELECT x.customer_id FROM x LIMIT 10",
                "SELECT c.customer_id FROM dim_customer c WHERE c.manager_id='M0001' AND EXISTS(SELECT t.transaction_id FROM fct_transaction t WHERE t.customer_id=c.customer_id) LIMIT 10",
                "SELECT c.customer_id,t.amount_cny FROM dim_customer c LEFT JOIN fct_transaction t ON c.customer_id=t.customer_id WHERE c.manager_id='M0001' LIMIT 10",
                "SELECT c.customer_id FROM dim_customer c WHERE c.manager_id='M0001' UNION ALL SELECT d.customer_id FROM dim_customer d WHERE d.manager_id='M0001' LIMIT 10",
                "WITH scoped AS(SELECT c.customer_id,c.age_band_code FROM dim_customer c WHERE c.manager_id='M0001') SELECT s.age_band_code,SUM(t.amount_cny) AS amount_cny FROM scoped s JOIN fct_transaction t ON t.customer_id=s.customer_id JOIN dim_customer c ON c.customer_id=t.customer_id WHERE c.manager_id='M0001' AND t.transaction_date BETWEEN DATE('2026-01-01') AND DATE('2026-08-28') GROUP BY s.age_band_code HAVING SUM(t.amount_cny)>0 LIMIT 100",
                "SELECT c.customer_id,(SELECT MAX(t.amount_cny) FROM fct_transaction t WHERE t.customer_id=c.customer_id) AS max_amount FROM dim_customer c WHERE c.manager_id='M0001' AND c.customer_level_code IN('GOLD','PLATINUM') LIMIT 100"
        })
        void supportsScopedCteCorrelatedSubqueryAndLeftJoin(String sql) {
            assertThatCode(() -> validator.validate(sql, manager)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "放行：括号是纯语法糖——{0}")
        @ValueSource(strings = {
                "SELECT c.customer_id FROM dim_customer c WHERE (c.manager_id='M0001') LIMIT 10",
                "SELECT c.customer_id FROM dim_customer c WHERE ((c.manager_id='M0001')) LIMIT 10",
                "SELECT c.customer_id FROM dim_customer c WHERE ((c.manager_id='M0001') AND c.age>18) LIMIT 10",
                "SELECT c.customer_id FROM dim_customer c LEFT JOIN fct_transaction t ON (t.customer_id=c.customer_id) WHERE (c.manager_id='M0001') LIMIT 10"
        })
        void treatsParenthesesAsTransparentGrouping(String sql) {
            assertThatCode(() -> validator.validate(sql, manager)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "拒绝：括号内的 OR 仍无保证——{0}")
        @ValueSource(strings = {
                "SELECT c.customer_id FROM dim_customer c WHERE (c.manager_id='M0001' OR c.branch_id='B001') LIMIT 10"
        })
        void parenthesizedOrStillGivesNoGuarantee(String sql) {
            assertScopeRejected(sql, manager, 403104);
        }

        @Test
        void rejectsValueCaseSpoofing() {
            // 取值事实是大小写敏感的：'m0001' 连不通种子 'M0001'
            assertScopeRejected("SELECT c.customer_id FROM dim_customer c WHERE c.manager_id = 'm0001' LIMIT 10", manager, 403104);
        }

        @Test
        void rejectsFunctionOrCastSideEquality() {
            // 函数/CAST 的取值无法静态核对——即使语义上等价于范围值，也不构成授权事实（宁可误拒）
            assertScopeRejected("SELECT c.customer_id FROM dim_customer c "
                    + "WHERE c.manager_id = CONCAT('M','0001') LIMIT 10", manager, 403104);
            assertScopeRejected("SELECT c.customer_id FROM dim_customer c "
                    + "WHERE c.manager_id = CAST('M0001' AS CHAR) LIMIT 10", manager, 403104);
        }

        @Test
        void rejectsAggregateSmugglingAcrossTables() {
            // 两跳链：dim_customer → 持仓 → 交易，全部经 customer_id 连通才获证
            assertThatCode(() -> validator.validate(
                    "SELECT h.product_name, t.amount_cny FROM dim_customer c "
                            + "JOIN fct_product_holding h ON h.customer_id = c.customer_id "
                            + "JOIN fct_transaction t ON t.customer_id = h.customer_id "
                            + "WHERE c.manager_id='M0001' LIMIT 10", manager))
                    .doesNotThrowAnyException();
            // 不等值连接（<）不构成等值事实，两张表都无法获证
            assertScopeRejected("SELECT d.customer_id FROM dim_customer c "
                    + "JOIN dim_customer d ON d.customer_id < c.customer_id "
                    + "WHERE c.manager_id='M0001' LIMIT 10", manager, 403104);
            // ON 里的 OR 让连接条件整体失去保证
            assertScopeRejected("SELECT d.customer_id FROM dim_customer c "
                    + "JOIN dim_customer d ON d.customer_id = c.customer_id OR d.branch_id = c.branch_id "
                    + "WHERE c.manager_id='M0001' LIMIT 10", manager, 403104);
        }

        @Test
        void acceptsSelfJoinWithSoundPropagation() {
            // 同表双别名：按 customer_id 等值连接，约束沿边传播到第二个别名
            assertThatCode(() -> validator.validate(
                    "SELECT a.customer_id FROM dim_customer a JOIN dim_customer b "
                            + "ON b.customer_id = a.customer_id WHERE a.manager_id='M0001' LIMIT 10", manager))
                    .doesNotThrowAnyException();
            // 按 manager_id 等值连接同理（同经理 = 同范围）
            assertThatCode(() -> validator.validate(
                    "SELECT a.customer_id FROM dim_customer a JOIN dim_customer b "
                            + "ON b.manager_id = a.manager_id WHERE a.manager_id='M0001' LIMIT 10", manager))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsReverseDirectionPropagation() {
            // 语义上安全（返回的就是 M0001 名下客户），但事实只能从外层流向内层——保守拒绝 + JOIN 改写
            assertScopeRejected("SELECT d.customer_id FROM dim_customer d "
                    + "WHERE EXISTS (SELECT 1 FROM dim_customer x "
                    + "WHERE x.manager_id='M0001' AND x.customer_id=d.customer_id) LIMIT 10", manager, 403104);
        }

        @ParameterizedTest(name = "放行：真实业务查询——{0}")
        @ValueSource(strings = {
                // 客户资产排行
                "SELECT c.customer_id, c.customer_name, ROUND(c.total_asset_amount/10000, 2) AS asset_wan "
                        + "FROM dim_customer c WHERE c.manager_id = 'M0001' AND c.status_code = 'ACTIVE' "
                        + "ORDER BY c.total_asset_amount DESC LIMIT 20",
                // 即将到期持仓
                "SELECT h.product_name, h.holding_amount, DATEDIFF(h.maturity_date, CURDATE()) AS days_left "
                        + "FROM fct_product_holding h JOIN dim_customer c ON c.customer_id = h.customer_id "
                        + "WHERE c.manager_id = 'M0001' AND h.maturity_date IS NOT NULL "
                        + "ORDER BY days_left LIMIT 50",
                // 高净值客群分布
                "SELECT c.customer_level_code, COUNT(*) AS cnt, SUM(c.total_asset_amount) AS total "
                        + "FROM dim_customer c WHERE c.manager_id = 'M0001' AND c.total_asset_amount >= 1000000 "
                        + "GROUP BY c.customer_level_code ORDER BY cnt DESC LIMIT 10",
                // 名下客户交易明细
                "SELECT t.transaction_date, t.amount_cny, t.transaction_type_code FROM fct_transaction t "
                        + "JOIN dim_customer c ON c.customer_id = t.customer_id "
                        + "WHERE c.manager_id = 'M0001' AND t.transaction_date >= '2026-01-01' "
                        + "ORDER BY t.transaction_date DESC LIMIT 100",
                // 名下客户交易汇总（相关子查询聚合）
                "SELECT c.customer_id, c.customer_name, "
                        + "(SELECT SUM(t.amount_cny) FROM fct_transaction t WHERE t.customer_id = c.customer_id) AS total_amount "
                        + "FROM dim_customer c WHERE c.manager_id = 'M0001' LIMIT 50"
        })
        void acceptsRealisticFinancialQueries(String sql) {
            assertThatCode(() -> validator.validate(sql, manager)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("客户模式：每个数据源必须可证明限定为已确认客户")
    class ResolvedCustomerMode {
        @Test
        void enforcesResolvedCustomerEvenWhenSqlHasAccountScope() {
            // 只有账号范围条件不构成客户绑定
            assertThatThrownBy(() -> validator.validateCustomer(
                    "SELECT c.customer_id FROM dim_customer c WHERE c.manager_id='M0001' LIMIT 10",
                    java.util.Map.of(), "C00000001")).isInstanceOf(BusinessException.class);
            assertThatCode(() -> validator.validateCustomer(
                    "SELECT c.customer_id FROM dim_customer c WHERE c.customer_id=:resolvedCustomerId LIMIT 10",
                    java.util.Map.of("resolvedCustomerId", "C00000001"), "C00000001"))
                    .doesNotThrowAnyException();
        }

        @Test
        void factTableMayProveThroughResolvedCustomerDirectly() {
            assertThatCode(() -> validator.validateCustomer(
                    "SELECT t.amount_cny FROM fct_transaction t WHERE t.customer_id=:resolvedCustomerId LIMIT 10",
                    java.util.Map.of("resolvedCustomerId", "C00000001"), "C00000001"))
                    .doesNotThrowAnyException();
            // 客户编号不在确认名单内时不构成证明
            assertThatThrownBy(() -> validator.validateCustomer(
                    "SELECT t.amount_cny FROM fct_transaction t WHERE t.customer_id='C99999999' LIMIT 10",
                    java.util.Map.of(), "C00000001"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(403105));
        }

        @Test
        void acceptsInListWithSingleConfirmedCustomer() {
            assertThatCode(() -> validator.validateCustomer(
                    "SELECT c.customer_id, c.customer_name FROM dim_customer c "
                            + "WHERE c.customer_id IN ('C00000001') LIMIT 10",
                    java.util.Map.of(), "C00000001")).doesNotThrowAnyException();
        }

        @Test
        void rejectsNumericCustomerIdLiteral() {
            // 数字字面量在客户编号域里连事实都不是（factOf 没有数字分支）
            assertThatThrownBy(() -> validator.validateCustomer(
                    "SELECT t.amount_cny FROM fct_transaction t WHERE t.customer_id = 123 LIMIT 10",
                    java.util.Map.of(), "C00000001"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(403105));
        }

        @Test
        void rejectsCaseSpoofedCustomerId() {
            // 编号是大小写敏感的数据：'c00000001' 不在确认名单内
            assertThatThrownBy(() -> validator.validateCustomer(
                    "SELECT c.customer_id FROM dim_customer c WHERE c.customer_id IN ('c00000001') LIMIT 10",
                    java.util.Map.of(), "C00000001"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("批量名单模式：服务端核验名单 + 每个来源保留 IN 条件")
    class BatchListMode {
        private final java.util.List<String> confirmed = java.util.List.of("C00000001", "C00000002");

        @Test
        void batchCustomerListMustRestrainEverySourceThroughVerifiedInList() {
            assertThatCode(() -> validator.validateCustomers(
                    "SELECT t.amount_cny FROM fct_transaction t "
                            + "WHERE t.customer_id IN ('C00000001','C00000002') LIMIT 10",
                    java.util.Map.of(), confirmed)).doesNotThrowAnyException();
            assertThatCode(() -> validator.validateCustomers(
                    "SELECT c.age_band_code FROM dim_customer c JOIN fct_transaction t "
                            + "ON t.customer_id=c.customer_id "
                            + "WHERE t.customer_id IN ('C00000001','C00000002') LIMIT 10",
                    java.util.Map.of(), confirmed)).doesNotThrowAnyException();
            // 名单之外、部分越界或夹带非字符串成员的 IN 列表都不构成证明
            assertThatThrownBy(() -> validator.validateCustomers(
                    "SELECT t.amount_cny FROM fct_transaction t WHERE t.amount_cny>100 LIMIT 10",
                    java.util.Map.of(), confirmed)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> validator.validateCustomers(
                    "SELECT t.amount_cny FROM fct_transaction t "
                            + "WHERE t.customer_id IN ('C00000001','C99999999') LIMIT 10",
                    java.util.Map.of(), confirmed)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> validator.validateCustomers(
                    "SELECT t.amount_cny FROM fct_transaction t WHERE t.customer_id IN ('C00000001',123) LIMIT 10",
                    java.util.Map.of(), confirmed)).isInstanceOf(BusinessException.class);
        }

        @Test
        void acceptsListAppliedToEveryJoinedSource() {
            assertThatCode(() -> validator.validateCustomers(
                    "SELECT c.customer_id, t.amount_cny FROM dim_customer c JOIN fct_transaction t "
                            + "ON t.customer_id = c.customer_id "
                            + "WHERE c.customer_id IN ('C00000001','C00000002') "
                            + "AND t.customer_id IN ('C00000001','C00000002') LIMIT 100",
                    java.util.Map.of(), confirmed)).doesNotThrowAnyException();
        }

        @Test
        void acceptsListAcrossUnionBranches() {
            assertThatCode(() -> validator.validateCustomers(
                    "SELECT c.customer_id FROM dim_customer c "
                            + "WHERE c.customer_id IN ('C00000001','C00000002') "
                            + "UNION ALL "
                            + "SELECT t.customer_id FROM fct_transaction t "
                            + "WHERE t.customer_id IN ('C00000001','C00000002') LIMIT 100",
                    java.util.Map.of(), confirmed)).doesNotThrowAnyException();
        }

        @Test
        void rejectsNamedParameterInsideInList() {
            // 命名参数不是字符串字面量，无法与确认名单逐个核对
            assertThatThrownBy(() -> validator.validateCustomers(
                    "SELECT t.amount_cny FROM fct_transaction t WHERE t.customer_id IN (:idList) LIMIT 10",
                    java.util.Map.of(), confirmed))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(403105));
        }

        @Test
        void marketingChainAuthorizesCampaignThroughProvenCustomerSource() {
            // 事实表获证后，campaign_id 链授权 dim_marketing_campaign
            assertThatCode(() -> validator.validateCustomers(
                    "SELECT m.campaign_name FROM fct_customer_marketing f "
                            + "JOIN dim_marketing_campaign m ON m.campaign_id = f.campaign_id "
                            + "WHERE f.customer_id IN ('C00000001','C00000002') LIMIT 10",
                    java.util.Map.of(), confirmed)).doesNotThrowAnyException();
        }

        @Test
        void rejectsCampaignWithoutAnyCustomerSource() {
            // 批量模式下（user==null）账号检查跳过，错误码为 403105
            assertThatThrownBy(() -> validator.validateCustomers(
                    "SELECT m.campaign_name FROM dim_marketing_campaign m "
                            + "WHERE m.campaign_status_code='ACTIVE' LIMIT 10",
                    java.util.Map.of(), confirmed))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(403105));
        }
    }

    @Nested
    @DisplayName("混合模式：账号与客户两条证明链都要通过")
    class MixedMode {
        private final SqlAstValidator both = new SqlAstValidator(
                manager, java.util.Map.of("resolvedCustomerId", "C00000001"),
                java.util.List.of("C00000001"), 500);

        @Test
        void passesOnlyWhenBothChainsProveEverySource() {
            assertThatCode(() -> both.validate(
                    "SELECT t.amount_cny FROM dim_customer c JOIN fct_transaction t "
                            + "ON t.customer_id=c.customer_id "
                            + "WHERE c.manager_id='M0001' AND c.customer_id=:resolvedCustomerId LIMIT 10"))
                    .doesNotThrowAnyException();
        }

        @Test
        void reportsMissingCustomerBindingAs403105() {
            assertThatThrownBy(() -> both.validate(
                    "SELECT t.amount_cny FROM dim_customer c JOIN fct_transaction t "
                            + "ON t.customer_id=c.customer_id WHERE c.manager_id='M0001' LIMIT 10"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(403105));
        }

        @Test
        void reportsMissingAccountScopeAs403104() {
            assertThatThrownBy(() -> both.validate(
                    "SELECT t.amount_cny FROM dim_customer c JOIN fct_transaction t "
                            + "ON t.customer_id=c.customer_id WHERE c.customer_id=:resolvedCustomerId LIMIT 10"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(403104));
        }
    }
}

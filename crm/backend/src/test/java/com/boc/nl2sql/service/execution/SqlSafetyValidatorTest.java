package com.boc.nl2sql.service.execution;

import com.boc.nl2sql.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只读安全闸门的分层测试：词法防线 → 结构与方言 → 对象/函数白名单 → 表达式形态 → 资源预算 → 错误码契约。
 * 授权证明（账号/客户/批量/混合四种模式）见 {@link GeneratedSqlScopeValidatorTest}。
 * 所有拒绝路径 fail-closed：本套件里每个"通过"都必须同时被授权测试覆盖过语义。
 */
@DisplayName("SqlSafetyValidator：只读安全闸门")
class SqlSafetyValidatorTest {
    private final SqlSafetyValidator validator = new SqlSafetyValidator();

    private void assertRejected(String sql) {
        assertThatThrownBy(() -> validator.validate(sql)).isInstanceOf(BusinessException.class);
    }

    @Nested
    @DisplayName("词法防线：解析之前的文本形态即决")
    class LexicalDefense {
        @ParameterizedTest(name = "拒绝不信任文本形态：{0}")
        @ValueSource(strings = {
                // 多语句：一条校验携带两条执行
                "SELECT customer_id FROM dim_customer LIMIT 1; DROP TABLE dim_customer",
                // 注释：内容对校验器不可见；MySQL 的 /*! */ 还是可执行注释
                "SELECT customer_id FROM dim_customer -- 注释\n LIMIT 1",
                "SELECT /* 注释 */ customer_id FROM dim_customer LIMIT 1",
                "SELECT customer_id FROM dim_customer LIMIT 1 # 注释",
                // 会话变量：取值无法静态核对
                "SELECT customer_id FROM dim_customer WHERE customer_id = @a LIMIT 1",
                // 双引号：MySQL 默认是字符串、标准模式是标识符，存在解释歧义
                "SELECT \"customer_id\" FROM dim_customer LIMIT 1",
                // 反引号：整体禁止（含转义形态），引用标识符不解锁任何能力
                "SELECT customer_id FROM `dim_customer` LIMIT 1",
                "SELECT c.`cus``tomer` FROM dim_customer c LIMIT 1"
        })
        void rejectsUntrustedTextForms(String sql) {
            assertRejected(sql);
        }

        @Test
        void rejectsUnclosedQuote() {
            assertRejected("SELECT customer_id FROM dim_customer WHERE customer_id = 'C1");
        }

        @Test
        void rejectsBackslashEscapeInsideString() {
            // MySQL 把 \' 当转义引号、解析器当字符串结束——两边对字符串边界的判定不同
            assertRejected("SELECT customer_id FROM dim_customer WHERE customer_id = 'C1\\' LIMIT 1");
        }

        @Test
        void rejectsOversizedSql() {
            assertRejected("SELECT customer_id FROM dim_customer WHERE customer_id = '" + "A".repeat(30000) + "' LIMIT 1");
        }
    }

    @Nested
    @DisplayName("结构与方言防线：只放行 MySQL 8.4 的受控 SELECT 形态")
    class StructureAndDialect {
        @ParameterizedTest(name = "拒绝非只读或外来方言：{0}")
        @ValueSource(strings = {
                "DELETE FROM dim_customer WHERE customer_id='C1'",
                "UPDATE dim_customer SET status_code='FROZEN' WHERE customer_id='C1'",
                "INSERT INTO dim_customer (customer_id) VALUES ('C1')",
                "SHOW TABLES",
                "SET @a = 1",
                "CALL some_procedure()",
                // 锁与游标形态
                "SELECT customer_id FROM dim_customer LIMIT 1 FOR UPDATE",
                "SELECT customer_id FROM dim_customer LIMIT 1 FOR SHARE",
                // 服务端变量接收（JSqlParser 5.2 解析失败，落入解析异常拒绝）
                "SELECT customer_id FROM dim_customer LIMIT 1 INTO @v",
                // SQL Server 方言
                "SELECT TOP 5 customer_id FROM dim_customer LIMIT 1"
        })
        void rejectsNonReadOnlyOrForeignDialect(String sql) {
            assertRejected(sql);
        }

        @Test
        void acceptsMysql84SetOperations() {
            // MySQL 8.0.31+：UNION/UNION ALL/INTERSECT/EXCEPT，各分支独立校验
            assertThatCode(() -> validator.validate(
                    "SELECT customer_id FROM dim_customer UNION ALL SELECT customer_id FROM dim_customer LIMIT 10"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> validator.validate(
                    "SELECT customer_id FROM dim_customer INTERSECT SELECT customer_id FROM fct_transaction LIMIT 10"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> validator.validate(
                    "SELECT customer_id FROM dim_customer EXCEPT SELECT customer_id FROM fct_transaction LIMIT 10"))
                    .doesNotThrowAnyException();
        }

        @Test
        void rejectsOracleMinusAndUnsupportedSetShapes() {
            assertRejected("SELECT customer_id FROM dim_customer MINUS SELECT customer_id FROM dim_customer");
        }

        @Test
        void rejectsGroupingSets() {
            assertRejected("SELECT c.age_band_code, COUNT(*) FROM dim_customer c "
                    + "GROUP BY GROUPING SETS ((c.age_band_code),(c.region_code)) LIMIT 10");
        }

        @ParameterizedTest(name = "拒绝选择性扩展：{0}")
        @ValueSource(strings = {
                "SELECT DISTINCT ON (c.customer_id) c.customer_id FROM dim_customer c LIMIT 10",
                "SELECT SQL_CALC_FOUND_ROWS c.customer_id FROM dim_customer c LIMIT 10",
                "WITH RECURSIVE a AS (SELECT customer_id FROM dim_customer) SELECT a.customer_id FROM a LIMIT 10"
        })
        void rejectsUnsupportedExtensions(String sql) {
            assertRejected(sql);
        }

        @Test
        void acceptsQueryWithoutLimitBecauseExecutionAddsPagination() {
            assertThatCode(() -> validator.validate("SELECT customer_id FROM dim_customer"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("分页：可选但出现时必须合法")
    class Pagination {
        @Test
        void acceptsConstantLimitAndOffset() {
            assertThatCode(() -> validator.validate("SELECT customer_id FROM dim_customer LIMIT 10 OFFSET 5"))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "拒绝非法分页：{0}")
        @ValueSource(strings = {
                "SELECT customer_id FROM dim_customer LIMIT 1000",   // 超过 maxRows
                "SELECT customer_id FROM dim_customer LIMIT 0",      // 下界
                "SELECT customer_id FROM dim_customer LIMIT '10'"    // 非常量
        })
        void rejectsInvalidPagination(String sql) {
            assertRejected(sql);
        }
    }

    @Nested
    @DisplayName("对象与函数白名单：数据源、列、函数逐个核对")
    class Whitelists {
        @ParameterizedTest(name = "放行业务表：{0}")
        @ValueSource(strings = {
                "SELECT customer_id FROM dim_customer LIMIT 1",
                "SELECT t.amount_cny FROM fct_transaction t LIMIT 1",
                "SELECT h.product_name FROM fct_product_holding h LIMIT 1",
                "SELECT m.campaign_name FROM dim_marketing_campaign m LIMIT 1",
                "SELECT f.response_flag FROM fct_customer_marketing f LIMIT 1"
        })
        void acceptsEveryBusinessTable(String sql) {
            assertThatCode(() -> validator.validate(sql)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "拒绝库外对象：{0}")
        @ValueSource(strings = {
                "SELECT customer_id FROM mysql.user LIMIT 1",
                "SELECT table_name FROM information_schema.tables LIMIT 1",
                "SELECT thread_id FROM performance_schema.threads LIMIT 1",
                "SELECT customer_id FROM other_db.dim_customer LIMIT 1"
        })
        void rejectsObjectsOutsideBusinessSchema(String sql) {
            assertRejected(sql);
        }

        @Test
        void acceptsFullNameColumnPerBusinessDecision() {
            // 业务决策（v1.7）：客户经理可见完整姓名，customer_name 纳入白名单；
            // mobile 仍只有脱敏形态 mobile_masked。
            assertThatCode(() -> validator.validate(
                    "SELECT c.customer_name, c.mobile_masked FROM dim_customer c LIMIT 10"))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "拒绝通配符：{0}")
        @ValueSource(strings = {
                "SELECT * FROM dim_customer LIMIT 1",
                "SELECT c.* FROM dim_customer c LIMIT 1"
        })
        void rejectsWildcardColumns(String sql) {
            assertRejected(sql);
        }

        @ParameterizedTest(name = "拒绝危险或未收录函数：{0}")
        @ValueSource(strings = {
                "SELECT SLEEP(1) FROM dim_customer LIMIT 1",
                "SELECT BENCHMARK(1000000, MD5('x')) FROM dim_customer LIMIT 1",
                "SELECT GET_LOCK('a', 1) FROM dim_customer LIMIT 1",
                "SELECT LOAD_FILE('/etc/passwd') FROM dim_customer LIMIT 1",
                "SELECT UUID() FROM dim_customer LIMIT 1",
                "SELECT DATABASE() FROM dim_customer LIMIT 1",
                "SELECT USER() FROM dim_customer LIMIT 1",
                "SELECT CONNECTION_ID() FROM dim_customer LIMIT 1",
                "SELECT GROUP_CONCAT(customer_id) FROM dim_customer LIMIT 1",
                "SELECT CHAR(67,48) FROM dim_customer LIMIT 1"
        })
        void rejectsNonWhitelistedFunctions(String sql) {
            assertRejected(sql);
        }
    }

    @Nested
    @DisplayName("表达式形态：白名单内的 MySQL 8.4 常用分析写法")
    class ExpressionShapes {
        @ParameterizedTest(name = "放行表达式形态：{0}")
        @ValueSource(strings = {
                // 条件与谓词
                "SELECT c.customer_id FROM dim_customer c WHERE c.age IS NULL LIMIT 10",
                "SELECT c.customer_id FROM dim_customer c WHERE c.mobile_masked IS NOT NULL LIMIT 10",
                "SELECT c.customer_id FROM dim_customer c WHERE NOT c.age > 60 LIMIT 10",
                "SELECT c.customer_id, c.customer_name FROM dim_customer c WHERE c.customer_name LIKE '王%' LIMIT 10",
                "SELECT c.customer_id FROM dim_customer c WHERE c.customer_id REGEXP '^C' LIMIT 10",
                "SELECT c.customer_id FROM dim_customer c WHERE c.customer_id RLIKE '^C' LIMIT 10",
                "SELECT c.customer_id FROM dim_customer c WHERE c.age > -1 LIMIT 10",
                // 算术与聚合
                "SELECT c.customer_id, c.age DIV 10 AS decade FROM dim_customer c LIMIT 10",
                "SELECT c.customer_id, c.age % 10 AS age_tail FROM dim_customer c LIMIT 10",
                "SELECT c.customer_level_code, COUNT(DISTINCT c.customer_id) AS cnt FROM dim_customer c "
                        + "WHERE c.manager_id='M0001' GROUP BY c.customer_level_code LIMIT 10",
                // 日期
                "SELECT EXTRACT(YEAR FROM c.open_date) AS y FROM dim_customer c LIMIT 10",
                "SELECT DATE_ADD('2026-01-01', INTERVAL 30 DAY) AS d FROM dim_customer LIMIT 1",
                "SELECT NOW() AS queried_at, customer_id FROM dim_customer LIMIT 1",
                "SELECT CURRENT_DATE AS today FROM dim_customer LIMIT 1",
                // CAST（含精度）
                "SELECT CAST(c.total_asset_amount AS DECIMAL(12,2)) AS asset_exact FROM dim_customer c LIMIT 10",
                // 子查询与派生表
                "SELECT c.customer_id FROM dim_customer c WHERE c.customer_id IN "
                        + "(SELECT t.customer_id FROM fct_transaction t) LIMIT 10",
                "SELECT q.customer_count FROM (SELECT COUNT(*) AS customer_count FROM dim_customer) q LIMIT 10",
                "WITH scoped AS (SELECT customer_id FROM dim_customer WHERE region_code='EAST') "
                        + "SELECT s.customer_id FROM scoped s LIMIT 10",
                // 窗口（匿名 + 命名 + 帧）
                "SELECT c.customer_id, ROW_NUMBER() OVER (PARTITION BY c.branch_id ORDER BY c.total_asset_amount DESC) AS rn "
                        + "FROM dim_customer c LIMIT 10",
                "SELECT c.customer_id, SUM(c.total_asset_amount) OVER w AS running_total FROM dim_customer c "
                        + "WINDOW w AS (ORDER BY c.total_asset_amount ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) LIMIT 10",
                // 复合 ON 的 INNER JOIN
                "SELECT c.customer_id FROM dim_customer c JOIN fct_transaction t "
                        + "ON t.customer_id = c.customer_id AND t.branch_id = c.branch_id LIMIT 10"
        })
        void acceptsWhitelistedExpressionShapes(String sql) {
            assertThatCode(() -> validator.validate(sql)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "拒绝表达式形态：{0}")
        @ValueSource(strings = {
                "SELECT customer_id FROM dim_customer WHERE customer_id <=> 'C1' LIMIT 1",   // NULL 安全等号未收录
                "SELECT c.* FROM dim_customer c LIMIT 10",                                   // 表级通配
                "SELECT TRIM(BOTH ' ' FROM customer_id) FROM dim_customer LIMIT 10"          // 函数方言扩展位
        })
        void rejectsUnsupportedExpressionShapes(String sql) {
            assertRejected(sql);
        }

        @Test
        void acceptsComplexReadOnlyMysqlShapes() {
            // CASE 分桶 + BETWEEN + GROUP BY + HAVING 聚合 + 别名排序
            assertThatCode(() -> validator.validate(
                    "SELECT c.age_band_code, SUM(CASE WHEN c.total_asset_amount BETWEEN 1000000 AND 5000000 THEN 1 "
                            + "ELSE 0 END) AS customer_count FROM dim_customer c "
                            + "WHERE c.open_date BETWEEN DATE('2025-01-01') AND DATE('2026-08-28') "
                            + "GROUP BY c.age_band_code HAVING COUNT(c.customer_id) > 0 "
                            + "ORDER BY customer_count DESC LIMIT 100"))
                    .doesNotThrowAnyException();
            // 月度快照 + 命名分区窗口
            assertThatCode(() -> validator.validate(
                    "SELECT c.customer_id, DATE_FORMAT(c.snapshot_date,'%Y-%m') AS snapshot_month, "
                            + "ROW_NUMBER() OVER (PARTITION BY c.branch_id ORDER BY c.total_asset_amount DESC) AS ranking "
                            + "FROM dim_customer c WHERE c.customer_level_code IN ('GOLD','PLATINUM') LIMIT 100"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("资源与预算：对抗性输入有硬上界")
    class ResourceBudgets {
        @Test
        void rejectsExpressionNodeBudgetExhaustion() {
            // 1200 项恒真等值：表达式节点数远超 3000 的预算上限
            String sql = "SELECT customer_id FROM dim_customer WHERE customer_id = 'C' AND "
                    + "1 = 1 AND ".repeat(1200) + "1 = 1 LIMIT 10";
            assertThatThrownBy(() -> validator.validate(sql))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(422101));
        }

        @Test
        void acceptsQueryAtDepthBudgetBoundary() {
            assertThatCode(() -> validator.validate(nestedInSubquery(12))).doesNotThrowAnyException();
        }

        @Test
        void rejectsDepthBeyondBudget() {
            assertRejected(nestedInSubquery(13));
        }

        @Test
        void acceptsFlatAndChainWithinDepthBudget() {
            // 150 项 AND：左退化树深约 151，在深度预算内（真实分析 SQL 的谓词数远低于此）
            String sql = "SELECT customer_id FROM dim_customer WHERE region_code = 'EAST' AND "
                    + "status_code = 'ACTIVE' AND ".repeat(150) + "age > 18 LIMIT 10";
            assertThatCode(() -> validator.validate(sql)).doesNotThrowAnyException();
        }

        @Test
        void rejectsFlatAndChainBeyondDepthBudget() {
            // 300 项 AND：树深约 300 超过深度预算——对抗性长链在打爆线程栈之前被干净拒绝
            String sql = "SELECT customer_id FROM dim_customer WHERE region_code = 'EAST' AND "
                    + "status_code = 'ACTIVE' AND ".repeat(300) + "age > 18 LIMIT 10";
            assertRejected(sql);
        }

        @Test
        void rejectsDeeplyNestedParenthesesBeforeStackOverflow() {
            // 500 层嵌套括号：树深超限，在打爆线程栈之前干净拒绝（而非 StackOverflowError）
            assertRejected("SELECT customer_id FROM dim_customer WHERE ("
                    + "(".repeat(500) + "customer_id = 'C1" + ")".repeat(500) + ") LIMIT 1");
        }

        /** 逐层嵌套 IN 子查询：每层是一个完整查询块，深度随层数递增。 */
        private String nestedInSubquery(int levels) {
            String sql = "SELECT customer_id FROM dim_customer LIMIT 10";
            for (int i = 0; i < levels; i++) {
                sql = "SELECT customer_id FROM dim_customer WHERE customer_id IN (" + sql + ")";
            }
            return sql;
        }
    }

    @Nested
    @DisplayName("错误码契约：拒绝原因可被修复回路精确消费")
    class ErrorContract {
        @Test
        void reportsPreciseErrorCodesPerRejectionLayer() {
            // 表白名单 403102
            assertCode("SELECT customer_id FROM mysql.user LIMIT 10", 403102);
            // 字段白名单 422104
            assertCode("SELECT c.unknown_column FROM dim_customer c LIMIT 10", 422104);
            // 分页上限 422102
            assertCode("SELECT customer_id FROM dim_customer LIMIT 1000", 422102);
            // 结构与白名单 422101
            assertCode("SELECT * FROM dim_customer LIMIT 10", 422101);
            assertCode("SELECT customer_id FROM dim_customer LIMIT 10 OFFSET -1", 422102);
        }

        private void assertCode(String sql, int expected) {
            assertThatThrownBy(() -> validator.validate(sql))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo(expected));
        }
    }
}

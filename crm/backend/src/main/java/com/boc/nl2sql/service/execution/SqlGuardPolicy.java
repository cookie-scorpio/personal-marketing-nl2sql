package com.boc.nl2sql.service.execution;

import java.util.Map;
import java.util.Set;

/**
 * 安全闸门的白名单与资源限额：唯一事实来源。
 *
 * <p>维护约定：</p>
 * <ul>
 *   <li>表/列跟随数据库迁移在代码中维护（与 Flyway 迁移同评审）；</li>
 *   <li>函数清单只放行只读语义——sleep、get_lock、load_file 这类副作用函数永不加入；</li>
 *   <li>资源限额钉死对抗性输入的消耗上界（字符数、查询块深度、表达式节点与递归深度、解析超时）。</li>
 * </ul>
 *
 * <p>若运营侧未来需要不发布代码调整函数清单，此类是接入外部配置的天然接缝；
 * 但安全白名单进入配置文件前必须同步补齐启动校验与变更审计。</p>
 */
final class SqlGuardPolicy {
    static final String CUSTOMER_ID = "customer_id";
    static final String CAMPAIGN_ID = "campaign_id";
    static final String DIM_CUSTOMER = "dim_customer";
    static final String DIM_MARKETING_CAMPAIGN = "dim_marketing_campaign";
    static final String FCT_CUSTOMER_MARKETING = "fct_customer_marketing";

    private static final int DEFAULT_MAX_SQL_LENGTH = 30000;
    private static final int DEFAULT_MAX_QUERY_DEPTH = 12;
    private static final int DEFAULT_MAX_EXPRESSION_NODES = 3000;
    private static final int DEFAULT_MAX_EXPRESSION_DEPTH = 200;
    private static final int DEFAULT_PARSE_TIMEOUT_MILLIS = 2000;

    /** 可查询的物理表及其列。customer_name 已纳入白名单，前端直接展示完整姓名。 */
    private static final Map<String, Set<String>> STANDARD_TABLES = Map.of(
            DIM_CUSTOMER,
            words("customer_id customer_name gender_code age age_band_code mobile_masked customer_level_code "
                    + "vip_flag risk_level_code occupation_code region_code branch_id manager_id total_asset_amount "
                    + "asset_change_3m_rate open_date status_code snapshot_date"),
            "fct_transaction",
            words("transaction_id customer_id product_id transaction_time transaction_date transaction_type_code "
                    + "debit_credit_flag currency_code amount_cny branch_id status_code"),
            "fct_product_holding",
            words("holding_id customer_id product_id product_name product_category_code holding_amount "
                    + "market_value_amount profit_amount maturity_date risk_level_code snapshot_date"),
            DIM_MARKETING_CAMPAIGN,
            words("campaign_id campaign_name campaign_type_code campaign_status_code product_id "
                    + "target_customer_segment_code channel_code owner_org_id owner_manager_id start_time end_time "
                    + "budget_amount target_count"),
            FCT_CUSTOMER_MARKETING,
            words("relation_id campaign_id customer_id contact_time contact_channel_code response_flag "
                    + "conversion_flag conversion_amount"));

    /**
     * 允许的函数（聚合/数学/字符串/日期/窗口）。维护约束：只读语义之外的一律不放，
     * sleep、get_lock、load_file 这类副作用函数永不加入白名单。
     */
    private static final Set<String> STANDARD_FUNCTIONS = words(
            "count sum avg min max round abs ceil ceiling floor coalesce ifnull nullif if concat concat_ws substring "
                    + "substr left right length char_length lower upper trim date date_format year month day dayofmonth "
                    + "quarter datediff timestampdiff date_add date_sub extract greatest least stddev_pop stddev_samp "
                    + "variance var_pop var_samp power sqrt mod row_number rank dense_rank lag lead first_value "
                    + "last_value ntile now curdate curtime current_date current_time current_timestamp");

    /** 允许的二元运算符，按 JSqlParser 实现类简单名匹配。 */
    private static final Set<String> STANDARD_BINARY_OPERATORS = Set.of(
            "AndExpression", "OrExpression", "EqualsTo", "NotEqualsTo",
            "GreaterThan", "GreaterThanEquals", "MinorThan", "MinorThanEquals",
            "Addition", "Subtraction", "Multiplication", "Division", "IntegerDivision", "Modulo",
            "LikeExpression");

    /** 允许的 CAST 目标类型。 */
    private static final Set<String> STANDARD_CAST_TYPES = Set.of(
            "DECIMAL", "SIGNED", "UNSIGNED", "CHAR", "DATE", "DATETIME", "TIME", "INTEGER", "DOUBLE");

    /** 允许的无括号时间关键字：MySQL 里 CURRENT_DATE 既可以写成函数也可以写成关键字。 */
    private static final Set<String> STANDARD_TIME_KEYWORDS = Set.of(
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP");

    private final Map<String, Set<String>> tables;
    private final Set<String> functions;
    private final Set<String> binaryOperators;
    private final Set<String> castTypes;
    private final Set<String> timeKeywords;
    private final int maxSqlLength;
    private final int maxQueryDepth;
    private final int maxExpressionNodes;
    private final int maxExpressionDepth;
    private final int parseTimeoutMillis;

    private SqlGuardPolicy(Map<String, Set<String>> tables, Set<String> functions, Set<String> binaryOperators,
                           Set<String> castTypes, Set<String> timeKeywords, int maxSqlLength, int maxQueryDepth,
                           int maxExpressionNodes, int maxExpressionDepth, int parseTimeoutMillis) {
        this.tables = Map.copyOf(tables);
        this.functions = Set.copyOf(functions);
        this.binaryOperators = Set.copyOf(binaryOperators);
        this.castTypes = Set.copyOf(castTypes);
        this.timeKeywords = Set.copyOf(timeKeywords);
        this.maxSqlLength = maxSqlLength;
        this.maxQueryDepth = maxQueryDepth;
        this.maxExpressionNodes = maxExpressionNodes;
        this.maxExpressionDepth = maxExpressionDepth;
        this.parseTimeoutMillis = parseTimeoutMillis;
    }

    /** 当前业务库的标准白名单与资源限额。 */
    static SqlGuardPolicy standard() {
        return new SqlGuardPolicy(STANDARD_TABLES, STANDARD_FUNCTIONS, STANDARD_BINARY_OPERATORS,
                STANDARD_CAST_TYPES, STANDARD_TIME_KEYWORDS, DEFAULT_MAX_SQL_LENGTH, DEFAULT_MAX_QUERY_DEPTH,
                DEFAULT_MAX_EXPRESSION_NODES, DEFAULT_MAX_EXPRESSION_DEPTH, DEFAULT_PARSE_TIMEOUT_MILLIS);
    }

    boolean isTableAllowed(String tableName) {
        return tables.containsKey(tableName);
    }

    Set<String> columnsOf(String tableName) {
        return tables.get(tableName);
    }

    boolean isFunctionAllowed(String functionName) {
        return functions.contains(functionName);
    }

    boolean isBinaryOperatorAllowed(String operatorClassName) {
        return binaryOperators.contains(operatorClassName);
    }

    boolean isCastTypeAllowed(String dataTypeName) {
        return castTypes.contains(dataTypeName);
    }

    boolean isTimeKeywordAllowed(String keyword) {
        return timeKeywords.contains(keyword);
    }

    int maxSqlLength() {
        return maxSqlLength;
    }

    int maxQueryDepth() {
        return maxQueryDepth;
    }

    int maxExpressionNodes() {
        return maxExpressionNodes;
    }

    int maxExpressionDepth() {
        return maxExpressionDepth;
    }

    int parseTimeoutMillis() {
        return parseTimeoutMillis;
    }

    private static Set<String> words(String spaceSeparated) {
        return Set.of(spaceSeparated.split("\\s+"));
    }
}

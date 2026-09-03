package com.boc.nl2sql.service.execution;

import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.service.authorization.DataScopePolicy;
import com.boc.nl2sql.common.exception.BusinessException;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExtractExpression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.IntervalExpression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.WindowElement;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.UnionOp;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 生成 SQL 的白名单 AST 校验器，是模型产物进入执行层前的最后闸门。
 *
 * 三层防线：
 * <ol>
 *   <li>词法预检：拒绝注释、多语句、会话变量、反斜杠转义等无法可靠静态分析的形态；</li>
 *   <li>结构与白名单：仅接受受控的 SELECT 形态，表/列/函数/运算符/CAST类型全部走白名单，
 *       未知结构一律拒绝（绝不静默放行）；</li>
 *   <li>授权证明：按查询块构建等值约束图，证明每个物理数据源被账号数据范围或已确认客户约束。
 *       证明不了的直接拒绝，不把 SQL 里碰巧出现的范围字符串当作授权依据。</li>
 * </ol>
 *
 * <p>为什么第三层需要"证明"：SQL 由模型生成，提示词与数据库权限都挡不住"范围条件看起来存在、
 * 语义上不生效"的写法——OR 分支中的条件、LEFT JOIN 的 ON 只约束右表、约束落在子查询里等。
 * 因此把等值条件抽象为约束传播图，从账号范围值/已确认客户出发推导每个物理数据源是否被约束。
 * 这套推导是"等值连接传递约束、约束多跳传播、OR/LEFT-JOIN-ON 不传播、传播覆盖所有物理表"
 * 四条 SQL 语义规则的最小形式化，不是可选的复杂度。</p>
 *
 * <p>推荐阅读顺序：{@link #validate} → {@link #analyzePlainSelect}（单个查询块的完整流程）
 * → {@link #proveDataSources}（授权证明核心）。设计背景、行为规格与常见疑问详见
 * docs/SqlAstValidator设计说明.md。</p>
 *
 * <p>配置全部为 final，一次 validate 的可变状态集中在校验开始时创建的 {@link Run} 里，
 * 因此实例线程安全、可复用；调用方按 new SqlAstValidator(...).validate(sql) 使用即可。</p>
 */
public final class SqlAstValidator {

    // ==================== 限额与错误码 ====================

    private static final int MAX_SQL_LENGTH = 30000;
    private static final int MAX_QUERY_DEPTH = 12;
    private static final int MAX_EXPRESSION_NODES = 3000;
    private static final int PARSE_TIMEOUT_MILLIS = 2000;

    /** 语法、结构、白名单类拒绝。 */
    private static final int SQL_STRUCTURE_REJECTED = 422101;
    /** LIMIT/OFFSET 类拒绝。 */
    private static final int SQL_PAGINATION_REJECTED = 422102;
    /** 字段类拒绝。 */
    private static final int SQL_COLUMN_REJECTED = 422104;
    /** 表对象类拒绝。 */
    private static final int SQL_TABLE_REJECTED = 403102;
    /** 账号数据范围未配置。 */
    private static final int ACCOUNT_SCOPE_INVALID = 403103;
    /** 未证明账号范围约束。 */
    private static final int SCOPE_NOT_PROVEN = 403104;
    /** 未证明已确认客户约束。 */
    private static final int CUSTOMER_NOT_PROVEN = 403105;

    // ==================== 白名单 ====================

    private static final String CUSTOMER_ID = "customer_id";
    private static final String CAMPAIGN_ID = "campaign_id";
    private static final String DIM_CUSTOMER = "dim_customer";
    private static final String DIM_MARKETING_CAMPAIGN = "dim_marketing_campaign";
    private static final String FCT_CUSTOMER_MARKETING = "fct_customer_marketing";

    /** 可查询的物理表及其列。customer_name 已纳入白名单，前端直接展示完整姓名。 */
    private static final Map<String, Set<String>> SCHEMA = Map.of(
            DIM_CUSTOMER,
            columns("customer_id customer_name gender_code age age_band_code mobile_masked customer_level_code "
                    + "vip_flag risk_level_code occupation_code region_code branch_id manager_id total_asset_amount "
                    + "asset_change_3m_rate open_date status_code snapshot_date"),
            "fct_transaction",
            columns("transaction_id customer_id product_id transaction_time transaction_date transaction_type_code "
                    + "debit_credit_flag currency_code amount_cny branch_id status_code"),
            "fct_product_holding",
            columns("holding_id customer_id product_id product_name product_category_code holding_amount "
                    + "market_value_amount profit_amount maturity_date risk_level_code snapshot_date"),
            DIM_MARKETING_CAMPAIGN,
            columns("campaign_id campaign_name campaign_type_code campaign_status_code product_id "
                    + "target_customer_segment_code channel_code owner_org_id owner_manager_id start_time end_time "
                    + "budget_amount target_count"),
            FCT_CUSTOMER_MARKETING,
            columns("relation_id campaign_id customer_id contact_time contact_channel_code response_flag "
                    + "conversion_flag conversion_amount"));

    /**
     * 允许的函数（聚合/数学/字符串/日期/窗口）。维护约束：只读语义之外的一律不放，
     * sleep、get_lock、load_file 这类副作用函数永不加入白名单。
     */
    private static final Set<String> FUNCTIONS = columns(
            "count sum avg min max round abs ceil ceiling floor coalesce ifnull nullif if concat concat_ws substring "
                    + "substr left right length char_length lower upper trim date date_format year month day dayofmonth "
                    + "quarter datediff timestampdiff date_add date_sub extract greatest least stddev_pop stddev_samp "
                    + "variance var_pop var_samp power sqrt mod row_number rank dense_rank lag lead first_value "
                    + "last_value ntile");

    /** 允许的二元运算符，按 JSqlParser 实现类简单名匹配。 */
    private static final Set<String> BINARY_OPERATORS = Set.of(
            "AndExpression", "OrExpression", "EqualsTo", "NotEqualsTo",
            "GreaterThan", "GreaterThanEquals", "MinorThan", "MinorThanEquals",
            "Addition", "Subtraction", "Multiplication", "Division", "IntegerDivision", "Modulo",
            "LikeExpression");

    /** 允许的 CAST 目标类型。 */
    private static final Set<String> CAST_TYPES = Set.of(
            "DECIMAL", "SIGNED", "UNSIGNED", "CHAR", "DATE", "DATETIME", "TIME", "INTEGER", "DOUBLE");

    private static Set<String> columns(String spaceSeparated) {
        return Set.of(spaceSeparated.split("\\s+"));
    }

    // ==================== 配置 ====================

    private final CurrentUser user;
    private final Map<String, Object> parameters;
    /**
     * 服务端核验过的已确认客户编号。null 表示本次校验不要求客户绑定证明；
     * 空集合视为 null（没有任何已确认客户时无从证明，交由调用方语义决定）。
     */
    private final Set<String> confirmedCustomers;
    private final int maxRows;

    public SqlAstValidator(CurrentUser user, Map<String, Object> parameters,
                           java.util.Collection<String> confirmedCustomers, int maxRows) {
        this.user = user;
        this.parameters = parameters == null ? Map.of() : parameters;
        this.confirmedCustomers = confirmedCustomers == null || confirmedCustomers.isEmpty()
                ? null
                : Set.copyOf(confirmedCustomers);
        this.maxRows = maxRows;
    }

    // ==================== 入口 ====================

    public void validate(String sql) {
        if (sql == null || sql.isBlank() || sql.length() > MAX_SQL_LENGTH)
            fail(SQL_STRUCTURE_REJECTED, "SQL为空或超过长度限制");
        // 第一道：词法预检。必须在解析之前——注释、反斜杠转义这类形态会被解析器静默吞掉，
        // 或与 MySQL 的解释不一致，进了 AST 就再也看不见了（设计文档 3.1 节）。
        lexicalPreCheck(sql);
        Run run = new Run();
        try {
            // 第二道：解析 + 结构与白名单校验。parseStatements 会解析出多条语句，
            // 必须断言"恰好一条且是 SELECT"；超时防构造输入拖死解析器。
            var statements = CCJSqlParserUtil.parseStatements(sql, parser -> parser.withTimeOut(PARSE_TIMEOUT_MILLIS));
            if (statements.size() != 1 || !(statements.get(0) instanceof Select))
                fail(SQL_STRUCTURE_REJECTED, "仅允许单条只读SELECT");
            // 第三道：授权证明在 analyzeSelect 递归内部的每个查询块上执行。
            analyzeSelect((Select) statements.get(0), null, new LinkedHashMap<>(), 0, run);
        } catch (BusinessException rejected) {
            throw rejected;
        } catch (Exception parseFailure) {
            // 不携带原始异常信息，避免泄露解析器内部细节
            fail(SQL_STRUCTURE_REJECTED, "SQL语法无法解析或尚未支持");
        }
    }

    /** 拒绝一切无法被后续 AST 校验可靠覆盖的词法形态。 */
    private static void lexicalPreCheck(String sql) {
        boolean insideString = false;
        boolean insideBackticks = false;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (insideString) {
                // MySQL 把 \' 当转义引号、解析器当字符串结束——两边对字符串边界的判定不同，
                // 证明时比较的字面量值就可能和数据库实际比较的不一致，直接拒绝。
                if (current == '\\')
                    fail(SQL_STRUCTURE_REJECTED, "字符串转义方式未支持，请使用标准单引号转义");
                if (current == '\'') {
                    // 唯一允许的转义：标准 ''，跳过第二个引号后字符串继续
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'')
                        i++;
                    else
                        insideString = false;
                }
                continue;
            }
            // 反引号内是标识符原样内容，除配对外不做检查
            if (current == '`') {
                insideBackticks = !insideBackticks;
                continue;
            }
            if (insideBackticks)
                continue;
            if (current == '\'') {
                insideString = true;
                continue;
            }
            // MySQL 的 /*! */ 是"可执行注释"：解析器当注释丢弃、数据库照常执行，
            // 是只能在本层拦截的绕过形态；--、#、@、双引号、分号同理均不允许。
            boolean commentAhead = i + 1 < sql.length()
                    && ((current == '-' && sql.charAt(i + 1) == '-') || (current == '/' && sql.charAt(i + 1) == '*'));
            if (current == '@' || current == ';' || current == '#' || current == '"' || commentAhead)
                fail(SQL_STRUCTURE_REJECTED, "不允许变量、注释、多语句或双引号歧义");
        }
        if (insideString || insideBackticks)
            fail(SQL_STRUCTURE_REJECTED, "SQL引号未闭合");
    }

    // ==================== SELECT 结构 ====================

    /**
     * 递归校验一个 SELECT 节点，返回其输出列名——它是 CTE、派生表和 UNION 对外的"表结构"。
     * outer 为所属外层查询块的作用域（关联子查询由此引用外层），CTE 自身为 null。
     */
    private List<String> analyzeSelect(Select select, Scope outer, Map<String, List<String>> inheritedCtes,
                                       int depth, Run run) {
        if (depth > MAX_QUERY_DEPTH || ++run.nodes > MAX_EXPRESSION_NODES)
            fail(SQL_STRUCTURE_REJECTED, "SQL结构超过复杂度限制");
        rejectUnsupportedSelectFeatures(select);
        checkPagination(select);
        Map<String, List<String>> ctes = declareCtes(select, inheritedCtes, depth, run);

        if (select instanceof ParenthesedSelect parenthesized)
            return analyzeSelect(parenthesized.getSelect(), outer, ctes, depth + 1, run);
        if (select instanceof SetOperationList setOperation)
            return analyzeSetOperation(setOperation, outer, ctes, depth, run);
        if (select instanceof PlainSelect plain)
            return analyzePlainSelect(plain, outer, ctes, depth, run);
        fail(SQL_STRUCTURE_REJECTED, "仅支持受控SELECT查询块");
        return List.of();
    }

    /** CTE 逐个声明、逐个校验；后声明的 CTE 可以引用先声明的。 */
    private Map<String, List<String>> declareCtes(Select select, Map<String, List<String>> inherited,
                                                  int depth, Run run) {
        Map<String, List<String>> ctes = new LinkedHashMap<>(inherited);
        if (select.getWithItemsList() == null)
            return ctes;
        for (WithItem item : select.getWithItemsList()) {
            if (item.isRecursive() || item.getSelect() == null
                    || item.getWithItemList() != null && !item.getWithItemList().isEmpty())
                fail(SQL_STRUCTURE_REJECTED, "仅支持非递归SELECT CTE，列名请在SELECT中显式声明");
            String name = normalizeIdentifier(item.getAliasName());
            if (ctes.containsKey(name) || SCHEMA.containsKey(name))
                fail(SQL_STRUCTURE_REJECTED, "CTE名称重复或遮蔽业务表");
            ctes.put(name, analyzeSelect(item.getSelect(), null, ctes, depth + 1, run));
        }
        return ctes;
    }

    private List<String> analyzeSetOperation(SetOperationList setOperation, Scope outer,
                                             Map<String, List<String>> ctes, int depth, Run run) {
        if (setOperation.getOperations().stream().anyMatch(operation -> !(operation instanceof UnionOp)))
            fail(SQL_STRUCTURE_REJECTED, "集合运算仅支持UNION与UNION ALL");
        List<String> columns = null;
        for (Select branch : setOperation.getSelects()) {
            List<String> branchColumns = analyzeSelect(branch, outer, ctes, depth + 1, run);
            if (columns != null && columns.size() != branchColumns.size())
                fail(SQL_STRUCTURE_REJECTED, "UNION各分支列数不一致");
            if (columns == null)
                columns = branchColumns;
        }
        // 集合层 ORDER BY 只能引用分支的输出列。
        Scope outputScope = new Scope(null);
        outputScope.aliases.addAll(columns == null ? List.of() : columns);
        checkOrderBy(setOperation.getOrderByElements(), outputScope, ctes, depth, run);
        return columns;
    }

    private static void rejectUnsupportedSelectFeatures(Select select) {
        if (select.getForMode() != null || select.getForClause() != null || select.getForUpdateTable() != null
                || select.getIsolation() != null || select.getFetch() != null || select.getLimitBy() != null
                || select.getPivot() != null || select.getUnPivot() != null)
            fail(SQL_STRUCTURE_REJECTED, "不允许锁定或未支持的SELECT扩展");
    }

    private static void rejectUnsupportedPlainSelectFeatures(PlainSelect plain) {
        if (plain.getWindowDefinitions() != null && !plain.getWindowDefinitions().isEmpty()
                || plain.getPreferringClause() != null || plain.getSampleClause() != null
                || plain.getKsqlWindow() != null || plain.getBigQuerySelectQualifier() != null
                || plain.getDistinct() != null && plain.getDistinct().getOnSelectItems() != null
                || plain.getMySqlSqlCalcFoundRows())
            fail(SQL_STRUCTURE_REJECTED, "不支持命名窗口、抽样、DISTINCT ON或SQL_CALC_FOUND_ROWS");
        if (plain.getIntoTables() != null && !plain.getIntoTables().isEmpty() || plain.getIntoTempTable() != null
                || plain.getTop() != null || plain.getSkip() != null || plain.getFirst() != null
                || plain.getOracleHierarchical() != null || plain.getOracleHint() != null
                || plain.getQualify() != null || plain.getLateralViews() != null && !plain.getLateralViews().isEmpty())
            fail(SQL_STRUCTURE_REJECTED, "不允许SELECT写入或未支持的查询扩展");
    }

    /** LIMIT/OFFSET 可选（执行层统一分页），出现时必须是常量且 LIMIT 不超过上限；子查询块同样受限。 */
    private void checkPagination(Select select) {
        Limit limit = select.getLimit();
        if (limit != null) {
            if (!(limit.getRowCount() instanceof LongValue))
                fail(SQL_PAGINATION_REJECTED, "LIMIT必须为常量整数");
            long value = ((LongValue) limit.getRowCount()).getValue();
            if (value < 1 || value > maxRows)
                fail(SQL_PAGINATION_REJECTED, "LIMIT必须在1至" + maxRows + "之间");
            checkOffsetConstant(limit.getOffset());
        }
        // OFFSET 无论是否伴随 LIMIT 都校验，避免独立 OFFSET 形态绕过常量检查。
        checkOffsetConstant(select.getOffset() == null ? null : select.getOffset().getOffset());
    }

    private void checkOffsetConstant(Expression offset) {
        if (offset != null && !isNonNegativeConstant(offset))
            fail(SQL_PAGINATION_REJECTED, "OFFSET必须为非负整数");
    }

    private static boolean isNonNegativeConstant(Expression expression) {
        return expression instanceof LongValue value && value.getValue() >= 0;
    }

    // ==================== 查询块 ====================

    private List<String> analyzePlainSelect(PlainSelect plain, Scope outer,
                                            Map<String, List<String>> ctes, int depth, Run run) {
        // ① 本块特有的方言特性拒绝（命名窗口、DISTINCT ON、SELECT INTO 等）
        rejectUnsupportedPlainSelectFeatures(plain);
        // ② 登记数据源绑定；scope 的 parent 指向外层，支撑关联子查询的列解析
        Scope scope = new Scope(outer);
        bindDataSources(plain, scope, ctes, depth, run);

        // ③ 先收集本块等值约束并完成授权证明，再校验表达式：
        // 关联子查询随后校验时可以引用外层已证明的数据源。
        List<Edge> edges = new ArrayList<>();
        collectConstraints(plain.getWhere(), scope, edges, null, run);
        for (Join join : joins(plain)) {
            Binding rightSide = scope.bindings.get(sourceAlias(join.getRightItem()));
            for (Expression onCondition : join.getOnExpressions())
                collectConstraints(onCondition, scope, edges, join.isLeft() ? rightSide : null, run);
        }
        proveDataSources(scope, edges, run);

        // ④ 表达式校验：WHERE/ON 不允许引用输出列别名（与 MySQL 语义一致）
        ExprEnv env = new ExprEnv(scope, ctes, depth, false);
        checkExpression(plain.getWhere(), env, run);
        for (Join join : joins(plain))
            for (Expression onCondition : join.getOnExpressions())
                checkExpression(onCondition, env, run);

        // ⑤ 输出列名推导：别名 → 列名 → expression_N；这份列表同时是 CTE/派生表对外的"表结构"
        List<String> outputColumns = new ArrayList<>();
        for (SelectItem<?> item : plain.getSelectItems()) {
            checkExpression(item.getExpression(), env, run);
            String name = outputColumnName(item, outputColumns.size());
            if (outputColumns.contains(name))
                fail(SQL_STRUCTURE_REJECTED, "结果列名称重复，请提供唯一别名");
            outputColumns.add(name);
        }
        // 输出列别名从现在起可在 GROUP BY/HAVING/ORDER BY 中引用。
        scope.aliases.addAll(outputColumns);
        ExprEnv envAllowingAliases = new ExprEnv(scope, ctes, depth, true);
        if (plain.getGroupBy() != null) {
            if (plain.getGroupBy().getGroupingSets() != null && !plain.getGroupBy().getGroupingSets().isEmpty())
                fail(SQL_STRUCTURE_REJECTED, "暂不支持GROUPING SETS");
            checkExpression(plain.getGroupBy().getGroupByExpressionList(), envAllowingAliases, run);
        }
        checkExpression(plain.getHaving(), envAllowingAliases, run);
        checkOrderBy(plain.getOrderByElements(), scope, ctes, depth, run);
        return outputColumns;
    }

    private static String outputColumnName(SelectItem<?> item, int index) {
        if (item.getAlias() != null)
            return normalizeIdentifier(item.getAlias().getName());
        if (item.getExpression() instanceof Column column)
            return normalizeIdentifier(column.getColumnName());
        return "expression_" + index;
    }

    private void bindDataSources(PlainSelect plain, Scope scope,
                                 Map<String, List<String>> ctes, int depth, Run run) {
        bindDataSource(plain.getFromItem(), scope, ctes, depth, run);
        for (Join join : joins(plain)) {
            if (join.isRight() || join.isFull() || join.isNatural() || join.isCross() || join.isSimple()
                    || join.isApply() || join.isSemi()
                    || join.getUsingColumns() != null && !join.getUsingColumns().isEmpty())
                fail(SQL_STRUCTURE_REJECTED, "关联请使用INNER/LEFT JOIN及明确ON条件");
            bindDataSource(join.getRightItem(), scope, ctes, depth, run);
        }
    }

    /** 把一个 FROM 项登记为数据源绑定；CTE/派生表内部已递归校验，物理表做白名单校验。 */
    private void bindDataSource(FromItem from, Scope scope,
                                Map<String, List<String>> ctes, int depth, Run run) {
        if (from == null)
            return;
        String alias = sourceAlias(from);
        if (scope.bindings.containsKey(alias))
            fail(SQL_STRUCTURE_REJECTED, "同一查询块的表别名重复");
        if (from.getPivot() != null || from.getUnPivot() != null)
            fail(SQL_STRUCTURE_REJECTED, "不支持PIVOT");
        if (from instanceof Table table) {
            // 限定库名（db.table）一律拒绝，防止越出业务库
            if (table.getSchemaName() != null
                    || table.getDatabase() != null && table.getDatabase().getDatabaseName() != null)
                fail(SQL_TABLE_REJECTED, "禁止跨库或限定库名访问");
            String tableName = normalizeIdentifier(table.getName());
            if (ctes.containsKey(tableName))
                // 引用同名 CTE（CTE 定义时已禁止遮蔽业务表，这里不存在歧义）
                scope.bindings.put(alias, Binding.derived(++run.nextSourceId, ctes.get(tableName)));
            else {
                if (!SCHEMA.containsKey(tableName))
                    fail(SQL_TABLE_REJECTED, "数据对象不在白名单中");
                scope.bindings.put(alias, Binding.physical(++run.nextSourceId, tableName, SCHEMA.get(tableName)));
            }
        } else if (from instanceof ParenthesedSelect derived) {
            if (derived.getAlias() == null)
                fail(SQL_STRUCTURE_REJECTED, "派生表必须有别名");
            // 派生表体作为独立查询块校验（outer=null：FROM 里的子查询不能引用外层，即不支持 LATERAL）
            List<String> columns = analyzeSelect(derived.getSelect(), null, ctes, depth + 1, run);
            scope.bindings.put(alias, Binding.derived(++run.nextSourceId, columns));
        } else {
            fail(SQL_STRUCTURE_REJECTED, "不支持该数据源结构");
        }
    }

    /** null 安全的 JOIN 列表访问。 */
    private static List<Join> joins(PlainSelect plain) {
        return plain.getJoins() == null ? List.of() : plain.getJoins();
    }

    // ==================== 授权证明 ====================

    /**
     * 授权证明：以"等值连通"为核心的不动点推导。事实从种子出发沿等值边传播：
     * <ul>
     *   <li>账号模式（user != null）：种子是账号范围值。dim_customer 的范围列（branch_id/manager_id/
     *       region_code）连通到该值后获证，其 customer_id 随之成为"已授权客户来源"；
     *       其余物理表靠 customer_id（营销活动表为 campaign_id）连通到已授权来源获证。</li>
     *   <li>客户模式（confirmedCustomers != null）：种子是已确认客户编号，含 IN 名单证明
     *       （customer_id IN (确认名单子集)，见 {@link #collectInListProof}）。
     *       每个物理表的关键列连通到种子即获证。</li>
     * </ul>
     * 外层查询块已证明的绑定可作为本块（关联子查询）的种子。CTE/派生表在其内部查询块已完成
     * 同样的证明，此处视为已证明；但其列不作为本块的证明种子——新关联的事实表仍须在本块内
     * 与已证明来源建立等值连接。OR/NOT 分支不产生事实。
     */
    private void proveDataSources(Scope scope, List<Edge> edges, Run run) {
        if (user == null && confirmedCustomers == null)
            return; // 纯只读安全校验，无身份要求
        DataScopePolicy.Scope account = user == null ? null : DataScopePolicy.scopeOf(user);
        if (user != null && (account.value() == null || account.value().isBlank()))
            fail(ACCOUNT_SCOPE_INVALID, "账号数据范围未配置");

        // 四个事实集合是证明的"已知条件"，处理分三段：播种 → 不动点传播 → 判定。
        Set<Fact> scopeFacts = new HashSet<>();     // 可连通到账号范围值的事实
        Set<Fact> identityFacts = new HashSet<>();  // 可连通到已确认客户的事实
        Set<Fact> allowed = new HashSet<>();        // 已获证数据源的授权列
        Set<Fact> bound = new HashSet<>();          // 已获证数据源的客户绑定列

        if (account != null)
            scopeFacts.add(new ValueFact(account.value()));
        if (confirmedCustomers != null) {
            for (String customer : confirmedCustomers)
                identityFacts.add(new ValueFact(customer));
            identityFacts.addAll(run.customerListProven);
        }
        // 关联子查询的支撑：外层已证明的绑定把事实"送给"内层当种子
        for (Binding outerBinding : outerBindings(scope)) {
            if (outerBinding.scopeProven) {
                if (account != null)
                    scopeFacts.add(new ColumnFact(outerBinding, account.column()));
                allowed.add(new ColumnFact(outerBinding, CUSTOMER_ID));
                allowed.add(new ColumnFact(outerBinding, CAMPAIGN_ID));
            }
            if (outerBinding.customerProven && confirmedCustomers != null)
                bound.add(new ColumnFact(outerBinding, CUSTOMER_ID));
        }

        // 不动点：事实集合只增不减、绑定标记只翻转一次，必然收敛。多跳 JOIN 需要多轮传播
        //（如 dim_customer → 事实表 → CTE 列的两跳链），直到没有任何新事实产生为止。
        boolean changed = true;
        while (changed) {
            changed = false;
            changed |= propagate(scopeFacts, edges);
            changed |= propagate(identityFacts, edges);
            changed |= propagate(allowed, edges);
            changed |= propagate(bound, edges);
            for (Binding binding : scope.bindings.values()) {
                if (!binding.isPhysical())
                    continue; // CTE/派生表在内部查询块证明
                if (DIM_CUSTOMER.equals(binding.baseTable))
                    changed |= proveCustomerTable(binding, account, scopeFacts, allowed, identityFacts, bound);
                else
                    changed |= proveDependentTable(binding, identityFacts, allowed, bound);
            }
        }

        // 判定：不动点收敛后，任何未获证的数据源都构成拒绝——fail-closed。
        for (Map.Entry<String, Binding> entry : scope.bindings.entrySet()) {
            Binding binding = entry.getValue();
            String source = "数据源 " + (binding.isPhysical() ? binding.baseTable : "派生查询")
                    + "（别名 " + entry.getKey() + "，来源编号 " + binding.id + "）";
            if (account != null && !binding.scopeProven)
                fail(SCOPE_NOT_PROVEN, source + "缺少可证明的账号范围限制。当前账号要求 dim_customer." + account.column()
                        + " = '" + account.value() + "'；请在该查询块的WHERE中限制客户，并通过customer_id关联事实表。"
                        + "CTE或派生表的授权不会自动传递给新关联的事实表；OR/NOT中的条件不能作为授权依据。"
                        + "此SQL未执行，无需用户补充账号权限");
            if (confirmedCustomers != null && !binding.customerProven)
                fail(CUSTOMER_NOT_PROVEN, source
                        + "未保留已确认的customer_id限制；所有客户来源必须限定为已确认客户，此SQL未执行");
        }
    }

    /** dim_customer 获证方式：范围列连通账号范围值，或 customer_id 连通到任一已授权来源。 */
    private boolean proveCustomerTable(Binding binding, DataScopePolicy.Scope account,
                                       Set<Fact> scopeFacts, Set<Fact> allowed,
                                       Set<Fact> identityFacts, Set<Fact> bound) {
        boolean changed = false;
        // 账号模式获证：范围列（branch_id/manager_id/region_code 之一）连通范围值，
        // 或 customer_id 连通到任一已授权来源；获证后其 customer_id 成为新的授权来源。
        if (account != null && !binding.scopeProven
                && (scopeFacts.contains(new ColumnFact(binding, account.column()))
                        || allowed.contains(new ColumnFact(binding, CUSTOMER_ID)))) {
            binding.scopeProven = true;
            allowed.add(new ColumnFact(binding, CUSTOMER_ID));
            changed = true;
        }
        // 客户模式获证：customer_id 连通到已确认客户事实（字面量、命名参数或 IN 名单证明）
        if (confirmedCustomers != null && !binding.customerProven
                && (identityFacts.contains(new ColumnFact(binding, CUSTOMER_ID))
                        || bound.contains(new ColumnFact(binding, CUSTOMER_ID)))) {
            binding.customerProven = true;
            bound.add(new ColumnFact(binding, CUSTOMER_ID));
            changed = true;
        }
        return changed;
    }

    /**
     * 其余物理表获证方式：关键列（营销活动表用 campaign_id，其余用 customer_id）
     * 连通到已确认客户或已授权来源。fct_customer_marketing 兼具两种绑定，
     * 获证后其 campaign_id 也成为对应来源。
     */
    private boolean proveDependentTable(Binding binding, Set<Fact> identityFacts,
                                        Set<Fact> allowed, Set<Fact> bound) {
        String keyColumn = DIM_MARKETING_CAMPAIGN.equals(binding.baseTable) ? CAMPAIGN_ID : CUSTOMER_ID;
        boolean changed = false;
        if (!binding.scopeProven && allowed.contains(new ColumnFact(binding, keyColumn))) {
            binding.scopeProven = true;
            changed = true;
            // fct_customer_marketing 获证后，其 campaign_id 成为新的授权来源，
            // 借此把与它按 campaign_id 关联的 dim_marketing_campaign 一并授权。
            if (FCT_CUSTOMER_MARKETING.equals(binding.baseTable))
                allowed.add(new ColumnFact(binding, CAMPAIGN_ID));
        }
        if (!binding.customerProven
                && (identityFacts.contains(new ColumnFact(binding, keyColumn))
                        || bound.contains(new ColumnFact(binding, keyColumn)))) {
            binding.customerProven = true;
            changed = true;
            if (FCT_CUSTOMER_MARKETING.equals(binding.baseTable))
                bound.add(new ColumnFact(binding, CAMPAIGN_ID));
        }
        return changed;
    }

    /** 单轮沿等值边传播事实，返回是否有新增；外层循环直到不动点。 */
    private static boolean propagate(Set<Fact> facts, List<Edge> edges) {
        boolean changed = false;
        for (Edge edge : edges)
            if (facts.contains(edge.from()))
                changed |= facts.add(edge.to());
        return changed;
    }

    /** 收集外层作用域链上的全部绑定，供内层（关联子查询）证明时播种。 */
    private static List<Binding> outerBindings(Scope scope) {
        List<Binding> outer = new ArrayList<>();
        for (Scope current = scope.parent; current != null; current = current.parent)
            outer.addAll(current.bindings.values());
        return outer;
    }

    // ==================== 等值约束收集 ====================

    /**
     * 从 WHERE/ON 的 AND 链中提取等值约束（列-列、列-字面量、列-命名参数）。
     * OR/NOT 分支不作为授权依据，但本身合法——块内另有独立 AND 约束即可完成证明。
     * nullableSide 为 LEFT JOIN 的右表：ON 条件只约束被补全的一侧，事实只流向该侧。
     */
    private void collectConstraints(Expression condition, Scope scope, List<Edge> edges,
                                    Binding nullableSide, Run run) {
        if (condition == null)
            return;
        if (condition instanceof ParenthesedExpressionList<?> grouped && grouped.size() == 1) {
            collectConstraints(grouped.get(0), scope, edges, nullableSide, run);
            return;
        }
        if (condition instanceof AndExpression and) {
            collectConstraints(and.getLeftExpression(), scope, edges, nullableSide, run);
            collectConstraints(and.getRightExpression(), scope, edges, nullableSide, run);
            return;
        }
        if (condition instanceof InExpression in) {
            collectInListProof(in, scope, run);
            return;
        }
        if (!(condition instanceof EqualsTo equality))
            return; // 其它运算符（>、LIKE…）不构成等值约束
        Fact left = factOf(equality.getLeftExpression(), scope);
        Fact right = factOf(equality.getRightExpression(), scope);
        if (left == null || right == null)
            return; // 一侧是函数/算术等无法静态核对的形态，整条不出边
        if (nullableSide == null) {
            // WHERE / INNER JOIN 的 ON：等值是双向约束
            edges.add(new Edge(left, right));
            edges.add(new Edge(right, left));
            return;
        }
        // LEFT JOIN 的 ON 只约束右表。例如
        //   dim_customer c LEFT JOIN fct_transaction t ON c.manager_id='M0001' AND c.customer_id=t.customer_id
        // 范围条件一侧不是 t 的列、不出边（约束不到 c）；等值边只从 c.customer_id 指向 t.customer_id。
        if (right instanceof ColumnFact columnFact && columnFact.binding() == nullableSide)
            edges.add(new Edge(left, right));
        if (left instanceof ColumnFact columnFact && columnFact.binding() == nullableSide)
            edges.add(new Edge(right, left));
    }

    /**
     * 名单约束证明（@客户名单批量查询）：customer_id IN (字符串字面量集合)，
     * 且集合成员全部落在服务端核验的确认名单内时，该数据源的 customer_id 视同已被确认客户约束。
     * 列表必须全部是字符串字面量——出现数字、命名参数或子查询时无法与确认名单完整核对，
     * 整体不作为证明。
     */
    private void collectInListProof(InExpression in, Scope scope, Run run) {
        // 以下任一条件不满足即保持沉默（不证明也不报错，授权交由本块其余约束完成）：
        if (confirmedCustomers == null)
            return;                                   // 非客户模式，没有"确认名单"可核对
        if (!(in.getLeftExpression() instanceof Column column))
            return;                                   // 左侧必须是列
        if (!CUSTOMER_ID.equals(normalizeIdentifier(column.getColumnName())))
            return;                                   // 必须是 customer_id
        if (column.getTable() == null || column.getTable().getName() == null)
            return;                                   // 必须带表限定，才能定位到具体数据源
        Binding binding = scope.bindings.get(normalizeIdentifier(column.getTable().getName()));
        if (binding == null)
            return;                                   // 限定名不是本块的数据源
        List<String> literals = new ArrayList<>();
        if (!collectStringLiterals(in.getRightExpression(), literals))
            return;
        if (literals.isEmpty() || !confirmedCustomers.containsAll(literals))
            return;
        run.customerListProven.add(new ColumnFact(binding, CUSTOMER_ID));
    }

    /** 收集 IN 列表中的字符串字面量；任一元素不是字符串字面量即返回 false。 */
    private static boolean collectStringLiterals(Expression expression, List<String> out) {
        if (expression instanceof StringValue value) {
            out.add(value.getValue());
            return true;
        }
        if (expression instanceof ExpressionList<?> list) {
            for (Object element : list)
                if (!(element instanceof Expression child) || !collectStringLiterals(child, out))
                    return false;
            return true;
        }
        return false;
    }

    /** 等值一侧的事实：列引用解析为 ColumnFact；字符串字面量与已知命名参数解析为 ValueFact；其余无法核对。 */
    private Fact factOf(Expression expression, Scope scope) {
        if (expression instanceof Column column)
            return resolveColumn(column, scope);
        if (expression instanceof StringValue value)
            return new ValueFact(value.getValue());
        if (expression instanceof JdbcNamedParameter parameter && parameters.containsKey(parameter.getName()))
            return new ValueFact(String.valueOf(parameters.get(parameter.getName())));
        return null;
    }

    /**
     * 解析列引用：从当前查询块向外层逐块查找（关联引用合法）。
     * 限定别名时必须命中且列存在；未限定时要求在所在块内无歧义。
     */
    private ColumnFact resolveColumn(Column column, Scope scope) {
        String name = normalizeIdentifier(column.getColumnName());
        if (column.getTable() != null && column.getTable().getSchemaName() != null)
            fail(SQL_TABLE_REJECTED, "禁止跨库字段引用");
        if (column.getTable() != null && column.getTable().getName() != null) {
            // 带限定：命中别名即检查列存在，列不在直接拒绝、不再向外层找（与 SQL 解析语义一致）
            String qualifier = normalizeIdentifier(column.getTable().getName());
            for (Scope current = scope; current != null; current = current.parent) {
                Binding binding = current.bindings.get(qualifier);
                if (binding != null) {
                    if (!binding.columns.contains(name))
                        fail(SQL_COLUMN_REJECTED, "字段不存在或不允许查询：" + name);
                    return new ColumnFact(binding, name);
                }
            }
        } else {
            // 不带限定：逐层统计含此列的绑定——歧义即拒绝（逼迫 SQL 写清来源，授权证明才无歧义可钻），
            // 恰有一个即成功，零个则继续向外层（合法的关联引用）
            for (Scope current = scope; current != null; current = current.parent) {
                List<Binding> matches = current.bindings.values().stream()
                        .filter(binding -> binding.columns.contains(name)).toList();
                if (matches.size() > 1)
                    fail(SQL_COLUMN_REJECTED, "字段含义不明确，请使用表别名：" + name);
                if (matches.size() == 1)
                    return new ColumnFact(matches.get(0), name);
            }
        }
        fail(SQL_COLUMN_REJECTED, "字段或表别名不存在：" + name);
        return null;
    }

    // ==================== 表达式校验 ====================

    /** ORDER BY 出现的所有位置（查询块、聚合、窗口内部）都允许引用输出列别名。 */
    private void checkOrderBy(List<OrderByElement> orderBy, Scope scope,
                              Map<String, List<String>> ctes, int depth, Run run) {
        if (orderBy == null)
            return;
        ExprEnv env = new ExprEnv(scope, ctes, depth, true);
        for (OrderByElement element : orderBy)
            checkExpression(element.getExpression(), env, run);
    }

    /**
     * 表达式白名单校验。覆盖显式列出的全部形态；未列出的类型落入兜底分支直接拒绝，
     * 保证新增的 JSqlParser 表达式不会静默通过。
     */
    private void checkExpression(Expression expression, ExprEnv env, Run run) {
        if (expression == null)
            return;
        if (++run.nodes > MAX_EXPRESSION_NODES)
            fail(SQL_STRUCTURE_REJECTED, "SQL表达式过多");
        // 字面量与命名参数
        if (expression instanceof LongValue || expression instanceof DoubleValue
                || expression instanceof StringValue || expression instanceof NullValue
                || expression instanceof DateValue || expression instanceof TimeValue
                || expression instanceof TimestampValue || expression instanceof DateTimeLiteralExpression
                || expression instanceof JdbcNamedParameter)
            return;
        if (expression instanceof Column column) {
            checkColumn(column, env);
            return;
        }
        if (expression instanceof BinaryExpression binary) {
            checkBinary(binary, env, run);
            return;
        }
        if (expression instanceof ExpressionList<?> list) {
            // 括号组、函数参数、IN 列表等统一按列表递归
            for (Expression child : list)
                checkExpression(child, env, run);
            return;
        }
        if (expression instanceof ParenthesedSelect subquery) {
            // 表达式位置的子查询（标量/EXISTS/IN 内部）作为完整查询块递归校验；
            // env.scope() 作为外层传入，关联子查询由此引用外层表
            analyzeSelect(subquery.getSelect(), env.scope(), env.ctes(), env.depth() + 1, run);
            return;
        }
        // 窗口函数是 Function 的子类，必须先于 Function 判断。
        if (expression instanceof AnalyticExpression analytic) {
            checkAnalytic(analytic, env, run);
            return;
        }
        if (expression instanceof Function function) {
            checkFunction(function, env, run);
            return;
        }
        if (expression instanceof CaseExpression caseExpression) {
            checkExpression(caseExpression.getSwitchExpression(), env, run);
            if (caseExpression.getWhenClauses() != null)
                for (WhenClause whenClause : caseExpression.getWhenClauses())
                    checkExpression(whenClause, env, run);
            checkExpression(caseExpression.getElseExpression(), env, run);
            return;
        }
        if (expression instanceof WhenClause whenClause) {
            checkExpression(whenClause.getWhenExpression(), env, run);
            checkExpression(whenClause.getThenExpression(), env, run);
            return;
        }
        if (expression instanceof Between between) {
            checkExpression(between.getLeftExpression(), env, run);
            checkExpression(between.getBetweenExpressionStart(), env, run);
            checkExpression(between.getBetweenExpressionEnd(), env, run);
            return;
        }
        if (expression instanceof InExpression in) {
            checkExpression(in.getLeftExpression(), env, run);
            checkExpression(in.getRightExpression(), env, run);
            return;
        }
        if (expression instanceof ExistsExpression exists) {
            checkExpression(exists.getRightExpression(), env, run);
            return;
        }
        if (expression instanceof IsNullExpression isNull) {
            checkExpression(isNull.getLeftExpression(), env, run);
            return;
        }
        if (expression instanceof NotExpression not) {
            checkExpression(not.getExpression(), env, run);
            return;
        }
        if (expression instanceof SignedExpression signed) {
            checkExpression(signed.getExpression(), env, run);
            return;
        }
        if (expression instanceof CastExpression cast) {
            checkCast(cast, env, run);
            return;
        }
        if (expression instanceof ExtractExpression extract) {
            checkExpression(extract.getExpression(), env, run);
            return;
        }
        if (expression instanceof IntervalExpression interval) {
            checkExpression(interval.getExpression(), env, run);
            return;
        }
        // 兜底：未显式接受的类型一律拒绝，JSqlParser 升级新增的表达式类型也落在这里。
        fail(SQL_STRUCTURE_REJECTED, "不支持的SQL表达式：" + expression.getClass().getSimpleName());
    }

    private void checkColumn(Column column, ExprEnv env) {
        String name = normalizeIdentifier(column.getColumnName());
        boolean qualified = column.getTable() != null && column.getTable().getName() != null;
        if (!qualified && ("true".equals(name) || "false".equals(name)))
            return; // 无表限定的布尔字面量
        if (!qualified && env.aliasesAllowed() && env.scope().aliases.contains(name))
            return; // GROUP BY/HAVING/ORDER BY 中引用本块输出列别名
        resolveColumn(column, env.scope());
    }

    private void checkBinary(BinaryExpression binary, ExprEnv env, Run run) {
        if (!BINARY_OPERATORS.contains(binary.getClass().getSimpleName()))
            fail(SQL_STRUCTURE_REJECTED, "表达式运算符未支持");
        checkExpression(binary.getLeftExpression(), env, run);
        checkExpression(binary.getRightExpression(), env, run);
    }

    private void checkFunction(Function function, ExprEnv env, Run run) {
        String name = normalizeIdentifier(function.getName());
        if (!FUNCTIONS.contains(name) || function.getAttribute() != null || function.getKeep() != null
                || function.getNamedParameters() != null)
            fail(SQL_STRUCTURE_REJECTED, "函数不在允许清单中");
        if (function.getParameters() != null)
            for (Expression argument : function.getParameters()) {
                if (argument instanceof AllColumns && "count".equals(name))
                    continue; // COUNT(*)
                checkExpression(argument, env, run);
            }
        checkOrderBy(function.getOrderByElements(), env.scope(), env.ctes(), env.depth(), run);
    }

    private void checkAnalytic(AnalyticExpression analytic, ExprEnv env, Run run) {
        String name = normalizeIdentifier(analytic.getName());
        if (!FUNCTIONS.contains(name) || analytic.getWindowName() != null || analytic.getKeep() != null
                || analytic.getFilterExpression() != null)
            fail(SQL_STRUCTURE_REJECTED, "窗口函数结构未支持");
        checkExpression(analytic.getExpression(), env, run);
        checkExpression(analytic.getOffset(), env, run);
        checkExpression(analytic.getDefaultValue(), env, run);
        checkExpression(analytic.getPartitionExpressionList(), env, run);
        checkOrderBy(analytic.getOrderByElements(), env.scope(), env.ctes(), env.depth(), run);
        WindowElement window = analytic.getWindowElement();
        if (window == null)
            return;
        if (window.getOffset() != null)
            checkExpression(window.getOffset().getExpression(), env, run);
        if (window.getRange() != null) {
            if (window.getRange().getStart() != null)
                checkExpression(window.getRange().getStart().getExpression(), env, run);
            if (window.getRange().getEnd() != null)
                checkExpression(window.getRange().getEnd().getExpression(), env, run);
        }
    }

    private void checkCast(CastExpression cast, ExprEnv env, Run run) {
        if (cast.getColDataType() == null
                || !CAST_TYPES.contains(cast.getColDataType().getDataType().toUpperCase(Locale.ROOT)))
            fail(SQL_STRUCTURE_REJECTED, "CAST类型未支持");
        checkExpression(cast.getLeftExpression(), env, run);
    }

    // ==================== 通用工具 ====================

    /** 标识符规范化：去反引号、转小写；只允许常规英文标识符。 */
    private static String normalizeIdentifier(String name) {
        if (name == null)
            return "";
        String normalized = name.replace("`", "").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z_][a-z0-9_]*"))
            fail(SQL_STRUCTURE_REJECTED, "标识符必须为英文列名或别名");
        return normalized;
    }

    private static String sourceAlias(FromItem from) {
        if (from.getAlias() != null)
            return normalizeIdentifier(from.getAlias().getName());
        if (from instanceof Table table)
            return normalizeIdentifier(table.getName());
        fail(SQL_STRUCTURE_REJECTED, "数据源需要别名");
        return "";
    }

    private static void fail(int code, String message) {
        throw new BusinessException(code, "SQL校验未通过：" + message);
    }

    // ==================== 内部结构 ====================

    /** 一次 validate 调用的共享状态：来源编号、表达式节点预算、IN 名单证明。 */
    private static final class Run {
        int nextSourceId;
        int nodes;
        final Set<Fact> customerListProven = new HashSet<>();
    }

    /** 查询块作用域：自身绑定 + 外层作用域链；aliases 为本块输出列别名（GROUP BY/HAVING/ORDER BY 可引用）。 */
    private static final class Scope {
        final Scope parent;
        final Map<String, Binding> bindings = new LinkedHashMap<>();
        final Set<String> aliases = new HashSet<>();

        Scope(Scope parent) {
            this.parent = parent;
        }
    }

    /** 一个数据源绑定。baseTable 为 null 表示 CTE 引用或派生表，其在内部查询块已完成证明。 */
    private static final class Binding {
        final int id;                  // 来源编号，报错文案用它在多个数据源间定位
        final String baseTable;        // 物理表名；null = CTE 引用或派生表
        final Set<String> columns;     // 可引用列（物理表白名单列，或派生表输出列）
        boolean scopeProven;           // 账号模式：已证明被账号数据范围约束
        boolean customerProven;        // 客户模式：已证明被已确认客户约束

        private Binding(int id, String baseTable, Set<String> columns, boolean preProven) {
            this.id = id;
            this.baseTable = baseTable;
            this.columns = columns;
            this.scopeProven = preProven;
            this.customerProven = preProven;
        }

        static Binding physical(int id, String baseTable, Set<String> columns) {
            return new Binding(id, baseTable, columns, false);
        }

        static Binding derived(int id, java.util.Collection<String> columns) {
            return new Binding(id, null, new LinkedHashSet<>(columns), true);
        }

        boolean isPhysical() {
            return baseTable != null;
        }
    }

    /** 表达式校验环境：所属作用域、可见 CTE、嵌套深度、是否允许引用输出列别名。 */
    private record ExprEnv(Scope scope, Map<String, List<String>> ctes, int depth, boolean aliasesAllowed) {
    }

    /** 授权证明中的事实单元：某数据源的某列，或一个具体取值（字面量/命名参数解析值）。 */
    private sealed interface Fact permits ColumnFact, ValueFact {
    }

    /** 事实一：某个数据源的某个列。 */
    private record ColumnFact(Binding binding, String column) implements Fact {
    }

    /** 事实二：一个具体取值（字符串字面量，或命名参数在服务端参数表中的解析值）。 */
    private record ValueFact(String value) implements Fact {
    }

    /** 有向事实边：from 上的事实可传播到 to（LEFT JOIN 的单侧约束靠方向性实现）。 */
    private record Edge(Fact from, Fact to) {
    }
}

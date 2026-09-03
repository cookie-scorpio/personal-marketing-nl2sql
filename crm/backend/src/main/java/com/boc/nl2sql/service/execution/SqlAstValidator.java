package com.boc.nl2sql.service.execution;

import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.domain.authorization.CurrentUser;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.ExceptOp;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.IntersectOp;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.UnionOp;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成 SQL 的白名单 AST 校验器，是模型产物进入执行层前的最后闸门。
 *
 * <p>三层防线（各自有独立的协作类，本类只做入口与查询块编排）：</p>
 * <ol>
 *   <li>词法预检 {@link SqlLexicalGate}：拒绝注释、多语句、会话变量、反斜杠转义等
 *       无法可靠静态分析的文本形态；</li>
 *   <li>结构与白名单：仅接受受控的 SELECT 形态，表/列/函数/运算符/CAST 类型全部走
 *       {@link SqlGuardPolicy} 白名单，未知结构一律拒绝（绝不静默放行）；</li>
 *   <li>授权证明 {@link SqlScopeProver}：按查询块构建等值约束图（{@link SqlConstraintCollector}），
 *       证明每个物理数据源被账号数据范围或已确认客户约束。证明不了的直接拒绝，
 *       不把 SQL 里碰巧出现的范围字符串当作授权依据。</li>
 * </ol>
 *
 * <p>为什么第三层需要"证明"：SQL 由模型生成，提示词与数据库权限都挡不住"范围条件看起来存在、
 * 语义上不生效"的写法——OR 分支中的条件、LEFT JOIN 的 ON 只约束右表、约束落在子查询里等。
 * 这套推导是"等值连接传递约束、约束多跳传播、OR/LEFT-JOIN-ON 不传播、传播覆盖所有物理表"
 * 四条 SQL 语义规则的最小形式化，不是可选的复杂度。</p>
 *
 * <p>推荐阅读顺序：{@link #validate} → {@link #analyzePlainSelect}（单个查询块的完整流程）
 * → {@link SqlScopeProver}（授权证明核心）。设计背景、行为规格与常见疑问详见
 * docs/SqlAstValidator设计说明.md 与 docs/权限校验详解.md。</p>
 *
 * <p>配置（白名单与限额）由 {@link SqlGuardPolicy} 提供，实例其余状态全部 final；
 * 一次 validate 的可变状态集中在校验开始时创建的 {@link Run} 里，
 * 因此实例线程安全、可复用；调用方按 new SqlAstValidator(...).validate(sql) 使用即可。</p>
 */
public final class SqlAstValidator {
    private final CurrentUser user;
    private final Map<String, Object> parameters;
    /**
     * 服务端核验过的已确认客户编号。null 表示本次校验不要求客户绑定证明；
     * 空集合视为 null（没有任何已确认客户时无从证明，交由调用方语义决定）。
     */
    private final Set<String> confirmedCustomers;
    private final int maxRows;
    private final SqlGuardPolicy policy;
    /** 表达式位置的子查询作为完整查询块递归进入本校验器的入口。 */
    private final SqlSubqueryAnalyzer subqueries = this::analyzeSelect;

    public SqlAstValidator(CurrentUser user, Map<String, Object> parameters,
                           java.util.Collection<String> confirmedCustomers, int maxRows) {
        this(user, parameters, confirmedCustomers, maxRows, SqlGuardPolicy.standard());
    }

    SqlAstValidator(CurrentUser user, Map<String, Object> parameters,
                    java.util.Collection<String> confirmedCustomers, int maxRows, SqlGuardPolicy policy) {
        this.user = user;
        this.parameters = parameters == null ? Map.of() : parameters;
        this.confirmedCustomers = confirmedCustomers == null || confirmedCustomers.isEmpty()
                ? null
                : Set.copyOf(confirmedCustomers);
        this.maxRows = maxRows;
        this.policy = policy;
    }

    // ==================== 入口 ====================

    public void validate(String sql) {
        // 第一道：词法预检。必须在解析之前——注释、反斜杠转义这类形态会被解析器静默吞掉，
        // 或与 MySQL 的解释不一致，进了 AST 就再也看不见了（设计文档 3.1 节）。
        SqlLexicalGate.check(sql, policy);
        Run run = new Run();
        try {
            // 第二道：解析 + 结构与白名单校验。parseStatements 会解析出多条语句，
            // 必须断言"恰好一条且是 SELECT"；超时防构造输入拖死解析器。
            var statements = CCJSqlParserUtil.parseStatements(
                    sql, parser -> parser.withTimeOut(policy.parseTimeoutMillis()));
            if (statements.size() != 1 || !(statements.get(0) instanceof Select))
                SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("仅允许单条只读SELECT");
            // 第三道：授权证明在 analyzeSelect 递归内部的每个查询块上执行。
            analyzeSelect((Select) statements.get(0), null, new LinkedHashMap<>(), 0, run);
        } catch (BusinessException rejected) {
            throw rejected;
        } catch (Exception parseFailure) {
            // 不携带原始异常信息，避免泄露解析器内部细节
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("SQL语法无法解析或尚未支持");
        }
    }

    // ==================== SELECT 结构 ====================

    /**
     * 递归校验一个 SELECT 节点，返回其输出列名——它是 CTE、派生表和 UNION 对外的"表结构"。
     * outer 为所属外层查询块的作用域（关联子查询由此引用外层），CTE 自身为 null。
     */
    private List<String> analyzeSelect(Select select, Scope outer, Map<String, List<String>> inheritedCtes,
                                       int depth, Run run) {
        if (depth > policy.maxQueryDepth() || ++run.nodes > policy.maxExpressionNodes())
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("SQL结构超过复杂度限制");
        rejectUnsupportedSelectFeatures(select);
        checkPagination(select);
        Map<String, List<String>> ctes = declareCtes(select, inheritedCtes, depth, run);

        if (select instanceof ParenthesedSelect parenthesized)
            return analyzeSelect(parenthesized.getSelect(), outer, ctes, depth + 1, run);
        if (select instanceof SetOperationList setOperation)
            return analyzeSetOperation(setOperation, outer, ctes, depth, run);
        if (select instanceof PlainSelect plain)
            return analyzePlainSelect(plain, outer, ctes, depth, run);
        SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("仅允许单条只读SELECT");
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
                SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("仅支持非递归SELECT CTE，列名请在SELECT中显式声明");
            String name = SqlIdentifiers.normalizeIdentifier(item.getAliasName());
            if (ctes.containsKey(name) || policy.isTableAllowed(name))
                SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("CTE名称重复或遮蔽业务表");
            ctes.put(name, analyzeSelect(item.getSelect(), null, ctes, depth + 1, run));
        }
        return ctes;
    }

    private List<String> analyzeSetOperation(SetOperationList setOperation, Scope outer,
                                             Map<String, List<String>> ctes, int depth, Run run) {
        // MySQL 8.0.31+ 支持 UNION/UNION ALL/INTERSECT/EXCEPT；MINUS（Oracle 方言）等仍拒绝。
        if (setOperation.getOperations().stream().anyMatch(operation -> !(operation instanceof UnionOp
                || operation instanceof IntersectOp || operation instanceof ExceptOp)))
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("集合运算仅支持UNION、UNION ALL、INTERSECT与EXCEPT");
        List<String> columns = null;
        for (Select branch : setOperation.getSelects()) {
            List<String> branchColumns = analyzeSelect(branch, outer, ctes, depth + 1, run);
            if (columns != null && columns.size() != branchColumns.size())
                SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("集合运算各分支列数不一致");
            if (columns == null)
                columns = branchColumns;
        }
        // 集合层 ORDER BY 只能引用分支的输出列。
        Scope outputScope = new Scope(null);
        outputScope.aliases.addAll(columns == null ? List.of() : columns);
        new SqlExpressionChecker(outputScope, ctes, depth, policy, run, subqueries)
                .checkOrderBy(setOperation.getOrderByElements());
        return columns;
    }

    private static void rejectUnsupportedSelectFeatures(Select select) {
        if (select.getForMode() != null || select.getForClause() != null || select.getForUpdateTable() != null
                || select.getIsolation() != null || select.getFetch() != null || select.getLimitBy() != null
                || select.getPivot() != null || select.getUnPivot() != null)
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("不允许锁定或未支持的SELECT扩展");
    }

    private static void rejectUnsupportedPlainSelectFeatures(PlainSelect plain) {
        if (plain.getPreferringClause() != null || plain.getSampleClause() != null
                || plain.getKsqlWindow() != null || plain.getBigQuerySelectQualifier() != null
                || plain.getDistinct() != null && plain.getDistinct().getOnSelectItems() != null
                || plain.getMySqlSqlCalcFoundRows())
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("不支持抽样、DISTINCT ON或SQL_CALC_FOUND_ROWS");
        if (plain.getIntoTables() != null && !plain.getIntoTables().isEmpty() || plain.getIntoTempTable() != null
                || plain.getTop() != null || plain.getSkip() != null || plain.getFirst() != null
                || plain.getOracleHierarchical() != null || plain.getOracleHint() != null
                || plain.getQualify() != null || plain.getLateralViews() != null && !plain.getLateralViews().isEmpty())
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("不允许SELECT写入或未支持的查询扩展");
    }

    /** LIMIT/OFFSET 可选（执行层统一分页），出现时必须是常量且 LIMIT 不超过上限；子查询块同样受限。 */
    private void checkPagination(Select select) {
        Limit limit = select.getLimit();
        if (limit != null) {
            if (!(limit.getRowCount() instanceof LongValue))
                SqlErrorCode.SQL_PAGINATION_REJECTED.fail("LIMIT必须为常量整数");
            long value = ((LongValue) limit.getRowCount()).getValue();
            if (value < 1 || value > maxRows)
                SqlErrorCode.SQL_PAGINATION_REJECTED.fail("LIMIT必须在1至" + maxRows + "之间");
            checkOffsetConstant(limit.getOffset());
        }
        // OFFSET 无论是否伴随 LIMIT 都校验，避免独立 OFFSET 形态绕过常量检查。
        checkOffsetConstant(select.getOffset() == null ? null : select.getOffset().getOffset());
    }

    private void checkOffsetConstant(Expression offset) {
        if (offset != null && !isNonNegativeConstant(offset))
            SqlErrorCode.SQL_PAGINATION_REJECTED.fail("OFFSET必须为非负整数");
    }

    private static boolean isNonNegativeConstant(Expression expression) {
        return expression instanceof LongValue value && value.getValue() >= 0;
    }

    // ==================== 查询块 ====================

    private List<String> analyzePlainSelect(PlainSelect plain, Scope outer,
                                            Map<String, List<String>> ctes, int depth, Run run) {
        // ① 本块特有的方言特性拒绝（抽样、DISTINCT ON、SELECT INTO 等）
        rejectUnsupportedPlainSelectFeatures(plain);
        // ② 登记数据源绑定；scope 的 parent 指向外层，支撑关联子查询的列解析
        Scope scope = new Scope(outer);
        bindDataSources(plain, scope, ctes, depth, run);
        // 命名窗口定义先于表达式校验：OVER w 的引用检查依赖本块已登记的窗口名
        SqlExpressionChecker checker = new SqlExpressionChecker(scope, ctes, depth, policy, run, subqueries);
        checker.validateWindowDefinitions(plain);

        // ③ 先收集本块等值约束并完成授权证明，再校验表达式：
        // 关联子查询随后校验时可以引用外层已证明的数据源。
        List<Edge> edges = new SqlConstraintCollector(scope, parameters, confirmedCustomers, run)
                .collectFrom(plain);
        new SqlScopeProver(scope, edges, user, confirmedCustomers, run).prove();

        // ④ 表达式校验：WHERE/ON 不允许引用输出列别名（与 MySQL 语义一致）
        checker.checkWhereAndJoins(plain);

        // ⑤ 输出列名推导：别名 → 列名 → expression_N；这份列表同时是 CTE/派生表对外的"表结构"
        List<String> outputColumns = new ArrayList<>();
        for (SelectItem<?> item : plain.getSelectItems()) {
            checker.check(item.getExpression(), false);
            String name = outputColumnName(item, outputColumns.size());
            if (outputColumns.contains(name))
                SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("结果列名称重复，请提供唯一别名");
            outputColumns.add(name);
        }
        // 输出列别名从现在起可在 GROUP BY/HAVING/ORDER BY 中引用。
        scope.aliases.addAll(outputColumns);
        if (plain.getGroupBy() != null) {
            if (plain.getGroupBy().getGroupingSets() != null && !plain.getGroupBy().getGroupingSets().isEmpty())
                SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("暂不支持GROUPING SETS");
            checker.check(plain.getGroupBy().getGroupByExpressionList(), true);
        }
        checker.check(plain.getHaving(), true);
        checker.checkOrderBy(plain.getOrderByElements());
        return outputColumns;
    }

    private static String outputColumnName(SelectItem<?> item, int index) {
        if (item.getAlias() != null)
            return SqlIdentifiers.normalizeIdentifier(item.getAlias().getName());
        if (item.getExpression() instanceof Column column)
            return SqlIdentifiers.normalizeIdentifier(column.getColumnName());
        return "expression_" + index;
    }

    private void bindDataSources(PlainSelect plain, Scope scope,
                                 Map<String, List<String>> ctes, int depth, Run run) {
        bindDataSource(plain.getFromItem(), scope, ctes, depth, run);
        for (Join join : joins(plain)) {
            if (join.isRight() || join.isFull() || join.isNatural() || join.isCross() || join.isSimple()
                    || join.isApply() || join.isSemi()
                    || join.getUsingColumns() != null && !join.getUsingColumns().isEmpty())
                SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("关联请使用INNER/LEFT JOIN及明确ON条件");
            bindDataSource(join.getRightItem(), scope, ctes, depth, run);
        }
    }

    /** 把一个 FROM 项登记为数据源绑定；CTE/派生表内部已递归校验，物理表做白名单校验。 */
    private void bindDataSource(FromItem from, Scope scope,
                                Map<String, List<String>> ctes, int depth, Run run) {
        if (from == null)
            return;
        String alias = SqlIdentifiers.sourceAlias(from);
        if (scope.bindings.containsKey(alias))
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("同一查询块的表别名重复");
        if (from.getPivot() != null || from.getUnPivot() != null)
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("不支持PIVOT");
        if (from instanceof Table table) {
            // 限定库名（db.table）一律拒绝，防止越出业务库
            if (table.getSchemaName() != null
                    || table.getDatabase() != null && table.getDatabase().getDatabaseName() != null)
                SqlErrorCode.SQL_TABLE_REJECTED.fail("禁止跨库或限定库名访问");
            String tableName = SqlIdentifiers.normalizeIdentifier(table.getName());
            if (ctes.containsKey(tableName))
                // 引用同名 CTE（CTE 定义时已禁止遮蔽业务表，这里不存在歧义）
                scope.bindings.put(alias, Binding.derived(++run.nextSourceId, ctes.get(tableName)));
            else {
                if (!policy.isTableAllowed(tableName))
                    SqlErrorCode.SQL_TABLE_REJECTED.fail("数据对象不在白名单中");
                scope.bindings.put(alias,
                        Binding.physical(++run.nextSourceId, tableName, policy.columnsOf(tableName)));
            }
        } else if (from instanceof ParenthesedSelect derived) {
            if (derived.getAlias() == null)
                SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("派生表必须有别名");
            // 派生表体作为独立查询块校验（outer=null：FROM 里的子查询不能引用外层，即不支持 LATERAL）
            List<String> columns = analyzeSelect(derived.getSelect(), null, ctes, depth + 1, run);
            scope.bindings.put(alias, Binding.derived(++run.nextSourceId, columns));
        } else {
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("不支持该数据源结构");
        }
    }

    private static List<Join> joins(PlainSelect plain) {
        return plain.getJoins() == null ? List.of() : plain.getJoins();
    }
}

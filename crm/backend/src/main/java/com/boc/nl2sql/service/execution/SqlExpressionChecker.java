package com.boc.nl2sql.service.execution;

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
import net.sf.jsqlparser.expression.TimeKeyExpression;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.WindowElement;
import net.sf.jsqlparser.expression.WindowDefinition;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表达式白名单校验：覆盖显式列出的全部形态；未列出的类型落入兜底分支直接拒绝，
 * 保证新增的 JSqlParser 表达式不会静默通过。一个实例对应一个查询块。
 */
final class SqlExpressionChecker {
    private final Scope scope;                     // 所属查询块作用域（OVER w / 输出列别名 / 绑定都在这里）
    private final Map<String, List<String>> ctes;  // 本块可见的 CTE
    private final int depth;                       // 查询块嵌套深度
    private final SqlGuardPolicy policy;
    private final Run run;
    private final SqlSubqueryAnalyzer subqueries;  // 表达式位置子查询的递归入口

    SqlExpressionChecker(Scope scope, Map<String, List<String>> ctes, int depth,
                         SqlGuardPolicy policy, Run run, SqlSubqueryAnalyzer subqueries) {
        this.scope = scope;
        this.ctes = ctes;
        this.depth = depth;
        this.policy = policy;
        this.run = run;
        this.subqueries = subqueries;
    }

    /** 校验 WINDOW 子句的命名窗口定义：名称本块唯一，PARTITION BY / ORDER BY / 帧内表达式全走白名单。 */
    void validateWindowDefinitions(PlainSelect plain) {
        if (plain.getWindowDefinitions() == null)
            return;
        for (WindowDefinition definition : plain.getWindowDefinitions()) {
            String name = SqlIdentifiers.normalizeIdentifier(definition.getWindowName());
            if (!scope.windowNames.add(name))
                SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("命名窗口名称重复：" + name);
            check(definition.getPartitionExpressionList(), false);
            checkOrderBy(definition.getOrderByElements());
            checkWindowElement(definition.getWindowElement(), false);
        }
    }

    /** 校验 WHERE 与全部 JOIN 的 ON（不允许引用输出列别名，与 MySQL 语义一致）。 */
    void checkWhereAndJoins(PlainSelect plain) {
        check(plain.getWhere(), false);
        if (plain.getJoins() != null)
            for (Join join : plain.getJoins())
                for (Expression onCondition : join.getOnExpressions())
                    check(onCondition, false);
    }

    /** 校验单个表达式（SELECT 项 / GROUP BY / HAVING 按子句语义选择别名开关）。 */
    void check(Expression expression, boolean aliasesAllowed) {
        if (expression == null)
            return;
        if (++run.nodes > 3000)
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("SQL表达式过多");
        // 节点预算只限总工作量，限不住递归深度——扁平 AND/OR 长链的树深远大于节点均摊，
        // 必须单独限定递归深度，在 StackOverflowError 发生之前干净拒绝。
        if (++run.expressionDepth > policy.maxExpressionDepth())
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("SQL表达式嵌套过深");
        try {
            dispatch(expression, aliasesAllowed);
        } finally {
            run.expressionDepth--;
        }
    }

    /** ORDER BY 出现的所有位置（查询块、聚合、窗口内部）都允许引用输出列别名。 */
    void checkOrderBy(List<OrderByElement> orderBy) {
        if (orderBy == null)
            return;
        for (OrderByElement element : orderBy)
            check(element.getExpression(), true);
    }

    private void dispatch(Expression expression, boolean aliasesAllowed) {
        // 字面量与命名参数
        if (expression instanceof LongValue || expression instanceof DoubleValue
                || expression instanceof StringValue || expression instanceof NullValue
                || expression instanceof DateValue || expression instanceof TimeValue
                || expression instanceof TimestampValue || expression instanceof DateTimeLiteralExpression
                || expression instanceof JdbcNamedParameter)
            return;
        if (expression instanceof Column column) {
            checkColumn(column, aliasesAllowed);
            return;
        }
        if (expression instanceof BinaryExpression binary) {
            checkBinary(binary, aliasesAllowed);
            return;
        }
        if (expression instanceof ExpressionList<?> list) {
            // 括号组、函数参数、IN 列表等统一按列表递归
            for (Expression child : list)
                check(child, aliasesAllowed);
            return;
        }
        if (expression instanceof ParenthesedSelect subquery) {
            // 表达式位置的子查询（标量/EXISTS/IN 内部）作为完整查询块递归校验；
            // scope 作为外层传入，关联子查询由此引用外层表
            subqueries.analyze(subquery.getSelect(), scope, ctes, depth + 1, run);
            return;
        }
        // 窗口函数与普通函数是两个独立分支：5.x 起 AnalyticExpression 不再继承 Function（4.x 时代是），
        // 它的 OVER 子句结构只能在这里校验，普通 Function 分支覆盖不到。
        if (expression instanceof AnalyticExpression analytic) {
            checkAnalytic(analytic, aliasesAllowed);
            return;
        }
        if (expression instanceof Function function) {
            checkFunction(function, aliasesAllowed);
            return;
        }
        if (expression instanceof CaseExpression caseExpression) {
            check(caseExpression.getSwitchExpression(), aliasesAllowed);
            if (caseExpression.getWhenClauses() != null)
                for (WhenClause whenClause : caseExpression.getWhenClauses())
                    check(whenClause, aliasesAllowed);
            check(caseExpression.getElseExpression(), aliasesAllowed);
            return;
        }
        if (expression instanceof WhenClause whenClause) {
            check(whenClause.getWhenExpression(), aliasesAllowed);
            check(whenClause.getThenExpression(), aliasesAllowed);
            return;
        }
        if (expression instanceof Between between) {
            check(between.getLeftExpression(), aliasesAllowed);
            check(between.getBetweenExpressionStart(), aliasesAllowed);
            check(between.getBetweenExpressionEnd(), aliasesAllowed);
            return;
        }
        if (expression instanceof InExpression in) {
            check(in.getLeftExpression(), aliasesAllowed);
            check(in.getRightExpression(), aliasesAllowed);
            return;
        }
        if (expression instanceof ExistsExpression exists) {
            check(exists.getRightExpression(), aliasesAllowed);
            return;
        }
        if (expression instanceof IsNullExpression isNull) {
            check(isNull.getLeftExpression(), aliasesAllowed);
            return;
        }
        if (expression instanceof NotExpression not) {
            check(not.getExpression(), aliasesAllowed);
            return;
        }
        if (expression instanceof SignedExpression signed) {
            check(signed.getExpression(), aliasesAllowed);
            return;
        }
        if (expression instanceof CastExpression cast) {
            checkCast(cast, aliasesAllowed);
            return;
        }
        if (expression instanceof ExtractExpression extract) {
            check(extract.getExpression(), aliasesAllowed);
            return;
        }
        if (expression instanceof IntervalExpression interval) {
            check(interval.getExpression(), aliasesAllowed);
            return;
        }
        // 无括号时间关键字（CURRENT_DATE 等）——MySQL 时间函数的另一种写法，按白名单放行
        if (expression instanceof TimeKeyExpression timeKey) {
            if (!policy.isTimeKeywordAllowed(timeKey.getStringValue().toUpperCase(Locale.ROOT)))
                SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("函数不在允许清单中");
            return;
        }
        // 兜底：未显式接受的类型一律拒绝，JSqlParser 升级新增的表达式类型也落在这里。
        SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("不支持的SQL表达式：" + expression.getClass().getSimpleName());
    }

    private void checkColumn(Column column, boolean aliasesAllowed) {
        String name = SqlIdentifiers.normalizeIdentifier(column.getColumnName());
        boolean qualified = column.getTable() != null && column.getTable().getName() != null;
        if (!qualified && ("true".equals(name) || "false".equals(name)))
            return; // 无表限定的布尔字面量
        if (!qualified && aliasesAllowed && scope.aliases.contains(name))
            return; // GROUP BY/HAVING/ORDER BY 中引用本块输出列别名
        resolveColumn(column);
    }

    private void checkBinary(BinaryExpression binary, boolean aliasesAllowed) {
        if (!policy.isBinaryOperatorAllowed(binary.getClass().getSimpleName()))
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("表达式运算符未支持");
        check(binary.getLeftExpression(), aliasesAllowed);
        check(binary.getRightExpression(), aliasesAllowed);
    }

    private void checkFunction(Function function, boolean aliasesAllowed) {
        String name = SqlIdentifiers.normalizeIdentifier(function.getName());
        if (!policy.isFunctionAllowed(name) || function.getAttribute() != null || function.getKeep() != null
                || function.getNamedParameters() != null)
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("函数不在允许清单中");
        if (function.getParameters() != null)
            for (Expression argument : function.getParameters()) {
                if (argument instanceof AllColumns && "count".equals(name))
                    continue; // COUNT(*)
                check(argument, aliasesAllowed);
            }
        checkOrderBy(function.getOrderByElements());
    }

    private void checkAnalytic(AnalyticExpression analytic, boolean aliasesAllowed) {
        String name = SqlIdentifiers.normalizeIdentifier(analytic.getName());
        if (!policy.isFunctionAllowed(name) || analytic.getKeep() != null
                || analytic.getFilterExpression() != null)
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("窗口函数结构未支持");
        if (analytic.getWindowName() != null) {
            // OVER w：引用本查询块 WINDOW 子句定义的命名窗口
            String windowName = SqlIdentifiers.normalizeIdentifier(analytic.getWindowName());
            if (!scope.windowNames.contains(windowName))
                SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("命名窗口未在WINDOW子句中定义：" + windowName);
        }
        check(analytic.getExpression(), aliasesAllowed);
        check(analytic.getOffset(), aliasesAllowed);
        check(analytic.getDefaultValue(), aliasesAllowed);
        check(analytic.getPartitionExpressionList(), aliasesAllowed);
        checkOrderBy(analytic.getOrderByElements());
        checkWindowElement(analytic.getWindowElement(), aliasesAllowed);
    }

    /** 窗口帧（ROWS/RANGE BETWEEN ...）内出现的表达式全量校验；UNBOUNDED/CURRENT ROW 边界的表达式为 null。 */
    private void checkWindowElement(WindowElement window, boolean aliasesAllowed) {
        if (window == null)
            return;
        if (window.getOffset() != null)
            check(window.getOffset().getExpression(), aliasesAllowed);
        if (window.getRange() != null) {
            if (window.getRange().getStart() != null)
                check(window.getRange().getStart().getExpression(), aliasesAllowed);
            if (window.getRange().getEnd() != null)
                check(window.getRange().getEnd().getExpression(), aliasesAllowed);
        }
    }

    private void checkCast(CastExpression cast, boolean aliasesAllowed) {
        if (cast.getColDataType() == null)
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("CAST类型未支持");
        // 5.2 的 getDataType() 会带精度后缀（如 "DECIMAL (12, 2)"），先截取纯类型名再匹配白名单
        String dataType = cast.getColDataType().getDataType().toUpperCase(Locale.ROOT);
        int parenthesis = dataType.indexOf('(');
        if (parenthesis > 0)
            dataType = dataType.substring(0, parenthesis).trim();
        if (!policy.isCastTypeAllowed(dataType))
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("CAST类型未支持");
        check(cast.getLeftExpression(), aliasesAllowed);
    }

    /**
     * 解析列引用：从当前查询块向外层逐块查找（关联引用合法）。
     * 限定别名时必须命中且列存在；未限定时要求在所在块内无歧义。
     */
    private ColumnFact resolveColumn(Column column) {
        String name = SqlIdentifiers.normalizeIdentifier(column.getColumnName());
        if (column.getTable() != null && column.getTable().getSchemaName() != null)
            SqlErrorCode.SQL_TABLE_REJECTED.fail("禁止跨库字段引用");
        if (column.getTable() != null && column.getTable().getName() != null) {
            // 带限定：命中别名即检查列存在，列不在直接拒绝、不再向外层找（与 SQL 解析语义一致）
            String qualifier = SqlIdentifiers.normalizeIdentifier(column.getTable().getName());
            for (Scope current = scope; current != null; current = current.parent) {
                Binding binding = current.bindings.get(qualifier);
                if (binding != null) {
                    if (!binding.columns.contains(name))
                        SqlErrorCode.SQL_COLUMN_REJECTED.fail("字段不存在或不允许查询：" + name);
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
                    SqlErrorCode.SQL_COLUMN_REJECTED.fail("字段含义不明确，请使用表别名：" + name);
                if (matches.size() == 1)
                    return new ColumnFact(matches.get(0), name);
            }
        }
        SqlErrorCode.SQL_COLUMN_REJECTED.fail("字段或表别名不存在：" + name);
        return null;
    }
}

package com.boc.nl2sql.service.execution;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 等值约束收集：从 WHERE/ON 的 AND 链中提取授权事实与等值边（含 IN 名单证明），
 * 产出供 {@link SqlScopeProver} 使用的事实集合与传播图。
 */
final class SqlConstraintCollector {
    private final Scope scope;
    private final Map<String, Object> parameters;
    private final Set<String> confirmedCustomers;   // null = 非客户模式
    private final Run run;
    private final List<Edge> edges = new ArrayList<>();

    SqlConstraintCollector(Scope scope, Map<String, Object> parameters,
                           Set<String> confirmedCustomers, Run run) {
        this.scope = scope;
        this.parameters = parameters;
        this.confirmedCustomers = confirmedCustomers;
        this.run = run;
    }

    /** 从 WHERE 与全部 JOIN 的 ON 收集约束，返回授权证明的传播图。 */
    List<Edge> collectFrom(PlainSelect plain) {
        collect(plain.getWhere(), null);
        for (Join join : joins(plain)) {
            Binding rightSide = scope.bindings.get(SqlIdentifiers.sourceAlias(join.getRightItem()));
            for (Expression onCondition : join.getOnExpressions())
                collect(onCondition, join.isLeft() ? rightSide : null);
        }
        return edges;
    }

    private List<Join> joins(PlainSelect plain) {
        return plain.getJoins() == null ? List.of() : plain.getJoins();
    }

    /**
     * 从 WHERE/ON 的 AND 链中提取等值约束（列-列、列-字面量、列-命名参数）。
     * OR/NOT 分支不作为授权依据，但本身合法——块内另有独立 AND 约束即可完成证明。
     * nullableSide 为 LEFT JOIN 的右表：ON 条件只约束被补全的一侧，事实只流向该侧。
     */
    private void collect(Expression condition, Binding nullableSide) {
        if (condition == null)
            return;
        if (++run.expressionDepth > 200)
            SqlErrorCode.SQL_STRUCTURE_REJECTED.fail("SQL条件嵌套过深");
        try {
            if (condition instanceof ParenthesedExpressionList<?> grouped && grouped.size() == 1) {
                collect(grouped.get(0), nullableSide);
                return;
            }
            if (condition instanceof AndExpression and) {
                collect(and.getLeftExpression(), nullableSide);
                collect(and.getRightExpression(), nullableSide);
                return;
            }
            if (condition instanceof InExpression in) {
                collectInListProof(in);
                return;
            }
            if (!(condition instanceof EqualsTo equality))
                return; // 其它运算符（>、LIKE…）不构成等值约束
            Fact left = factOf(equality.getLeftExpression());
            Fact right = factOf(equality.getRightExpression());
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
        } finally {
            run.expressionDepth--;
        }
    }

    /**
     * 名单约束证明（@客户名单批量查询）：customer_id IN (字符串字面量集合)，
     * 且集合成员全部落在服务端核验的确认名单内时，该数据源的 customer_id 视同已被确认客户约束。
     * 列表必须全部是字符串字面量——出现数字、命名参数或子查询时无法与确认名单完整核对，
     * 整体不作为证明。
     */
    private void collectInListProof(InExpression in) {
        // 以下任一条件不满足即保持沉默（不证明也不报错，授权交由本块其余约束完成）：
        if (confirmedCustomers == null)
            return;                                   // 非客户模式，没有"确认名单"可核对
        if (!(in.getLeftExpression() instanceof Column column))
            return;                                   // 左侧必须是列
        if (!SqlGuardPolicy.CUSTOMER_ID.equals(SqlIdentifiers.normalizeIdentifier(column.getColumnName())))
            return;                                   // 必须是 customer_id
        if (column.getTable() == null || column.getTable().getName() == null)
            return;                                   // 必须带表限定，才能定位到具体数据源
        Binding binding = scope.bindings.get(SqlIdentifiers.normalizeIdentifier(column.getTable().getName()));
        if (binding == null)
            return;                                   // 限定名不是本块的数据源
        List<String> literals = new ArrayList<>();
        if (!collectStringLiterals(in.getRightExpression(), literals))
            return;
        if (literals.isEmpty() || !confirmedCustomers.containsAll(literals))
            return;
        run.customerListProven.add(new ColumnFact(binding, SqlGuardPolicy.CUSTOMER_ID));
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
    private Fact factOf(Expression expression) {
        if (expression instanceof Column column)
            return resolveColumn(column);
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
                        .filter(candidate -> candidate.columns.contains(name)).toList();
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

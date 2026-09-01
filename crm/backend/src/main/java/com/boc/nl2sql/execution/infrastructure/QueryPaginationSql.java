package com.boc.nl2sql.execution.infrastructure;

import com.boc.nl2sql.execution.domain.QueryPage;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 将已通过白名单校验的业务SQL改写为总数SQL和稳定排序的分页SQL。 */
final class QueryPaginationSql {
    private QueryPaginationSql() { }

    static Statements build(String sql, QueryPage page) {
        try {
            Select count = (Select) CCJSqlParserUtil.parse(sql);
            stripOuterPagination(count);
            clearOuterOrder(count);

            Select data = (Select) CCJSqlParserUtil.parse(sql);
            stripOuterPagination(data);
            addStableOrder(data);
            Limit limit = new Limit();
            limit.setRowCount(new LongValue(page.pageSize()));
            limit.setOffset(new LongValue(page.offset()));
            data.setLimit(limit);
            return new Statements("SELECT COUNT(*) AS total FROM (" + count + ") page_count", data.toString());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("无法生成安全分页SQL", invalid);
        }
    }

    /** JSqlParser 5.2可能把UNION末尾分页挂在最后一个分支，两个位置都清理。 */
    private static void stripOuterPagination(Select select) {
        select.setLimit(null);
        select.setOffset(null);
        Select body = unwrap(select);
        body.setLimit(null);
        body.setOffset(null);
        if (body instanceof SetOperationList set && !set.getSelects().isEmpty()) {
            Select last = set.getSelects().get(set.getSelects().size() - 1);
            last.setLimit(null);
            last.setOffset(null);
        }
    }

    private static void clearOuterOrder(Select select) {
        Select body = unwrap(select);
        body.setOrderByElements(null);
    }

    /** 保留业务主排序，并把全部输出列追加为并列排序键，避免翻页时同值记录漂移。 */
    private static void addStableOrder(Select select) {
        Select body = unwrap(select);
        List<OrderByElement> order = body.getOrderByElements() == null
                ? new ArrayList<>() : new ArrayList<>(body.getOrderByElements());
        Set<String> existing = new HashSet<>();
        for (OrderByElement item : order) existing.add(normalize(item.getExpression()));
        if (body instanceof PlainSelect plain) {
            for (var item : plain.getSelectItems()) {
                Expression expression = item.getAlias() == null ? item.getExpression() : new Column(item.getAliasName());
                append(order, existing, expression);
            }
        } else if (body instanceof SetOperationList set && !set.getSelects().isEmpty()) {
            int columns = outputCount(unwrap(set.getSelects().get(0)));
            for (int position = 1; position <= columns; position++) append(order, existing, new LongValue(position));
        }
        body.setOrderByElements(order);
    }

    private static int outputCount(Select select) {
        if (select instanceof PlainSelect plain) return plain.getSelectItems().size();
        if (select instanceof SetOperationList set && !set.getSelects().isEmpty()) return outputCount(unwrap(set.getSelects().get(0)));
        return 0;
    }

    private static void append(List<OrderByElement> order, Set<String> existing, Expression expression) {
        if (!existing.add(normalize(expression))) return;
        OrderByElement item = new OrderByElement();
        item.setExpression(expression);
        item.setAsc(true);
        item.setAscDescPresent(true);
        order.add(item);
    }

    private static Select unwrap(Select select) {
        Select current = select;
        while (current instanceof ParenthesedSelect parenthesed) current = parenthesed.getSelect();
        return current;
    }

    private static String normalize(Expression expression) {
        return expression.toString().replace("`", "").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    record Statements(String countSql, String pageSql) { }
}

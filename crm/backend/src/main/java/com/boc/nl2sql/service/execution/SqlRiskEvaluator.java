package com.boc.nl2sql.service.execution;

import com.boc.nl2sql.domain.execution.PlannedQuery;
import com.boc.nl2sql.domain.execution.QueryRisk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用可解释的确定性规则识别大范围和潜在慢查询。
 *
 * <p>演示环境不依赖数据库统计信息，因此不承诺精确耗时预测；规则关注无过滤明细、过多关联、
 * 全量请求等高概率风险，并把触发原因交给用户确认。</p>
 */
@Component
public class SqlRiskEvaluator {
    private static final Pattern LIMIT = Pattern.compile("(?i)\\blimit\\s+(\\d+)");
    @org.springframework.beans.factory.annotation.Value("${app.query.max-sql-limit:500}")
    private int maxSqlLimit = 500;

    public QueryRisk assess(PlannedQuery planned) {
        String normalized = planned.sql().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<String> reasons = new ArrayList<>();
        if (planned.risk() != null && planned.risk().reasons() != null) {
            reasons.addAll(planned.risk().reasons());
        }
        long joins = Pattern.compile("\\bjoin\\b").matcher(normalized).results().count();
        if (joins >= 3) reasons.add("查询涉及的数据范围较大，执行耗时可能较长");
        if (!normalized.contains(" where ") && referencesFactTable(normalized)) {
            reasons.add("查询未限定客户范围，可能扫描大量数据，请确认后再执行");
        }
        if (!containsAggregate(normalized) && referencesFactTable(normalized) && !containsTimeFilter(normalized)) {
            reasons.add("明细查询未限定时间范围，耗时可能较长");
        }
        Matcher limit = LIMIT.matcher(normalized);
        if (limit.find() && Integer.parseInt(limit.group(1)) > maxSqlLimit) {
            reasons.add("用户指定的Top N结果超过"+maxSqlLimit+"行");
        }
        List<String> distinct = reasons.stream().distinct().toList();
        if (distinct.isEmpty()) return QueryRisk.low();
        return new QueryRisk(distinct.size() >= 2 ? "HIGH" : "MEDIUM", true, distinct);
    }

    private boolean referencesFactTable(String sql) {
        return sql.contains("fct_transaction") || sql.contains("fct_product_holding")
                || sql.contains("fct_customer_marketing");
    }

    private boolean containsAggregate(String sql) {
        return sql.contains("count(") || sql.contains("sum(") || sql.contains("avg(")
                || sql.contains("min(") || sql.contains("max(");
    }

    private boolean containsTimeFilter(String sql) {
        return sql.contains("transaction_date") || sql.contains("contact_time") || sql.contains("snapshot_date");
    }
}

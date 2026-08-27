package com.boc.nl2sql.execution.application;

import com.boc.nl2sql.execution.domain.ColumnMeta;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import com.boc.nl2sql.execution.domain.QueryResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ResultAssembler {
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("customer_id", "客户编号"), Map.entry("customer_name", "客户姓名"),
            Map.entry("customer_level", "客户等级"), Map.entry("asset_wan", "资产（万元）"),
            Map.entry("asset_change_rate", "近三月资产变化（%）"), Map.entry("branch_id", "机构"),
            Map.entry("customer_count", "客户数"), Map.entry("total_asset_yi", "资产合计（亿元）"),
            Map.entry("avg_asset_change_rate", "平均资产变化（%）"), Map.entry("transaction_count", "交易笔数"),
            Map.entry("transaction_amount_wan", "交易金额（万元）"), Map.entry("avg_transaction_amount", "平均交易金额"),
            Map.entry("product_name", "产品名称"), Map.entry("product_category_code", "产品类别"),
            Map.entry("market_value_wan", "持有市值（万元）"), Map.entry("maturity_date", "到期日"),
            Map.entry("holding_count", "持仓数"), Map.entry("market_value_yi", "持有市值（亿元）"),
            Map.entry("profit_wan", "收益（万元）"), Map.entry("campaign_name", "营销活动"),
            Map.entry("contact_count", "触达人数"), Map.entry("response_count", "响应人数"),
            Map.entry("conversion_count", "转化人数"), Map.entry("conversion_rate", "转化率（%）"),
            Map.entry("conversion_amount_wan", "转化金额（万元）"));

    public QueryResult assemble(PlannedQuery planned, List<Map<String, Object>> rows) {
        List<Map<String, Object>> normalizedRows = rows.stream().map(this::normalizeKeys).toList();
        List<ColumnMeta> columns = normalizedRows.isEmpty() ? List.of() : normalizedRows.get(0).keySet().stream()
                .map(key -> new ColumnMeta(key, LABELS.getOrDefault(key, key), inferType(normalizedRows.get(0).get(key)),
                        key.contains("name") || key.contains("mobile")))
                .toList();
        List<Map<String, Object>> metrics = normalizedRows.isEmpty() ? List.of()
                : normalizedRows.get(0).entrySet().stream()
                .filter(entry -> entry.getValue() instanceof Number)
                .limit(3)
                .map(entry -> Map.<String, Object>of(
                        "label", LABELS.getOrDefault(entry.getKey(), entry.getKey()),
                        "value", entry.getValue()))
                .toList();
        String summary = normalizedRows.isEmpty()
                ? "没有找到符合当前条件的数据，可以调整范围后重试。"
                : "查询完成，共返回 " + normalizedRows.size() + " 行已授权、已脱敏结果。";
        return new QueryResult(planned.resultType(), planned.title(), summary, columns, normalizedRows, metrics,
                sanitizeSql(planned.sql()), LocalDate.now());
    }

    private Map<String, Object> normalizeKeys(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key.toLowerCase(), value));
        return result;
    }

    private String inferType(Object value) {
        if (value instanceof Number) return "NUMBER";
        if (value instanceof java.time.temporal.Temporal) return "DATE";
        return "TEXT";
    }

    private String sanitizeSql(String sql) {
        // SQL预览只展示模板和参数名，不展开客户范围、金额等实际绑定值。
        return sql.strip().replaceAll("\\s+", " ");
    }
}

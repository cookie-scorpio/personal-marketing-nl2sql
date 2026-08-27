package com.boc.nl2sql.execution.application;

import com.boc.nl2sql.execution.domain.ColumnMeta;
import com.boc.nl2sql.execution.domain.AnalysisSummary;
import com.boc.nl2sql.execution.domain.ChartSeries;
import com.boc.nl2sql.execution.domain.ChartSpec;
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
            Map.entry("conversion_amount_wan", "转化金额（万元）"), Map.entry("age", "年龄"),
            Map.entry("age_band_code", "年龄段"), Map.entry("gender_code", "性别"),
            Map.entry("region_code", "区域"), Map.entry("transaction_date", "交易日期"),
            Map.entry("snapshot_date", "快照日期"), Map.entry("channel_code", "渠道"),
            Map.entry("contact_channel_code", "触达渠道"), Map.entry("budget_amount", "预算金额"));

    public QueryResult assemble(PlannedQuery planned, List<Map<String, Object>> rows,
                                String interpretationSource, double confidence) {
        List<Map<String, Object>> normalizedRows = rows.stream().map(this::normalizeKeys).toList();
        List<ColumnMeta> columns = normalizedRows.isEmpty() ? List.of() : normalizedRows.get(0).keySet().stream()
                .map(key -> new ColumnMeta(key, LABELS.getOrDefault(key, key), inferType(normalizedRows.get(0).get(key)),
                        key.contains("name") || key.contains("mobile")))
                .toList();
        List<Map<String, Object>> metrics = buildMetrics(planned, columns, normalizedRows);
        String summary = normalizedRows.isEmpty()
                ? "没有找到符合当前条件的数据，可以调整范围后重试。"
                : "查询完成，共返回 " + normalizedRows.size() + " 行模拟业务数据。";
        List<ChartSpec> charts = buildCharts(planned, columns, normalizedRows);
        AnalysisSummary analysis = analyze(columns, normalizedRows, summary);
        return new QueryResult(planned.resultType(), planned.title(), summary, columns, normalizedRows, metrics,
                charts, analysis, sanitizeSql(planned.sql()), LocalDate.now(), interpretationSource, confidence);
    }

    /** 指标卡展示全体结果口径：可加总字段求和，平均值和比率字段取各分组均值。 */
    private List<Map<String, Object>> buildMetrics(PlannedQuery planned, List<ColumnMeta> columns,
                                                   List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return List.of();
        if ("TABLE".equalsIgnoreCase(planned.resultType())) {
            return List.of(Map.of("label", "结果行数", "value", rows.size()));
        }
        return columns.stream().filter(column -> "NUMBER".equals(column.dataType())).limit(3).map(column -> {
            var values = rows.stream().map(row -> row.get(column.key())).filter(Number.class::isInstance)
                    .map(Number.class::cast).toList();
            double value = values.stream().mapToDouble(Number::doubleValue).sum();
            if (column.key().contains("avg") || column.key().contains("rate")) value /= Math.max(1, values.size());
            return Map.<String, Object>of("label", column.label(), "value", compactNumberValue(value));
        }).toList();
    }

    /** 根据“一个维度 + 一个或多个数值”的常见结果形态选择图表，不让前端猜测数据库字段。 */
    private List<ChartSpec> buildCharts(PlannedQuery planned, List<ColumnMeta> columns,
                                        List<Map<String, Object>> rows) {
        if (rows.size() < 2 || columns.isEmpty()) return List.of();
        ColumnMeta dimension = columns.stream().filter(column -> !"NUMBER".equals(column.dataType())).findFirst().orElse(null);
        List<ColumnMeta> numbers = columns.stream().filter(column -> "NUMBER".equals(column.dataType())).toList();
        if (dimension == null || numbers.isEmpty()) return List.of();
        ColumnMeta primary = preferredMeasure(numbers);

        String requested = planned.resultType() == null ? "AUTO" : planned.resultType().toUpperCase();
        String type;
        if (List.of("BAR", "LINE", "PIE").contains(requested)) {
            type = requested;
        } else if ("DATE".equals(dimension.dataType()) || dimension.key().contains("date")
                || dimension.key().contains("month")) {
            type = "LINE";
        } else if (rows.size() <= 6 && (dimension.key().contains("category") || dimension.key().contains("level"))) {
            type = "PIE";
        } else {
            type = "BAR";
        }
        // 一个主图只使用一个计量口径，避免把人数、金额和比率放在同一纵轴导致误读。
        List<ChartSeries> series = List.of(new ChartSeries(primary.key(), primary.label()));
        return List.of(new ChartSpec(type, planned.title() + " · 可视化", dimension.key(), series));
    }

    private ColumnMeta preferredMeasure(List<ColumnMeta> columns) {
        for (String candidate : List.of("conversion_rate", "transaction_amount_wan", "market_value_yi",
                "total_asset_yi", "customer_count")) {
            var match = columns.stream().filter(column -> candidate.equals(column.key())).findFirst();
            if (match.isPresent()) return match.get();
        }
        return columns.get(0);
    }

    /**
     * 提供可复现的基础数据分析：识别主要数值列、最高项和合计值。
     * 更复杂的经营归因应由后续分析模型完成，当前不把相关性描述成因果关系。
     */
    private AnalysisSummary analyze(List<ColumnMeta> columns, List<Map<String, Object>> rows, String overview) {
        if (rows.isEmpty()) {
            return new AnalysisSummary(overview, List.of("当前条件下没有可比较的数据项。"),
                    List.of("可适当扩大时间或客户范围后重新查询。"));
        }
        ColumnMeta dimension = columns.stream().filter(column -> !"NUMBER".equals(column.dataType())).findFirst().orElse(null);
        List<ColumnMeta> numericColumns = columns.stream().filter(column -> "NUMBER".equals(column.dataType())).toList();
        ColumnMeta primaryNumber = numericColumns.isEmpty() ? null : preferredMeasure(numericColumns);
        List<String> insights = new ArrayList<>();
        if (primaryNumber != null) {
            Map<String, Object> top = rows.stream()
                    .filter(row -> row.get(primaryNumber.key()) instanceof Number)
                    .max((left, right) -> Double.compare(number(left.get(primaryNumber.key())),
                            number(right.get(primaryNumber.key())))).orElse(null);
            if (top != null && dimension != null) {
                insights.add(dimension.label() + "“" + top.get(dimension.key()) + "”的"
                        + primaryNumber.label() + "最高，为" + top.get(primaryNumber.key()) + "。");
            }
            double total = rows.stream().map(row -> row.get(primaryNumber.key())).filter(Number.class::isInstance)
                    .mapToDouble(this::number).sum();
            if (rows.size() > 1) {
                boolean average = primaryNumber.key().contains("rate") || primaryNumber.key().contains("avg");
                insights.add(primaryNumber.label() + (average ? "各分组均值为" : "合计为")
                        + compactNumber(average ? total / rows.size() : total) + "。");
            }
        }
        if (insights.isEmpty()) insights.add("结果以明细信息为主，未发现可直接聚合比较的数值列。");
        List<String> suggestions = rows.size() >= 2
                ? List.of("可结合图表关注各分组差异，并继续追问时间趋势或细分客群。")
                : List.of("当前仅有一个结果项，可扩大维度后进行横向比较。");
        return new AnalysisSummary(overview, insights, suggestions);
    }

    private double number(Object value) {
        return ((Number) value).doubleValue();
    }

    private String compactNumber(double value) {
        if (Math.rint(value) == value) return String.format("%.0f", value);
        return String.format("%.2f", value);
    }

    private Object compactNumberValue(double value) {
        if (Math.rint(value) == value) return (long) value;
        return Math.round(value * 100.0) / 100.0;
    }

    private Map<String, Object> normalizeKeys(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key.toLowerCase(), value));
        return result;
    }

    private String inferType(Object value) {
        if (value instanceof Number) return "NUMBER";
        if (value instanceof java.time.temporal.Temporal || value instanceof java.util.Date) return "DATE";
        return "TEXT";
    }

    private String sanitizeSql(String sql) {
        // 规则SQL不展开绑定值；模型SQL可能含字面量。前端仅展示，不提供编辑或提交SQL的接口。
        return sql.strip().replaceAll("\\s+", " ");
    }
}

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
        List<Map<String, Object>> normalized = rows.stream().map(this::normalizeKeys).toList();
        var keys = new java.util.LinkedHashSet<String>();
        normalized.forEach(row -> keys.addAll(row.keySet()));
        List<ColumnMeta> columns = keys.stream().map(key -> column(key, normalized, planned.columnHints())).toList();
        List<Map<String, Object>> metrics = buildMetrics(columns, normalized);
        String summary = normalized.isEmpty() ? "没有找到符合当前条件的数据，可以调整范围后重试。"
                : "查询完成，共返回 " + normalized.size() + " 行模拟业务数据。";
        return new QueryResult(planned.resultType(), planned.title(), summary, columns, normalized, metrics,
                buildCharts(planned, columns, normalized), analyze(columns, normalized, summary),
                planned.sql() == null ? "" : planned.sql().strip().replaceAll("\\s+", " "),
                LocalDate.now(), interpretationSource, confidence, null);
    }

    private ColumnMeta column(String key, List<Map<String, Object>> rows,
                              List<com.boc.nl2sql.execution.domain.ResultColumnHint> hints) {
        var hint = hints.stream().filter(item -> key.equalsIgnoreCase(item.key())).findFirst().orElse(null);
        Object sample = rows.stream().map(row -> row.get(key)).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        String type = inferType(sample);
        boolean id = key.matches(".*(?:_id|_code)$") || key.equals("id");
        boolean time = "DATE".equals(type) || key.matches(".*(?:date|month|year|day|time).*");
        String role = time ? "TIME" : id || key.equals("age") || !"NUMBER".equals(type) ? "DIMENSION" : "MEASURE";
        if (hint != null && !id) {
            if ("DIMENSION".equals(hint.role()) || "TIME".equals(hint.role())) role = hint.role();
            if ("MEASURE".equals(hint.role()) && (sample == null || sample instanceof Number) && !time) {
                role = "MEASURE"; type = "NUMBER";
            }
        }
        String label = LABELS.getOrDefault(key, key);
        if (key.matches(".*(?:avg|average).*asset.*")) label = "平均资产";
        if (hint != null && hint.label() != null && !hint.label().isBlank() && hint.label().length() <= 80) label = hint.label();
        String unit = unit(key);
        if (hint != null && hint.unit() != null && hint.unit().length() <= 20) unit = hint.unit();
        String aggregation = key.contains("avg") || key.contains("average") || key.contains("rate") ? "AVERAGE"
                : key.contains("count") || key.contains("amount") || key.contains("total")
                || key.contains("value") || key.contains("profit") ? "SUM" : "NONE";
        String weight = null;
        if (hint != null && hint.aggregation() != null
                && List.of("SUM", "AVERAGE", "WEIGHTED_AVERAGE", "NONE").contains(hint.aggregation())) {
            aggregation = hint.aggregation();
            weight = hint.weightKey() == null ? null : hint.weightKey().toLowerCase(java.util.Locale.ROOT);
        }
        // 已知均值/比率不得被模型标成合计。没有可靠分母时只展示清楚标记的分组均值。
        if (key.contains("avg") || key.contains("average") || key.contains("rate")) {
            if ("SUM".equals(aggregation)) aggregation = "AVERAGE";
            if (weight == null && key.matches(".*(?:avg|average).*asset.*") && hasKey(rows, "customer_count")) weight = "customer_count";
            if (weight == null && key.equals("conversion_rate") && hasKey(rows, "contact_count")) weight = "contact_count";
        }
        if (weight != null && !weight.equals(key) && hasKey(rows, weight)) aggregation = "WEIGHTED_AVERAGE";
        else if ("WEIGHTED_AVERAGE".equals(aggregation)) { aggregation = "AVERAGE"; weight = null; }
        if (!"MEASURE".equals(role)) { aggregation = "NONE"; weight = null; }
        return new ColumnMeta(key, label, type, key.contains("name") || key.contains("mobile"),
                role, unit, aggregation, weight);
    }

    private boolean hasKey(List<Map<String, Object>> rows, String key) {
        return rows.stream().anyMatch(row -> row.containsKey(key));
    }

    private String unit(String key) {
        if (key.endsWith("_wan")) return "万元";
        if (key.endsWith("_yi")) return "亿元";
        if (key.contains("rate")) return "%";
        if (key.equals("transaction_count")) return "笔";
        if (key.contains("customer_count") || key.equals("contact_count") || key.equals("response_count")
                || key.equals("conversion_count")) return "人";
        if (key.contains("amount") || key.contains("asset") || key.contains("value")) return "元";
        return "";
    }

    private List<ColumnMeta> measures(List<ColumnMeta> columns) {
        return columns.stream().filter(column -> "MEASURE".equals(column.role())).toList();
    }

    private List<Map<String, Object>> buildMetrics(List<ColumnMeta> columns, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ColumnMeta column : measures(columns)) {
            Double value = aggregate(column, rows);
            if (value == null && rows.size() > 1 && "NONE".equals(column.aggregation())) continue;
            Map<String, Object> metric = new LinkedHashMap<>();
            metric.put("key", column.key()); metric.put("label", column.label());
            metric.put("value", value == null ? null : Math.round(value * 100.0) / 100.0);
            metric.put("unit", column.unit()); metric.put("aggregation", column.aggregation());
            metric.put("note", rows.size() == 1 ? "当前返回值" : aggregationLabel(column));
            result.add(metric);
        }
        if (result.isEmpty()) result.add(Map.of("key", "row_count", "label", "结果行数", "value", rows.size(), "unit", "行", "note", "当前返回结果"));
        return result;
    }

    private Double aggregate(ColumnMeta column, List<Map<String, Object>> rows) {
        var valid = rows.stream().filter(row -> numeric(row.get(column.key()))).toList();
        if (valid.isEmpty()) return null;
        if (rows.size() == 1) return number(valid.get(0).get(column.key()));
        if ("NONE".equals(column.aggregation())) return null;
        if ("WEIGHTED_AVERAGE".equals(column.aggregation())) {
            double weight = 0, sum = 0;
            for (var row : valid) {
                Object raw = row.get(column.weightKey());
                // 权重缺失或负数时不编造总体均值。
                if (!numeric(raw) || number(raw) < 0) return null;
                weight += number(raw);
                sum += number(row.get(column.key())) * number(raw);
            }
            return weight > 0 ? sum / weight : null;
        }
        double total = valid.stream().mapToDouble(row -> number(row.get(column.key()))).sum();
        return "AVERAGE".equals(column.aggregation()) ? total / valid.size() : total;
    }

    private String aggregationLabel(ColumnMeta column) {
        return switch (column.aggregation()) {
            case "SUM" -> "返回分组合计";
            case "WEIGHTED_AVERAGE" -> "按" + LABELS.getOrDefault(column.weightKey(), column.weightKey()) + "加权的返回分组均值";
            case "AVERAGE" -> "返回分组均值（非总体均值）";
            default -> "不进行跨行汇总";
        };
    }

    /** 覆盖全部有效指标，优先用分图区分计量单位；建议类型不符合数据形态时自动纠正。 */
    private List<ChartSpec> buildCharts(PlannedQuery planned, List<ColumnMeta> columns, List<Map<String, Object>> rows) {
        if (rows.size() < 2 || "TABLE".equalsIgnoreCase(planned.resultType())) return List.of();
        List<ColumnMeta> dimensions = columns.stream().filter(column -> !"MEASURE".equals(column.role())).toList();
        List<ColumnMeta> numbers = measures(columns).stream()
                .filter(column -> rows.stream().anyMatch(row -> numeric(row.get(column.key())))).toList();
        if (numbers.isEmpty()) return List.of();
        String requested = planned.resultType() == null ? "AUTO" : planned.resultType().toUpperCase(java.util.Locale.ROOT);
        List<ChartSpec> charts = new ArrayList<>();
        if ((dimensions.isEmpty() || "SCATTER".equals(requested)) && numbers.size() >= 2) {
            charts.add(chart("SCATTER", numbers.get(0), numbers.get(1), null, "比较两个数值指标的分布，不作因果推断"));
            if (dimensions.isEmpty()) return charts;
        }
        if (dimensions.size() == 2) {
            var seen = new java.util.HashSet<List<Object>>();
            boolean unique = rows.stream().allMatch(row -> seen.add(java.util.Arrays.asList(
                    row.get(dimensions.get(0).key()), row.get(dimensions.get(1).key()))));
            if (unique) for (var measure : numbers) charts.add(chart("HEATMAP", dimensions.get(0), measure,
                    dimensions.get(1).key(), "以颜色比较两个维度交叉下的数值，空白表示无数据"));
            return charts;
        }
        if (dimensions.size() != 1) return charts;
        var dimension = dimensions.get(0);
        if (rows.stream().map(row -> row.get(dimension.key())).distinct().count() != rows.size()) return charts;
        for (var measure : numbers) {
            boolean time = "TIME".equals(dimension.role());
            boolean nonnegative = rows.stream().allMatch(row -> numeric(row.get(measure.key())) && number(row.get(measure.key())) >= 0);
            String type = time ? ("AREA".equals(requested) && nonnegative ? "AREA" : "LINE") : "BAR";
            charts.add(chart(type, dimension, measure, null,
                    time ? "按时间顺序展示变化；缺失值保留为空" : "按分组比较此指标，独立标注计量单位"));
            boolean share = !time && rows.size() <= 8 && "SUM".equals(measure.aggregation()) && nonnegative
                    && rows.stream().mapToDouble(row -> number(row.get(measure.key()))).sum() > 0
                    && ("PIE".equals(requested) || dimension.key().contains("category") || dimension.key().contains("level"));
            if (share) charts.add(chart("PIE", dimension, measure, null, "展示当前返回分组的构成比例，不代表全库总体"));
        }
        return charts;
    }

    private ChartSpec chart(String type, ColumnMeta dimension, ColumnMeta measure, String second, String reason) {
        String title = dimension.label() + " · " + measure.label();
        if ("PIE".equals(type)) title += "构成";
        String xLabel = dimension.label() + (dimension.unit().isBlank() ? "" : "（" + dimension.unit() + "）");
        return new ChartSpec(type, title, dimension.key(), List.of(new ChartSeries(measure.key(), measure.label(), measure.unit())),
                xLabel, second, reason);
    }

    private AnalysisSummary analyze(List<ColumnMeta> columns, List<Map<String, Object>> rows, String overview) {
        if (rows.isEmpty()) return new AnalysisSummary(overview, List.of("当前条件下没有可比较的数据项。"), List.of("可调整条件后重新查询。"));
        ColumnMeta dimension = columns.stream().filter(column -> !"MEASURE".equals(column.role())).findFirst().orElse(null);
        List<String> insights = new ArrayList<>();
        for (var measure : measures(columns)) {
            var valid = rows.stream().filter(row -> numeric(row.get(measure.key()))).toList();
            if (valid.isEmpty()) continue;
            var max = valid.stream().max(java.util.Comparator.comparingDouble(row -> number(row.get(measure.key())))).orElseThrow();
            if (dimension != null) insights.add(dimension.label() + "“" + max.get(dimension.key()) + "”的"
                    + measure.label() + "最高，为" + compactNumber(number(max.get(measure.key()))) + measure.unit() + "。");
            Double total = aggregate(measure, rows);
            if (total != null && rows.size() > 1) insights.add(measure.label() + "：" + aggregationLabel(measure)
                    + "为" + compactNumber(total) + measure.unit() + "。");
        }
        if (insights.isEmpty()) insights.add("结果以明细为主，不对编号或缺失值进行数值汇总。");
        return new AnalysisSummary(overview, insights,
                List.of("以上计算仅基于当前返回结果；分组人数可能重叠，不能视为全库去重人数。均值与比率不直接相加。"));
    }

    private boolean numeric(Object value) { return value instanceof Number && Double.isFinite(((Number) value).doubleValue()); }
    private double number(Object value) { return ((Number) value).doubleValue(); }
    private String compactNumber(double value) { return String.format(java.util.Locale.ROOT, Math.rint(value) == value ? "%.0f" : "%.2f", value); }
    private Map<String, Object> normalizeKeys(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key.toLowerCase(java.util.Locale.ROOT), value));
        return result;
    }
    private String inferType(Object value) {
        if (value instanceof Number) return "NUMBER";
        if (value instanceof java.time.temporal.Temporal || value instanceof java.util.Date) return "DATE";
        return "TEXT";
    }
}

package com.boc.nl2sql.execution.application;

import com.boc.nl2sql.execution.domain.ColumnMeta;
import com.boc.nl2sql.execution.domain.AnalysisSummary;
import com.boc.nl2sql.execution.domain.ChartSeries;
import com.boc.nl2sql.execution.domain.ChartSpec;
import com.boc.nl2sql.execution.domain.PlannedQuery;
import com.boc.nl2sql.execution.domain.QueryResult;
import com.boc.nl2sql.execution.domain.PagedQueryRows;
import com.boc.nl2sql.execution.domain.QueryPage;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ResultAssembler {
    private final com.boc.nl2sql.model.DataInsightProvider insights;
    public ResultAssembler(com.boc.nl2sql.model.DataInsightProvider insights){this.insights=insights;}

    private static final Map<String, String> LABELS = Map.ofEntries(
            // 客户画像
            Map.entry("customer_id", "客户编号"), Map.entry("customer_name", "客户姓名"),
            Map.entry("customer_name_masked", "客户姓名"), Map.entry("customer_level", "客户等级"),
            Map.entry("customer_level_code", "客户等级"), Map.entry("gender_code", "性别"),
            Map.entry("age", "年龄"), Map.entry("age_band_code", "年龄段"),
            Map.entry("mobile_masked", "手机号"), Map.entry("vip_flag", "贵宾客户"),
            Map.entry("risk_level_code", "风险等级"), Map.entry("occupation_code", "职业类别"),
            Map.entry("region_code", "区域"), Map.entry("branch_id", "机构"),
            Map.entry("manager_id", "客户经理"), Map.entry("open_date", "开户日期"),
            Map.entry("status_code", "状态"), Map.entry("snapshot_date", "数据日期"),
            Map.entry("total_asset_amount", "总资产（元）"), Map.entry("total_asset_wan", "总资产（万元）"),
            Map.entry("total_asset_yi", "资产合计（亿元）"), Map.entry("asset_wan", "资产（万元）"),
            Map.entry("avg_asset_wan", "平均资产（万元）"), Map.entry("avg_total_asset_wan", "平均总资产（万元）"),
            Map.entry("asset_change_3m_rate", "近三月资产变化率"), Map.entry("asset_change_rate", "近三月资产变化（%）"),
            Map.entry("avg_asset_change_rate", "平均资产变化（%）"),
            // 交易
            Map.entry("transaction_id", "交易编号"), Map.entry("transaction_count", "交易笔数"),
            Map.entry("transaction_time", "交易时间"), Map.entry("transaction_date", "交易日期"),
            Map.entry("transaction_type_code", "交易类型"), Map.entry("debit_credit_flag", "收支方向"),
            Map.entry("currency_code", "币种"), Map.entry("amount_cny", "交易金额（元）"),
            Map.entry("transaction_amount_wan", "交易金额（万元）"), Map.entry("transaction_amount_yi", "交易金额（亿元）"),
            Map.entry("avg_transaction_amount", "平均交易金额（元）"),
            // 产品持有
            Map.entry("product_id", "产品编号"), Map.entry("product_name", "产品名称"),
            Map.entry("product_category_code", "产品类别"), Map.entry("product_count", "产品数"),
            Map.entry("holding_amount", "持有份额"), Map.entry("holding_count", "持仓笔数"),
            Map.entry("market_value_amount", "持有市值（元）"), Map.entry("market_value_wan", "持有市值（万元）"),
            Map.entry("market_value_yi", "持有市值（亿元）"), Map.entry("total_market_value_wan", "持有市值（万元）"),
            Map.entry("profit_amount", "持有收益（元）"), Map.entry("profit_wan", "收益（万元）"),
            Map.entry("maturity_date", "到期日"), Map.entry("deposit_band", "存款市值区间"),
            Map.entry("deposit_amount_wan", "存款市值（万元）"),
            // 营销
            Map.entry("campaign_id", "活动编号"), Map.entry("campaign_name", "营销活动"),
            Map.entry("campaign_type_code", "活动类型"), Map.entry("campaign_status_code", "活动状态"),
            Map.entry("channel_code", "渠道"), Map.entry("contact_channel_code", "触达渠道"),
            Map.entry("contact_time", "触达时间"), Map.entry("contact_count", "触达人数"),
            Map.entry("response_flag", "是否响应"), Map.entry("response_count", "响应人数"),
            Map.entry("conversion_flag", "是否转化"), Map.entry("conversion_count", "转化人数"),
            Map.entry("conversion_rate", "转化率（%）"), Map.entry("conversion_amount", "转化金额（元）"),
            Map.entry("conversion_amount_wan", "转化金额（万元）"), Map.entry("budget_amount", "预算金额"),
            Map.entry("target_count", "目标人数"), Map.entry("target_customer_segment_code", "目标客群"),
            // 通用派生列
            Map.entry("customer_count", "客户数"), Map.entry("customer_ratio_pct", "客户占比（%）"),
            Map.entry("share_pct", "占比（%）"), Map.entry("period_label", "统计区间"),
            Map.entry("start_date", "开始日期"), Map.entry("end_date", "结束日期"),
            Map.entry("month", "月份"), Map.entry("quarter", "季度"));

    /** 编码值字典：明细、图表和分析统一展示中文，不再把 A46_60、PLATINUM 等原码暴露给业务人员。 */
    private static final Map<String, Map<String, String>> VALUE_LABELS = Map.ofEntries(
            Map.entry("gender_code", Map.of("M", "男", "F", "女", "U", "未知")),
            Map.entry("age_band_code", Map.of("A18_25", "18-25岁", "A26_35", "26-35岁", "A36_45", "36-45岁",
                    "A46_60", "46-60岁", "A60_PLUS", "60岁以上")),
            Map.entry("customer_level_code", Map.of("NORMAL", "标准", "GOLD", "黄金", "PLATINUM", "铂金")),
            Map.entry("product_category_code", Map.of("DEPOSIT", "存款", "WEALTH", "理财", "FUND", "基金")),
            Map.entry("transaction_type_code", Map.of("DEPOSIT", "存入", "WITHDRAW", "支取", "TRANSFER", "转账",
                    "CONSUME", "消费", "INTEREST", "利息")),
            Map.entry("channel_code", Map.of("APP", "手机银行", "BRANCH", "网点", "WECHAT", "微信",
                    "SMS", "短信", "CALL", "电话", "ONLINE", "线上", "PHONE", "电话", "MULTI", "多渠道")),
            Map.entry("contact_channel_code", Map.of("APP", "手机银行", "SMS", "短信", "PHONE", "电话",
                    "WECHAT", "微信", "MULTI", "多渠道")),
            Map.entry("contact_channel", Map.of("APP", "手机银行", "SMS", "短信", "PHONE", "电话",
                    "WECHAT", "微信", "MULTI", "多渠道")),
            Map.entry("region_code", Map.of("EAST", "东区", "SOUTH", "南区", "WEST", "西区", "NORTH", "北区")),
            Map.entry("region", Map.of("EAST", "东区", "SOUTH", "南区")),
            Map.entry("status_code", Map.of("ACTIVE", "正常", "SUCCESS", "成功", "FAILED", "失败", "CLOSED", "关闭")));

    /** 未命中精确字典时按常见词根拼接，例如 r5_deposit_wan -> r5存款（万元）；无法识别才回退原始键。 */
    private static final Map<String, String> LABEL_TOKENS = Map.ofEntries(
            Map.entry("total", "合计"), Map.entry("avg", "平均"), Map.entry("average", "平均"),
            Map.entry("max", "最高"), Map.entry("min", "最低"), Map.entry("sum", "合计"),
            Map.entry("count", "数量"), Map.entry("cnt", "数量"), Map.entry("num", "数量"),
            Map.entry("distinct", "去重"), Map.entry("customer", "客户"), Map.entry("transaction", "交易"),
            Map.entry("product", "产品"), Map.entry("holding", "持有"), Map.entry("hold", "持有"),
            Map.entry("campaign", "活动"), Map.entry("deposit", "存款"), Map.entry("wealth", "理财"),
            Map.entry("fund", "基金"), Map.entry("conversion", "转化"), Map.entry("response", "响应"),
            Map.entry("contact", "触达"), Map.entry("asset", "资产"), Map.entry("profit", "收益"),
            Map.entry("market", "市场"), Map.entry("value", "价值"), Map.entry("amount", "金额"),
            Map.entry("new", "新增"), Map.entry("r1", "R1"), Map.entry("r2", "R2"), Map.entry("r3", "R3"),
            Map.entry("r4", "R4"), Map.entry("r5", "R5"), Map.entry("vip", "贵宾"),
            Map.entry("gold", "黄金"), Map.entry("platinum", "铂金"), Map.entry("normal", "标准"),
            Map.entry("active", "有效"), Map.entry("band", "区间"), Map.entry("label", "名称"),
            Map.entry("name", "名称"), Map.entry("type", "类型"), Map.entry("rate", "比率"),
            Map.entry("ratio", "占比"), Map.entry("share", "占比"), Map.entry("balance", "余额"));

    public QueryResult assemble(PlannedQuery planned, List<Map<String, Object>> rows,
                                String interpretationSource, double confidence) {
        return assemble(planned,new PagedQueryRows(rows,rows.size(),new QueryPage(1,Math.max(1,rows.size()),0)),
                interpretationSource,confidence);
    }

    public QueryResult assemble(PlannedQuery planned, PagedQueryRows page,
                                String interpretationSource, double confidence) {
        List<Map<String,Object>> rows=page.rows();
        List<Map<String, Object>> normalized = rows.stream().map(this::normalizeKeys).toList();
        var keys = new java.util.LinkedHashSet<String>();
        normalized.forEach(row -> keys.addAll(row.keySet()));
        List<ColumnMeta> columns = keys.stream().map(key -> column(key, normalized, planned.columnHints())).toList();
        List<Map<String, Object>> metrics = buildMetrics(columns, normalized);
        String summary = normalized.isEmpty() ? (page.total()>0
                ? "查询完成，共 " + page.total() + " 条，当前分页位置没有数据。"
                : emptySummary())
                : allZeroSingleRow(columns, normalized) ? "查询完成，但当前条件下没有匹配数据（计数与金额均为0）。"+coverageNote()
                : page.total()>normalized.size() || page.page().offset()>0
                ? "查询完成，共 " + page.total() + " 条；当前第 " + page.page().pageNo() + " 页返回 " + normalized.size() + " 条。"
                : "查询完成，共返回 " + normalized.size() + " 行模拟业务数据。";
        var charts=buildCharts(planned,columns,normalized);
        var analysis=analyze(columns,normalized,summary);
        if(planned.resultType()!=null && Set.of("PIE","LINE","AREA","BAR","SCATTER","HEATMAP").contains(planned.resultType())
                && charts.stream().noneMatch(chart->chart.type().equals(planned.resultType()))) {
            var insights=new ArrayList<>(analysis.insights());
            insights.add("本次要求的"+planned.resultType()+"未绘制：结果为空、维度不唯一或指标不适合该图形。饼图需要一个互斥分类和非负、可加总且合计大于零的数值；比例请同时返回对应人数。已保留实际数据与可用展示。");
            analysis=new AnalysisSummary(analysis.overview(),insights,analysis.suggestions());
        }
        return new QueryResult(planned.resultType(), planned.title(), summary, columns, normalized, metrics,
                charts, analysis,
                page.sqlPreview()!=null?page.sqlPreview().strip().replaceAll("\\s+", " "):
                        planned.sql() == null ? "" : planned.sql().strip().replaceAll("\\s+", " "),
                LocalDate.now(), interpretationSource, confidence, page.total(), page.page().pageNo(),
                page.page().pageSize(), page.page().offset(), page.hasMore(), null);
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
        String label = LABELS.containsKey(key) ? LABELS.get(key) : derivedLabel(key);
        if (key.matches(".*(?:avg|average).*asset.*")) label = "平均资产";
        if (hint != null && hint.label() != null && !hint.label().isBlank() && hint.label().length() <= 80) label = hint.label();
        String unit = unit(key);
        if (hint != null && hint.unit() != null && hint.unit().length() <= 20) unit = hint.unit();
        String aggregation = key.contains("avg") || key.contains("average") || key.contains("rate") ? "AVERAGE"
                : key.matches(".*(?:count|cnt)$") ? "COUNT"
                : key.contains("amount") || key.contains("total")
                || key.contains("value") || key.contains("profit") ? "SUM" : "NONE";
        String weight = null;
        if (hint != null && hint.aggregation() != null
                && List.of("SUM", "COUNT", "AVERAGE", "WEIGHTED_AVERAGE", "NONE").contains(hint.aggregation())) {
            aggregation = hint.aggregation();
            weight = hint.weightKey() == null ? null : hint.weightKey().toLowerCase(java.util.Locale.ROOT);
        }
        // 人数/笔数是可加总指标；模型误标 NONE 时按结构兜底，避免明确要求的饼图被静默降级。
        if ("NONE".equals(aggregation) && "MEASURE".equals(role) && key.matches(".*(?:count|cnt)$")) aggregation = "COUNT";
        // 已知均值/比率不得被模型标成合计。没有可靠分母时只展示清楚标记的分组均值。
        if (key.contains("avg") || key.contains("average") || key.contains("rate")
                || key.contains("ratio") || key.contains("percent") || key.contains("proportion") || "%".equals(unit)) {
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
            case "SUM", "COUNT" -> "返回分组合计";
            case "WEIGHTED_AVERAGE" -> "按" + LABELS.getOrDefault(column.weightKey(), column.weightKey()) + "加权的返回分组均值";
            case "AVERAGE" -> "返回分组均值（非总体均值）";
            default -> "不进行跨行汇总";
        };
    }

    /** 覆盖全部有效指标，优先用分图区分计量单位；建议类型不符合数据形态时自动纠正。 */
    private List<ChartSpec> buildCharts(PlannedQuery planned, List<ColumnMeta> columns, List<Map<String, Object>> rows) {
        if (rows.isEmpty() || rows.size()<2 && !"PIE".equalsIgnoreCase(planned.resultType()) || "TABLE".equalsIgnoreCase(planned.resultType())) return List.of();
        List<ColumnMeta> dimensions = columns.stream().filter(column -> !"MEASURE".equals(column.role()))
                .filter(column -> !Set.of("customer_id","customer_name","snapshot_date").contains(column.key())
                        || rows.stream().map(row->row.get(column.key())).distinct().count()>1).toList();
        List<ColumnMeta> numbers = measures(columns).stream()
                .filter(column -> rows.stream().anyMatch(row -> numeric(row.get(column.key())))).toList();
        if (numbers.isEmpty()) return List.of();
        String requested = planned.resultType() == null ? "AUTO" : planned.resultType().toUpperCase(java.util.Locale.ROOT);
        // 分组标签与数值排序号常一起返回。显式饼图可忽略与标签一一对应的排序维度，
        // 但不能丢弃重复标签下的独立分类（例如机构×产品），也不修改原始表格。
        if ("PIE".equals(requested) && dimensions.size() > 1) {
            var labels = dimensions.stream().filter(c -> "TEXT".equals(c.dataType())).toList();
            boolean oneToOne = dimensions.stream().allMatch(c -> rows.stream().allMatch(r -> r.get(c.key()) != null)
                    && rows.stream().map(r -> r.get(c.key())).distinct().count() == rows.size());
            if (labels.size() == 1 && oneToOne && dimensions.stream().allMatch(c -> c == labels.get(0) || "NUMBER".equals(c.dataType())))
                dimensions = List.of(labels.get(0));
        }
        List<ChartSpec> charts = new ArrayList<>();
        if ((dimensions.isEmpty() || "SCATTER".equals(requested)) && numbers.size() >= 2) {
            charts.add(chart("SCATTER", numbers.get(0), numbers.get(1), null, "比较两个数值指标的分布，不作因果推断"));
            if (dimensions.isEmpty()) return charts;
        }
        if (dimensions.size() == 2) {
            var axes = dimensions;
            var seen = new java.util.HashSet<List<Object>>();
            boolean unique = rows.stream().allMatch(row -> seen.add(java.util.Arrays.asList(
                    row.get(axes.get(0).key()), row.get(axes.get(1).key()))));
            if(unique){
                var time=dimensions.stream().filter(d->"TIME".equals(d.role())).findFirst();
                if(time.isPresent()){
                    var category=dimensions.get(0)==time.get()?dimensions.get(1):dimensions.get(0);
                    for(var measure:numbers)charts.add(chart("LINE",time.get(),measure,category.key(),"按时间比较不同分组的变化，缺失分组值保持为空"));
                }else for (var measure : numbers) charts.add(chart("HEATMAP", dimensions.get(0), measure,
                        dimensions.get(1).key(), "以颜色比较两个维度交叉下的数值，空白表示无数据"));
            }
            return charts;
        }
        if (dimensions.size() != 1) return charts;
        var dimension = dimensions.get(0);
        if (rows.stream().map(row -> row.get(dimension.key())).distinct().count() != rows.size()) return charts;
        for (var measure : numbers) {
            boolean time = "TIME".equals(dimension.role());
            boolean nonnegative = rows.stream().allMatch(row -> numeric(row.get(measure.key())) && number(row.get(measure.key())) >= 0);
            String type = "BAR".equals(requested)?"BAR":"LINE".equals(requested)?"LINE":time ? ("AREA".equals(requested) && nonnegative ? "AREA" : "LINE") : "BAR";
            boolean addable = "SUM".equals(measure.aggregation()) || "COUNT".equals(measure.aggregation());
            boolean share = !time && ("PIE".equals(requested) || rows.size() <= 8) && addable && nonnegative
                    && rows.stream().mapToDouble(row -> number(row.get(measure.key()))).sum() > 0
                    && ("PIE".equals(requested) || dimension.key().contains("category") || dimension.key().contains("level")
                    || "AUTO".equals(requested)&&(dimension.key().contains("gender")||dimension.key().contains("channel")));
            if (share && "PIE".equals(requested)) charts.add(chart("PIE",dimension,measure,null,"按要求展示授权范围内本次返回分组的构成比例"));
            else {
                charts.add(chart(type,dimension,measure,null,time?"按时间顺序展示变化；缺失值保留为空":"按分组比较此指标，独立标注计量单位"));
                if(share)charts.add(chart("PIE",dimension,measure,null,"展示本次返回分组的构成比例"));
            }
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
            String dimText = dimension == null ? "" : dimension.label() + "“" + valueText(dimension, max.get(dimension.key())) + "”";
            if (valid.size() == 1) insights.add((dimText.isEmpty() ? "" : dimText)
                    + measure.label() + "为" + compactNumber(number(max.get(measure.key()))) + measure.unit() + "。");
            else if (dimension != null && valid.size() == 2) {
                // v1.5 两人对比：两行结果直接生成高低比较句式；有姓名列时附脱敏姓名便于阅读。
                var first = valid.get(0); var second = valid.get(1);
                double a = number(first.get(measure.key())), b = number(second.get(measure.key()));
                String cmp = a > b ? "高于" : a < b ? "低于" : "等于";
                String name1 = attachedName(first), name2 = attachedName(second);
                insights.add(dimText + (name1.isEmpty() ? "" : "（" + name1 + "）") + "的" + measure.label()
                        + "（" + compactNumber(a) + measure.unit() + "）" + cmp + dimText.replace("“","").replace("”","")
                        + "“" + valueText(dimension, second.get(dimension.key())) + "”" + (name2.isEmpty() ? "" : "（" + name2 + "）")
                        + "：" + compactNumber(b) + measure.unit() + "。");
            }
            else if (dimension != null) insights.add(dimText + "的"
                    + measure.label() + "最高，为" + compactNumber(number(max.get(measure.key()))) + measure.unit() + "。");
            Double total = aggregate(measure, rows);
            if (total != null && rows.size() > 1) insights.add(measure.label() + "：" + aggregationLabel(measure)
                    + "为" + compactNumber(total) + measure.unit() + "。");
        }
        if (insights.isEmpty()) insights.add("结果以明细为主，不对编号或缺失值进行数值汇总。");
        return new AnalysisSummary(overview, insights,
                List.of("以上计算仅基于当前返回结果；分组人数可能重叠，不能视为全库去重人数。均值与比率不直接相加。"));
    }

    /** 分析文本中的维度值统一走编码字典，保持与明细表一致。 */
    private String valueText(ColumnMeta dimension, Object value) {
        if (value == null) return "";
        var enumLabels = VALUE_LABELS.get(dimension.key());
        return enumLabels == null ? String.valueOf(value) : enumLabels.getOrDefault(String.valueOf(value), String.valueOf(value));
    }

    /** 词根拼接兜底：无法识别的键保持原文，避免编造含义。 */
    private String derivedLabel(String key) {
        String base = key;
        String unitSuffix = "";
        if (key.endsWith("_wan")) { unitSuffix = "（万元）"; base = key.substring(0, key.length() - 4); }
        else if (key.endsWith("_yi")) { unitSuffix = "（亿元）"; base = key.substring(0, key.length() - 3); }
        else if (key.endsWith("_pct")) { unitSuffix = "（%）"; base = key.substring(0, key.length() - 4); }
        String[] parts = base.split("_");
        var builder = new StringBuilder();
        for (String part : parts) {
            String token = LABEL_TOKENS.get(part);
            if (token == null) return key;
            builder.append(token);
        }
        return builder + unitSuffix;
    }

    /** 空结果说明附加数据覆盖范围（N3）：让用户区分“没有数据”与“真的是0”。 */
    private String emptySummary() {
        var coverage = insights == null ? null : insights.transactionCoverage();
        return "没有找到符合当前条件的数据，可以调整范围后重试。"
                + (coverage == null ? "" : "当前交易数据覆盖范围为 " + coverage + "，范围外日期没有数据。");
    }
    private String coverageNote() {
        var coverage = insights == null ? null : insights.transactionCoverage();
        return coverage == null ? "" : " 当前交易数据覆盖范围为 " + coverage + "。";
    }
    private boolean allZeroSingleRow(List<ColumnMeta> columns, List<Map<String, Object>> rows) {
        if (rows.size() != 1) return false;
        return measures(columns).stream().allMatch(column -> {
            Object value = rows.get(0).get(column.key());
            return value == null || (numeric(value) && number(value) == 0.0);
        });
    }

    /** 两行对比时附脱敏姓名（若结果含姓名列）。 */
    private String attachedName(Map<String, Object> row) {
        for (String key : new String[]{"customer_name", "customer_name_masked"}) {
            Object value = row.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    private boolean numeric(Object value) { return value instanceof Number && Double.isFinite(((Number) value).doubleValue()); }
    private double number(Object value) { return ((Number) value).doubleValue(); }
    private String compactNumber(double value) { return String.format(java.util.Locale.ROOT, Math.rint(value) == value ? "%.0f" : "%.2f", value); }
    private Map<String, Object> normalizeKeys(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalized = key.toLowerCase(java.util.Locale.ROOT);
            Object masked = value;
            if(value instanceof String text) {
                if(Set.of("customer_name", "customer_name_masked").contains(normalized)) masked=com.boc.nl2sql.common.privacy.CustomerMasking.name(text);
                if(Set.of("mobile", "mobile_masked", "phone").contains(normalized)) masked=com.boc.nl2sql.common.privacy.CustomerMasking.mobile(text);
                var enumLabels = VALUE_LABELS.get(normalized);
                if (enumLabels != null) masked = enumLabels.getOrDefault(text, text);
            }
            result.put(normalized, masked);
        });
        return result;
    }
    private String inferType(Object value) {
        if (value instanceof Number) return "NUMBER";
        if (value instanceof java.time.temporal.Temporal || value instanceof java.util.Date) return "DATE";
        return "TEXT";
    }
}

package com.boc.nl2sql.execution.domain;

/** 图表中的一组数值序列。key对应结果行字段，label是页面展示名称。 */
public record ChartSeries(String key, String label, String unit) {
    public ChartSeries(String key, String label) { this(key, label, ""); }
}

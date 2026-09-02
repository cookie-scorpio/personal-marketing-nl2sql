package com.boc.nl2sql.execution.domain;

/** 结果列的展示与统计语义，供脱敏、图表选择和数值口径判断共同使用。 */
public record ColumnMeta(String key, String label, String dataType, boolean sensitive,
                         String role, String unit, String aggregation, String weightKey) {
    public ColumnMeta(String key, String label, String dataType, boolean sensitive) {
        this(key, label, dataType, sensitive, null, null, null, null);
    }
}

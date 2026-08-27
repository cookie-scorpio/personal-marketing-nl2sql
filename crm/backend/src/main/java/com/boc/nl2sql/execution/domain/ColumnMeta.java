package com.boc.nl2sql.execution.domain;

public record ColumnMeta(String key, String label, String dataType, boolean sensitive,
                         String role, String unit, String aggregation, String weightKey) {
    public ColumnMeta(String key, String label, String dataType, boolean sensitive) {
        this(key, label, dataType, sensitive, null, null, null, null);
    }
}

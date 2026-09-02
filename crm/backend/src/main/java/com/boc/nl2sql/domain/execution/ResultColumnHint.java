package com.boc.nl2sql.domain.execution;

/** 模型给出的字段语义建议，必须与实际返回列匹配后才能参与展示。 */
public record ResultColumnHint(String key, String label, String role, String unit,
                               String aggregation, String weightKey) { }

package com.boc.nl2sql.model;

/** 大模型只依据SQL和无业务值的结果结构摘要判断是否明显偏离原问题。 */
public record SqlResultReview(boolean aligned, String reason) {
    public SqlResultReview {
        reason = reason == null || reason.isBlank() ? (aligned ? "结果结构与问题一致" : "结果结构与问题不一致") : reason.strip();
    }
}

package com.boc.nl2sql.domain.nl2sql;

public enum IntentType {
    CUSTOMER_FILTER,
    TRANSACTION_ANALYSIS,
    PRODUCT_HOLDING,
    MARKETING_ANALYSIS,
    /** 不属于固定模板、由模型根据元数据生成查询计划的自由分析。 */
    GENERIC_ANALYSIS,
    UNKNOWN
}

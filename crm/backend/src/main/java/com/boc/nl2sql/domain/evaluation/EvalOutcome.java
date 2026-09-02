package com.boc.nl2sql.domain.evaluation;

/** 单条评测样本的多维度结论；执行成功、SQL 匹配与结果一致性分别落在不同字段。 */
public enum EvalOutcome {
    /** 执行成功且 SQL 与结果均与金标一致。 */
    PASSED,
    /** 系统输出可用，但生成 SQL 与金标规范化后不一致。 */
    SQL_MISMATCH,
    /** 生成 SQL 与金标不一致，且两者执行结果也不同。 */
    RESULT_MISMATCH,
    /** 生成的 SQL 未能执行成功。 */
    EXECUTION_FAILED,
    /** 生成的 SQL 未通过安全/范围校验。 */
    VALIDATION_FAILED,
    /** 模型未能给出可执行的 SQL。 */
    INTERPRET_FAILED,
    /** 系统对该问题发起了澄清请求而非直接回答。 */
    CLARIFICATION_NEEDED,

    /** 兼容历史字符串的兜底值，仅在明细中出现未知结论时使用。 */
    UNKNOWN
}

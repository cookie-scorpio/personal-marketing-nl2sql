package com.boc.nl2sql.domain.evaluation;

/** 评测运行的触发来源。 */
public enum EvalRunTrigger {
    /** 评测集发布时自动触发。 */
    AUTO_PUBLISH,
    /** 审计员在评测后台手动重跑。 */
    MANUAL
}

package com.boc.nl2sql.domain.evaluation;

/** 评测运行自身的执行状态；样本质量结论由逐条明细的 outcome 表达。 */
public enum EvalRunStatus {
    PENDING,
    RUNNING,
    /** 全部样本都完成了评测流程（不代表全部通过）。 */
    SUCCESS,
    /** 运行中断（例如模型不可用、存储异常），已完成样本的结果保留。 */
    FAILED
}

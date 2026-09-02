package com.boc.nl2sql.conversation.domain;

/** 查询任务状态机的持久化状态代码。 */
public enum QueryStatus {
    RECEIVED,
    INTENT_ANALYZING,
    ASKING,
    SQL_GENERATING,
    VALIDATING,
    CONFIRMING,
    EXECUTING,
    RESULT_REVIEWING,
    PACKAGING,
    SUCCESS,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    REPAIRING,
    FALLING_BACK,
    DEGRADED;

    /** 终态不会再接受澄清、确认或处理器推进。 */
    public static boolean terminal(String status) {
        return java.util.Set.of("SUCCESS", "FAILED", "CANCELLED", "TIMED_OUT", "DEGRADED").contains(status);
    }
}

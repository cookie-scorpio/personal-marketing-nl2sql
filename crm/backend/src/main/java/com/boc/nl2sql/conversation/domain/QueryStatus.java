package com.boc.nl2sql.conversation.domain;

public enum QueryStatus {
    RECEIVED,
    INTENT_ANALYZING,
    ASKING,
    SQL_GENERATING,
    VALIDATING,
    CONFIRMING,
    EXECUTING,
    PACKAGING,
    SUCCESS,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    REPAIRING,
    FALLING_BACK,
    DEGRADED;

    public static boolean terminal(String status) {
        return java.util.Set.of("SUCCESS", "FAILED", "CANCELLED", "TIMED_OUT", "DEGRADED").contains(status);
    }
}

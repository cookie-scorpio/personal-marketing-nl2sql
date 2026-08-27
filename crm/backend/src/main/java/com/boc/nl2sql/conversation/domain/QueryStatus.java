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
    CANCELLED
}

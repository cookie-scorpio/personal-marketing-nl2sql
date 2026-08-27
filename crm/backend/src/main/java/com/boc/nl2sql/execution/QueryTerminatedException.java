package com.boc.nl2sql.execution;

/** 取消和超时永不触发模型修复或模板执行。 */
public class QueryTerminatedException extends RuntimeException {
    private final boolean timedOut;
    public QueryTerminatedException(boolean timedOut) {
        super(timedOut ? "SQL执行超时，已终止查询" : "查询已取消");
        this.timedOut = timedOut;
    }
    public boolean timedOut() { return timedOut; }
}

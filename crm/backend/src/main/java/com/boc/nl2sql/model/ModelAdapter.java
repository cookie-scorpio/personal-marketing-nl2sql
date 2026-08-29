package com.boc.nl2sql.model;

import com.boc.nl2sql.authorization.domain.CurrentUser;

/** DeepSeek、Qwen 和本地 Mock 必须输出同一种结构化查询解释。 */
public interface ModelAdapter {
    String provider();
    QueryInterpretation interpret(String queryText, CurrentUser user);
    default QueryInterpretation interpret(String queryText, CurrentUser user, java.util.function.BooleanSupplier active) {
        if (!active.getAsBoolean()) throw new com.boc.nl2sql.execution.QueryTerminatedException(false);
        return interpret(queryText, user);
    }

    default QueryInterpretation repair(String queryText, CurrentUser user, String failedSql, String reason) {
        throw new com.boc.nl2sql.common.exception.BusinessException(503101, "当前模型不支持SQL修复");
    }
    default QueryInterpretation interpret(String text,CurrentUser user,java.util.function.BooleanSupplier active,boolean thinking){return interpret(text,user,active);}
    default QueryInterpretation repair(String text,CurrentUser user,String sql,String reason,boolean thinking){return repair(text,user,sql,reason);}
    default SqlResultReview reviewResult(String text, CurrentUser user, String sql,
                                         java.util.Map<String,Object> resultSummary, boolean thinking) {
        return new SqlResultReview(true, "当前模型不需要额外结果结构复核");
    }

    default boolean available() {
        return true;
    }
}

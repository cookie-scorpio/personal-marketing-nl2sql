package com.boc.nl2sql.model;

import com.boc.nl2sql.authorization.domain.CurrentUser;

/** DeepSeek、Qwen 和本地 Mock 必须输出同一种结构化查询解释。 */
public interface ModelAdapter {
    String provider();
    QueryInterpretation interpret(String queryText, CurrentUser user);

    default boolean available() {
        return true;
    }
}

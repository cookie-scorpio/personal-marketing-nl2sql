package com.boc.nl2sql.model;

import com.boc.nl2sql.nl2sql.domain.SemanticQuery;

/** DeepSeek、Qwen 和本地 Mock 必须输出同一种受控语义对象。 */
public interface ModelAdapter {
    String provider();
    SemanticQuery interpret(String queryText);
}

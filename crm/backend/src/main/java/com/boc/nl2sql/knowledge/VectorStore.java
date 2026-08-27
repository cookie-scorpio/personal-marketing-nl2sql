package com.boc.nl2sql.knowledge;

import java.util.List;

/** 后续 Milvus 适配点，避免核心层依赖具体向量数据库 SDK。 */
public interface VectorStore {
    List<String> search(float[] vector, int limit);
}

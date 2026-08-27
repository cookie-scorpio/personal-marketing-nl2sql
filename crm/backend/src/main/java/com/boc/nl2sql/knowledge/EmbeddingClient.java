package com.boc.nl2sql.knowledge;

/** 后续 BGE-M3 推理服务接入点；MVP 不加载本地模型。 */
public interface EmbeddingClient {
    float[] embed(String text);
}

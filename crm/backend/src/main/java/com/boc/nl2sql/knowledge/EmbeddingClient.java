package com.boc.nl2sql.knowledge;

/** 后续 BCG-E3 推理服务接入点；V1.0不加载本地模型，也不主动调用外置服务。 */
public interface EmbeddingClient {
    float[] embed(String text);
}

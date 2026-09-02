package com.boc.nl2sql.knowledge;

/** BCG-E3 推理服务的预留接入点；当前实现不加载本地模型，也不主动调用外部服务。 */
public interface EmbeddingClient {
    float[] embed(String text);
}

package com.boc.nl2sql.knowledge;

/** 文本向量化接入点；实现负责调用外部Embedding服务，失败时抛出运行时异常由调用方降级。 */
public interface EmbeddingClient {
    /** 服务与密钥配置齐全时返回true；false时调用方直接走无检索的降级路径。 */
    default boolean available() {
        return true;
    }

    float[] embed(String text);

    /** 当前嵌入模型名；参与向量内容哈希，模型变更后自动触发重新向量化。 */
    String model();

    /** 输出向量维度；参与向量内容哈希，维度变更后自动触发重新向量化。 */
    int dim();
}

package com.boc.nl2sql.knowledge;

import java.util.List;
import java.util.Set;

/**
 * 向量存取与相似检索的适配点，避免核心层依赖具体向量数据库 SDK。
 * 当前实现为MySQL持久化+进程内余弦检索；量级上来后可换Milvus等外置向量库，接口不变。
 */
public interface VectorStore {
    /** 按业务类型做余弦检索，返回相似度降序、不低于minScore的前limit条命中。 */
    List<VectorHit> search(String bizType, float[] vector, int limit, double minScore);

    /** 新增或覆盖一条向量（唯一键为biz_type+ref_id）；实现负责持久化与内存索引同步。 */
    void upsert(String bizType, String refId, String contentText, String contentHash, float[] vector, String model);

    /** 从持久层加载指定模型的全量向量到内存索引；每次索引刷新开始时调用。 */
    void load(String model);

    /** 内存索引中是否已有内容哈希完全一致的向量；一致时跳过重复embedding调用。 */
    boolean contains(String bizType, String refId, String contentHash);

    /** 删除指定业务类型中不在refIds集合内的向量；源数据删除或停用时清理脏向量。 */
    void retain(String bizType, Set<String> refIds);
}

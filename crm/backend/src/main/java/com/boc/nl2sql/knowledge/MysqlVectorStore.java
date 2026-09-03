package com.boc.nl2sql.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MySQL持久化+进程内余弦检索的向量库实现。
 * 向量按(biz_type,ref_id)唯一存放在vector_embedding表，加载或写入时归一化后放进
 * 不可变内存快照；检索只读快照做点积，无锁、毫秒级，不触发任何数据库访问。
 * Embedding调用由EmbeddingIndexService负责，本类只管存取与相似度计算。
 */
@Component
public class MysqlVectorStore implements VectorStore {
    private record Entry(String contentText, String contentHash, float[] normalizedVector) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    /** biz_type -> (ref_id -> entry)；整体不可变，写操作重建后原子替换。 */
    private volatile Map<String, Map<String, Entry>> snapshot = Map.of();

    public MysqlVectorStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<VectorHit> search(String bizType, float[] vector, int limit, double minScore) {
        float[] query = normalize(vector);
        if (query == null) return List.of();
        Map<String, Entry> entries = snapshot.get(bizType);
        if (entries == null || entries.isEmpty()) return List.of();
        var hits = new ArrayList<VectorHit>();
        for (var e : entries.entrySet()) {
            double score = dot(query, e.getValue().normalizedVector());
            if (score >= minScore) {
                hits.add(new VectorHit(e.getKey(), e.getValue().contentText(), score));
            }
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : List.copyOf(hits);
    }

    @Override
    public synchronized void load(String model) {
        var loaded = new HashMap<String, Map<String, Entry>>();
        jdbc.query("SELECT biz_type, ref_id, content_hash, content_text, embedding FROM vector_embedding WHERE model = ?",
                rs -> {
                    String bizType = rs.getString("biz_type");
                    String refId = rs.getString("ref_id");
                    float[] vector = objectMapper.readValue(rs.getString("embedding"), float[].class);
                    float[] normalized = normalize(vector);
                    if (normalized == null) return;
                    loaded.computeIfAbsent(bizType, k -> new HashMap<>())
                            .put(refId, new Entry(rs.getString("content_text"), rs.getString("content_hash"), normalized));
                }, model);
        this.snapshot = Map.copyOf(loaded);
    }

    @Override
    public synchronized void upsert(String bizType, String refId, String contentText, String contentHash,
                                    float[] vector, String model) {
        float[] normalized = normalize(vector);
        if (normalized == null) {
            throw new IllegalArgumentException("零向量无法入库：" + bizType + "/" + refId);
        }
        jdbc.update("""
                INSERT INTO vector_embedding(biz_type, ref_id, content_hash, content_text, embedding, dim, model)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    content_hash = VALUES(content_hash),
                    content_text = VALUES(content_text),
                    embedding = VALUES(embedding),
                    dim = VALUES(dim),
                    model = VALUES(model)
                """, bizType, refId, contentHash, contentText, objectMapper.writeValueAsString(vector),
                vector.length, model);
        var next = new HashMap<String, Map<String, Entry>>(snapshot);
        var entries = new HashMap<>(next.getOrDefault(bizType, Map.of()));
        entries.put(refId, new Entry(contentText, contentHash, normalized));
        next.put(bizType, Map.copyOf(entries));
        this.snapshot = Map.copyOf(next);
    }

    @Override
    public boolean contains(String bizType, String refId, String contentHash) {
        Entry entry = snapshot.getOrDefault(bizType, Map.of()).get(refId);
        return entry != null && entry.contentHash().equals(contentHash);
    }

    @Override
    public synchronized void retain(String bizType, Set<String> refIds) {
        if (refIds.isEmpty()) {
            jdbc.update("DELETE FROM vector_embedding WHERE biz_type = ?", bizType);
        } else {
            String placeholders = refIds.stream().map(r -> "?").collect(Collectors.joining(","));
            var args = new ArrayList<Object>();
            args.add(bizType);
            args.addAll(refIds);
            jdbc.update("DELETE FROM vector_embedding WHERE biz_type = ? AND ref_id NOT IN (" + placeholders + ")",
                    args.toArray());
        }
        var next = new HashMap<String, Map<String, Entry>>(snapshot);
        next.put(bizType, next.getOrDefault(bizType, Map.of()).entrySet().stream()
                .filter(e -> refIds.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        this.snapshot = Map.copyOf(next);
    }

    /** L2归一化；零向量返回null，调用方跳过该条。 */
    private static float[] normalize(float[] vector) {
        if (vector == null || vector.length == 0) return null;
        double sum = 0;
        for (float v : vector) sum += (double) v * v;
        if (sum <= 0) return null;
        double norm = Math.sqrt(sum);
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) normalized[i] = (float) (vector[i] / norm);
        return normalized;
    }

    private static double dot(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += (double) a[i] * b[i];
        return sum;
    }
}

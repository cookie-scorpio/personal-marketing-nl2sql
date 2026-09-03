package com.boc.nl2sql.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 向量索引构建：定时把业务术语与标准示例向量化后写入向量库。
 * 内容哈希（模型+维度+文本）命中时跳过embedding调用，只有新增或变更的内容才会产生请求；
 * 源数据删除或停用后由retain清理脏向量。任何失败只影响检索增强，不阻塞查询主链路。
 */
@Component
public class EmbeddingIndexService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexService.class);
    public static final String BIZ_TERM = "BUSINESS_TERM";
    public static final String BIZ_EXAMPLE = "NL2SQL_EXAMPLE";

    private final EmbeddingClient client;
    private final VectorStore store;
    private final JdbcTemplate jdbc;
    private final long refreshMs;
    private final ScheduledExecutorService scheduler;

    public EmbeddingIndexService(EmbeddingClient client, VectorStore store, JdbcTemplate jdbc,
                                 @Value("${app.rag.index-refresh-ms:300000}") long refreshMs) {
        if (refreshMs < 30_000) {
            throw new IllegalArgumentException("向量索引刷新间隔不得低于30秒，避免频繁调用embedding服务");
        }
        this.client = client;
        this.store = store;
        this.jdbc = jdbc;
        this.refreshMs = refreshMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rag-index-builder");
            thread.setDaemon(true);
            return thread;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!client.available()) {
            log.info("Embedding服务未配置或RAG已关闭，向量索引不启动，检索增强走全量术语降级");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::refreshSafely, 3_000, refreshMs, TimeUnit.MILLISECONDS);
        log.info("向量索引刷新任务已启动：间隔{}ms", refreshMs);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    private void refreshSafely() {
        try {
            store.load(client.model());
            int terms = indexTerms();
            int examples = indexExamples();
            log.info("向量索引刷新完成：业务术语{}条，标准示例{}条", terms, examples);
        } catch (RuntimeException exception) {
            log.warn("向量索引刷新失败，本次检索增强不可用：{}", exception.getMessage());
        }
    }

    private int indexTerms() {
        var rows = jdbc.queryForList("""
                SELECT term_code, standard_name, synonyms, definition_text, mapped_object
                  FROM business_term WHERE enabled = TRUE ORDER BY id LIMIT 100
                """);
        var desired = new LinkedHashMap<String, String>();
        for (var row : rows) {
            desired.put(String.valueOf(row.get("term_code")),
                    BusinessTermCatalog.renderTerm(row.get("standard_name"), row.get("synonyms"),
                            row.get("definition_text"), row.get("mapped_object")));
        }
        upsertMissing(BIZ_TERM, desired);
        store.retain(BIZ_TERM, desired.keySet());
        return desired.size();
    }

    private int indexExamples() {
        var rows = jdbc.queryForList("""
                SELECT example_code, question_text FROM nl2sql_example WHERE enabled = TRUE ORDER BY id
                """);
        var desired = new LinkedHashMap<String, String>();
        for (var row : rows) {
            desired.put(String.valueOf(row.get("example_code")), String.valueOf(row.get("question_text")));
        }
        upsertMissing(BIZ_EXAMPLE, desired);
        store.retain(BIZ_EXAMPLE, desired.keySet());
        return desired.size();
    }

    /** 只对新增或内容变更的条目调用embedding；哈希一致的直接跳过。 */
    private void upsertMissing(String bizType, Map<String, String> desired) {
        for (var entry : desired.entrySet()) {
            String hash = contentHash(client.model(), client.dim(), entry.getValue());
            if (store.contains(bizType, entry.getKey(), hash)) continue;
            float[] vector = client.embed(entry.getValue());
            store.upsert(bizType, entry.getKey(), entry.getValue(), hash, vector, client.model());
        }
    }

    static String contentHash(String model, int dim, String content) {
        String input = model + "|" + dim + "|" + content;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256不可用", impossible);
        }
    }
}

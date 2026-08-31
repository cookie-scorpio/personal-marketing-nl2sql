package com.boc.nl2sql.model;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 数据概览（最新快照日期、交易覆盖范围）的缓存读取。
 * 同时服务于两处：提示词动态注入（帮助模型判断口径）与空结果提示（N3）。
 * 缓存10分钟，查询失败时返回null并在调用侧静默降级——数据概览缺失不影响查询主链路。
 */
@Component
public class DataInsightProvider {
    private record Insight(LocalDate latestSnapshot, LocalDate txFrom, LocalDate txTo) {}
    private static final long TTL_MS = 10 * 60 * 1000L;
    private final JdbcTemplate jdbc;
    private final AtomicReference<Insight> cache = new AtomicReference<>();

    public DataInsightProvider(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private Insight insight() {
        Insight cached = cache.get();
        if (cached != null && System.currentTimeMillis() - loadedAt(cached) < TTL_MS) return cached;
        try {
            var snap = jdbc.queryForObject("SELECT MAX(snapshot_date) FROM fct_product_holding", LocalDate.class);
            var from = jdbc.queryForObject("SELECT MIN(transaction_date) FROM fct_transaction", LocalDate.class);
            var to = jdbc.queryForObject("SELECT MAX(transaction_date) FROM fct_transaction", LocalDate.class);
            Insight fresh = new Insight(snap, from, to);
            cache.set(fresh);
            loadedAt = System.currentTimeMillis();
            return fresh;
        } catch (RuntimeException degraded) {
            return cached;
        }
    }

    private volatile long loadedAt;
    private long loadedAt(Insight ignored) { return loadedAt; }

    /** 最新持有快照日期；不可用时返回 null，调用方不注入该行。 */
    public LocalDate latestSnapshot() { var i = insight(); return i == null ? null : i.latestSnapshot(); }

    /** 交易数据覆盖范围描述；不可用时返回 null。 */
    public String transactionCoverage() {
        var i = insight();
        if (i == null || i.txFrom() == null || i.txTo() == null) return null;
        return i.txFrom() + " 至 " + i.txTo();
    }
}

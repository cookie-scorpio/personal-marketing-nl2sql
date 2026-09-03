package com.boc.nl2sql.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalAugmentorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 2);
    private static final LocalDate SNAPSHOT = LocalDate.of(2026, 8, 26);

    /** 固定返回预设向量的假客户端；failure=true时模拟服务异常。 */
    private static final class FakeClient implements EmbeddingClient {
        private final boolean available;
        private final boolean failure;

        private FakeClient(boolean available, boolean failure) {
            this.available = available;
            this.failure = failure;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public float[] embed(String text) {
            if (failure) throw new IllegalStateException("embedding服务超时");
            return new float[]{1, 0};
        }

        @Override
        public String model() {
            return "fake-embed";
        }

        @Override
        public int dim() {
            return 2;
        }
    }

    /** 返回预设命中的假向量库。 */
    private static final class FakeStore implements VectorStore {
        private final List<VectorHit> termHits;
        private final List<VectorHit> exampleHits;

        private FakeStore(List<VectorHit> termHits, List<VectorHit> exampleHits) {
            this.termHits = termHits;
            this.exampleHits = exampleHits;
        }

        @Override
        public List<VectorHit> search(String bizType, float[] vector, int limit, double minScore) {
            return "BUSINESS_TERM".equals(bizType)
                    ? termHits.stream().limit(limit).toList()
                    : exampleHits.stream().limit(limit).toList();
        }

        @Override
        public void upsert(String bizType, String refId, String contentText, String contentHash, float[] vector, String model) {
        }

        @Override
        public void load(String model) {
        }

        @Override
        public boolean contains(String bizType, String refId, String contentHash) {
            return false;
        }

        @Override
        public void retain(String bizType, java.util.Set<String> refIds) {
        }
    }

    @Test
    void returnsNullWhenClientUnavailable() {
        var augmentor = new RetrievalAugmentor(new FakeClient(false, false), new FakeStore(List.of(), List.of()),
                mock(JdbcTemplate.class), 5, 3, 0.3);
        assertThat(augmentor.augment("高净值客户有多少", null)).isNull();
    }

    @Test
    void degradesToNullWhenEmbeddingFails() {
        var augmentor = new RetrievalAugmentor(new FakeClient(true, true), new FakeStore(List.of(), List.of()),
                mock(JdbcTemplate.class), 5, 3, 0.3);
        assertThat(augmentor.augment("高净值客户有多少", null)).isNull();
    }

    @Test
    void degradesToNullWhenNoHitPassesMinScore() {
        var augmentor = new RetrievalAugmentor(new FakeClient(true, false), new FakeStore(List.of(), List.of()),
                mock(JdbcTemplate.class), 5, 3, 0.3);
        assertThat(augmentor.augment("无关问题", null)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsTermContextAndRenderedExamplesInSimilarityOrder() {
        var today = LocalDate.now();
        var store = new FakeStore(
                List.of(new VectorHit("HIGH_NET_WORTH", "高净值客户（同义表达：高净客群）：总资产不低于100万", 0.8)),
                List.of(new VectorHit("EX_HNW_COUNT_AVG", "高净值客户有多少人？平均资产多少", 0.75),
                        new VectorHit("EX_DEPOSIT_BAND", "客户存款市值分档分布", 0.6)));
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.<String[]>of(
                new String[]{"EX_DEPOSIT_BAND", "客户存款市值分档分布",
                        "SELECT ... AND h.snapshot_date = '{snapshot-date}' AND t.transaction_date >= '{today-30}'"},
                new String[]{"EX_HNW_COUNT_AVG", "高净值客户有多少人？平均资产多少",
                        "SELECT COUNT(*) AS hnw_customer_count FROM dim_customer c WHERE c.status_code = 'ACTIVE'"}));
        var augmentor = new RetrievalAugmentor(new FakeClient(true, false), store, jdbc, 5, 3, 0.3);

        var augmented = augmentor.augment("高净值客户有多少", SNAPSHOT);

        assertThat(augmented).isNotNull();
        assertThat(augmented.termContext()).contains("高净值客户（同义表达：高净客群）");
        // 按相似度顺序返回；占位符替换为真实日期
        assertThat(augmented.examples()).hasSize(2);
        assertThat(augmented.examples().get(0).question()).isEqualTo("高净值客户有多少人？平均资产多少");
        assertThat(augmented.examples().get(0).sql()).doesNotContain("{");
        assertThat(augmented.examples().get(1).sql())
                .contains("h.snapshot_date = '2026-08-26'")
                .contains("t.transaction_date >= '" + today.minusDays(30) + "'");
    }

    @Test
    void dropsExamplesWhoseSnapshotDateIsUnknown() {
        var store = new FakeStore(List.of(),
                List.of(new VectorHit("EX_DEPOSIT_BAND", "客户存款市值分档分布", 0.6)));
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.<String[]>of(
                new String[]{"EX_DEPOSIT_BAND", "客户存款市值分档分布",
                        "SELECT ... WHERE h.snapshot_date = '{snapshot-date}'"}));
        var augmentor = new RetrievalAugmentor(new FakeClient(true, false), store, jdbc, 5, 3, 0.3);

        // 快照日期未知但术语命中时，示例被丢弃，术语上下文仍返回空串
        var augmented = augmentor.augment("客户存款分布", null);
        assertThat(augmented).isNotNull();
        assertThat(augmented.examples()).isEmpty();
    }

    @Test
    void rendersAllSupportedDateTokens() {
        String sql = "d >= '{today}' AND d2 >= '{today-90}' AND d3 >= '{year-start}' AND d4 >= '{month-start}'";
        assertThat(RetrievalAugmentor.renderSql(sql, TODAY, SNAPSHOT))
                .isEqualTo("d >= '2026-09-02' AND d2 >= '" + TODAY.minusDays(90)
                        + "' AND d3 >= '2026-01-01' AND d4 >= '2026-09-01'");
    }

    @Test
    void leavesSqlWithoutTokensUntouched() {
        assertThat(RetrievalAugmentor.renderSql("SELECT 1", TODAY, null)).isEqualTo("SELECT 1");
    }
}

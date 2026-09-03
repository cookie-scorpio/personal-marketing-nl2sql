package com.boc.nl2sql.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MysqlVectorStoreTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MysqlVectorStore store = new MysqlVectorStore(jdbc, JsonMapper.builder().build());

    @Test
    void searchRanksByCosineSimilarityAndAppliesLimitAndMinScore() {
        store.upsert("T", "a", "条目a", "hash-a", new float[]{1, 0, 0}, "test-embed");
        store.upsert("T", "b", "条目b", "hash-b", new float[]{0, 1, 0}, "test-embed");
        store.upsert("T", "c", "条目c", "hash-c", new float[]{1, 1, 0}, "test-embed");

        var hits = store.search("T", new float[]{1, 0, 0}, 3, 0.5);
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).refId()).isEqualTo("a");
        assertThat(hits.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(hits.get(1).refId()).isEqualTo("c");
        assertThat(hits.get(1).score()).isCloseTo(0.7071, org.assertj.core.data.Offset.offset(1e-3));

        assertThat(store.search("T", new float[]{1, 0, 0}, 1, 0.5)).hasSize(1);
        // 方向正交的条目低于阈值被过滤
        assertThat(store.search("T", new float[]{0, 1, 0}, 3, 0.99)).hasSize(1);
    }

    @Test
    void searchIsIsolatedByBizType() {
        store.upsert("T", "a", "术语", "hash-a", new float[]{1, 0}, "test-embed");
        store.upsert("E", "a", "示例", "hash-e", new float[]{1, 0}, "test-embed");

        assertThat(store.search("T", new float[]{1, 0}, 5, 0.5)).singleElement()
                .satisfies(hit -> assertThat(hit.contentText()).isEqualTo("术语"));
    }

    @Test
    void containsMatchesRefIdAndContentHash() {
        store.upsert("T", "a", "原文", "hash-1", new float[]{1, 0}, "test-embed");
        assertThat(store.contains("T", "a", "hash-1")).isTrue();
        assertThat(store.contains("T", "a", "hash-2")).isFalse();
        assertThat(store.contains("T", "missing", "hash-1")).isFalse();
    }

    @Test
    void retainKeepsOnlyGivenRefIds() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        store.upsert("T", "a", "保留", "hash-a", new float[]{1, 0}, "test-embed");
        store.upsert("T", "b", "清理", "hash-b", new float[]{1, 0}, "test-embed");

        store.retain("T", Set.of("a"));

        assertThat(store.search("T", new float[]{1, 0}, 5, 0.5)).singleElement()
                .satisfies(hit -> assertThat(hit.refId()).isEqualTo("a"));
        assertThat(store.contains("T", "b", "hash-b")).isFalse();
    }

    @Test
    void zeroVectorIsRejected() {
        assertThatThrownBy(() -> store.upsert("T", "bad", "原文", "hash", new float[]{0, 0, 0}, "m"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

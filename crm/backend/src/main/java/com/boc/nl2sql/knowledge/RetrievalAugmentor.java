package com.boc.nl2sql.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 检索增强器：把用户问题向量化后，从向量库取相关业务术语与相似标准示例，
 * 供Nl2SqlPrompts注入提示词。任何一步失败都返回null，调用方回退到全量术语注入，
 * 检索增强永远不阻断查询主链路。
 */
@Component
public class RetrievalAugmentor {
    private static final Logger log = LoggerFactory.getLogger(RetrievalAugmentor.class);

    /** 标准示例SQL中的日期占位符；渲染时替换为运行时真实值，避免示例随时间过期。 */
    private static final Pattern DATE_TOKEN = Pattern.compile("\\{today(-\\d+)?\\}|\\{year-start\\}|\\{month-start\\}|\\{snapshot-date\\}");

    /** 一条可注入提示词的标准示例；sql已完成日期占位符替换。 */
    public record Example(String question, String sql) {}

    /** 检索结果：termContext为按提示词格式拼好的相关术语，examples为相似问题示例。 */
    public record Augmented(String termContext, List<Example> examples) {}

    private final EmbeddingClient client;
    private final VectorStore store;
    private final JdbcTemplate jdbc;
    private final int termTopK;
    private final int exampleTopK;
    private final double minScore;

    public RetrievalAugmentor(EmbeddingClient client, VectorStore store, JdbcTemplate jdbc,
                              @Value("${app.rag.term-top-k:5}") int termTopK,
                              @Value("${app.rag.example-top-k:3}") int exampleTopK,
                              @Value("${app.rag.min-score:0.3}") double minScore) {
        if (termTopK <= 0 || exampleTopK <= 0) {
            throw new IllegalArgumentException("检索条数必须为正数");
        }
        if (minScore < 0 || minScore >= 1) {
            throw new IllegalArgumentException("最低相似度阈值必须在[0,1)区间");
        }
        this.client = client;
        this.store = store;
        this.jdbc = jdbc;
        this.termTopK = termTopK;
        this.exampleTopK = exampleTopK;
        this.minScore = minScore;
    }

    /**
     * 按问题相似度组装提示词素材；snapshotDate为最新持有快照日期，用于渲染示例中的
     * {snapshot-date}占位符，未知时包含该占位符的示例被跳过。返回null表示本次降级。
     */
    public Augmented augment(String question, LocalDate snapshotDate) {
        if (!client.available()) return null;
        try {
            float[] vector = client.embed(question);
            var termHits = store.search(EmbeddingIndexService.BIZ_TERM, vector, termTopK, minScore);
            var exampleHits = store.search(EmbeddingIndexService.BIZ_EXAMPLE, vector, exampleTopK, minScore);
            if (termHits.isEmpty() && exampleHits.isEmpty()) return null;
            return new Augmented(termContext(termHits), loadExamples(exampleHits, snapshotDate));
        } catch (RuntimeException exception) {
            log.warn("检索增强失败，本次降级为全量术语注入：{}", exception.getMessage());
            return null;
        }
    }

    private String termContext(List<VectorHit> hits) {
        return hits.stream().map(VectorHit::contentText).collect(Collectors.joining("\n"));
    }

    /** 按相似度顺序加载启用中的示例；日期占位符无法解析的示例直接丢弃。 */
    private List<Example> loadExamples(List<VectorHit> hits, LocalDate snapshotDate) {
        if (hits.isEmpty()) return List.of();
        var placeholders = hits.stream().map(h -> "?").collect(Collectors.joining(","));
        var args = hits.stream().map(VectorHit::refId).toArray();
        Map<String, String[]> rows = jdbc.query(
                "SELECT example_code, question_text, sql_text FROM nl2sql_example WHERE enabled = TRUE AND example_code IN ("
                        + placeholders + ")",
                (rs, i) -> new String[]{rs.getString("example_code"), rs.getString("question_text"), rs.getString("sql_text")},
                args).stream().collect(Collectors.toMap(row -> row[0], row -> row, (a, b) -> a));
        var examples = new ArrayList<Example>();
        for (var hit : hits) {
            String[] row = rows.get(hit.refId());
            if (row == null) continue;
            String sql = renderSql(row[2], LocalDate.now(), snapshotDate);
            if (sql != null) examples.add(new Example(row[1], sql));
        }
        return List.copyOf(examples);
    }

    /** 替换SQL中的日期占位符；{snapshot-date}在快照日期未知时返回null表示丢弃该示例。 */
    static String renderSql(String sql, LocalDate today, LocalDate snapshotDate) {
        Matcher matcher = DATE_TOKEN.matcher(sql);
        var rendered = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            String value = switch (token) {
                case "{snapshot-date}" -> snapshotDate == null ? null : snapshotDate.toString();
                case "{year-start}" -> LocalDate.of(today.getYear(), 1, 1).toString();
                case "{month-start}" -> LocalDate.of(today.getYear(), today.getMonthValue(), 1).toString();
                case "{today}" -> today.toString();
                default -> today.plusDays(Long.parseLong(token.substring(6, token.length() - 1))).toString();
            };
            if (value == null) return null;
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}

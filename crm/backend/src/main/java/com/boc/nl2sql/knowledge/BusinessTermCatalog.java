package com.boc.nl2sql.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/** 从MySQL读取已启用业务术语，作为大模型SQL生成时的明确口径，不依赖尚未接入的RAG。 */
@Service
public class BusinessTermCatalog {
    private final JdbcTemplate jdbcTemplate;

    public BusinessTermCatalog(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String promptContext() {
        return jdbcTemplate.queryForList("""
                SELECT standard_name, synonyms, definition_text, mapped_object
                  FROM business_term WHERE enabled = TRUE ORDER BY id LIMIT 100
                """).stream().map(row -> renderTerm(row.get("standard_name"), row.get("synonyms"),
                        row.get("definition_text"), row.get("mapped_object")))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 业务术语的提示词渲染格式。
     * 检索增强的向量化文本必须复用本格式：格式变化会改变内容哈希，自动触发重新向量化。
     */
    public static String renderTerm(Object standardName, Object synonyms, Object definitionText, Object mappedObject) {
        return standardName + "（同义表达：" + (synonyms == null ? "" : synonyms) + "）：" + definitionText
                + "；对应字段：" + mappedObject;
    }
}

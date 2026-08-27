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
                """).stream().map(row -> row.get("standard_name") + "（同义表达：" + row.get("synonyms")
                + "）：" + row.get("definition_text") + "；对应字段：" + row.get("mapped_object"))
                .collect(Collectors.joining("\n"));
    }
}

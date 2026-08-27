package com.boc.nl2sql.model;

import com.boc.nl2sql.nl2sql.domain.ClarificationQuestion;
import com.boc.nl2sql.nl2sql.domain.SemanticQuery;

/**
 * 规则或大模型对一次自然语言问题的统一解释结果。
 *
 * <p>固定场景只返回受控语义对象，由 {@code SqlPlanner} 生成模板 SQL；自由问题可以携带模型生成的
 * SQL，但仍必须通过服务端的只读、对象白名单、行数上限和风险校验。</p>
 */
public record QueryInterpretation(
        SemanticQuery semantic,
        String source,
        double confidence,
        String generatedSql,
        String title,
        String preferredDisplay,
        ClarificationQuestion clarification
) {
    public static QueryInterpretation rule(SemanticQuery semantic) {
        return new QueryInterpretation(semantic, "RULE", 1.0, null, null, "AUTO", null);
    }

    public boolean hasGeneratedSql() {
        return generatedSql != null && !generatedSql.isBlank();
    }
}

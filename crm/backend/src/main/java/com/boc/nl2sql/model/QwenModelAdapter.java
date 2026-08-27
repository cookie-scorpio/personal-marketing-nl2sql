package com.boc.nl2sql.model;

import com.boc.nl2sql.nl2sql.application.RuleBasedSemanticParser;
import com.boc.nl2sql.nl2sql.domain.SemanticQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Qwen3-32B 本地服务适配位置；API 未配置时保持与 Mock 相同的可重复行为。 */
@Component
public class QwenModelAdapter implements ModelAdapter {
    private final RuleBasedSemanticParser fallback;
    private final String baseUrl;

    public QwenModelAdapter(RuleBasedSemanticParser fallback,
                            @Value("${app.model.qwen.base-url:}") String baseUrl) {
        this.fallback = fallback;
        this.baseUrl = baseUrl;
    }

    @Override
    public String provider() {
        return "qwen";
    }

    @Override
    public SemanticQuery interpret(String queryText) {
        return fallback.parse(queryText);
    }

    public boolean apiConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}

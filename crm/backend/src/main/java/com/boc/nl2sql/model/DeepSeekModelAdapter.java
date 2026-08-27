package com.boc.nl2sql.model;

import com.boc.nl2sql.nl2sql.application.RuleBasedSemanticParser;
import com.boc.nl2sql.nl2sql.domain.SemanticQuery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DeepSeek 前期测试适配器。
 *
 * <p>API 地址留空时使用确定性规则模拟响应；后续只需在本类内增加兼容接口调用，不影响业务编排。</p>
 */
@Component
public class DeepSeekModelAdapter implements ModelAdapter {
    private final RuleBasedSemanticParser fallback;
    private final String baseUrl;

    public DeepSeekModelAdapter(RuleBasedSemanticParser fallback,
                                @Value("${app.model.deepseek.base-url:}") String baseUrl) {
        this.fallback = fallback;
        this.baseUrl = baseUrl;
    }

    @Override
    public String provider() {
        return "deepseek";
    }

    @Override
    public SemanticQuery interpret(String queryText) {
        // MVP 明确不发送外部请求，避免在 API 尚未确定时形成不稳定协议依赖。
        return fallback.parse(queryText);
    }

    public boolean apiConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}

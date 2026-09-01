package com.boc.nl2sql.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * DeepSeek V4 Flash的Chat Completions适配器。
 * 规划、修复、复核的通用协议见OpenAiCompatibleModelAdapter；DeepSeek思考模式用thinking字段控制。
 */
@Component
public class DeepSeekModelAdapter extends OpenAiCompatibleModelAdapter {

    public DeepSeekModelAdapter(ObjectMapper objectMapper, Nl2SqlPrompts prompts,
                                @Value("${app.model.deepseek.base-url:}") String baseUrl,
                                @Value("${app.model.deepseek.api-key:}") String apiKey,
                                @Value("${app.model.deepseek.model:}") String model,
                                @Value("${app.model.deepseek.thinking-enabled:true}") boolean thinkingEnabled,
                                @Value("${app.model.deepseek.max-tokens:16384}") int maxTokens,
                                @Value("${app.model.deepseek.retry-max-tokens:32768}") int retryMaxTokens,
                                @Value("${app.model.deepseek.read-timeout-seconds:120}") int readTimeoutSeconds,
                                @Value("${app.model.deepseek.tools-enabled:true}") boolean toolsEnabled,
                                @Value("${app.model.deepseek.max-tool-rounds:3}") int maxToolRounds,
                                @Value("${app.model.deepseek.result-review-enabled:true}") boolean resultReviewEnabled) {
        super(objectMapper, prompts, baseUrl, apiKey, model, thinkingEnabled, maxTokens, retryMaxTokens,
                readTimeoutSeconds, toolsEnabled, maxToolRounds, resultReviewEnabled);
    }

    @Override
    public String provider() {
        return "deepseek";
    }

    @Override
    public boolean available() {
        // DeepSeek云端API必须携带密钥。
        return super.available() && !apiKey.isBlank();
    }

    @Override
    protected String displayName() {
        return "DeepSeek";
    }

    @Override
    protected Map<String, Object> thinkingPayload(boolean thinking) {
        return Map.of("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
    }
}

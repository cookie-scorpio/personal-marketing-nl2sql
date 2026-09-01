package com.boc.nl2sql.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Qwen3-32B的Chat Completions适配器，适用于OpenAI兼容端点：
 * vLLM/SGLang自建服务、阿里云DashScope兼容模式、Ollama等。
 * 规划、修复、复核的通用协议见OpenAiCompatibleModelAdapter。
 * Qwen3是混合思考模型，开关参数随部署方式不同，由thinking-param配置选择。
 */
@Component
public class QwenModelAdapter extends OpenAiCompatibleModelAdapter {
    private final String thinkingParam;

    public QwenModelAdapter(ObjectMapper objectMapper, Nl2SqlPrompts prompts,
                            @Value("${app.model.qwen.base-url:}") String baseUrl,
                            @Value("${app.model.qwen.api-key:}") String apiKey,
                            @Value("${app.model.qwen.model:Qwen3-32B}") String model,
                            @Value("${app.model.qwen.thinking-enabled:false}") boolean thinkingEnabled,
                            @Value("${app.model.qwen.thinking-param:chat_template_kwargs}") String thinkingParam,
                            @Value("${app.model.qwen.max-tokens:16384}") int maxTokens,
                            @Value("${app.model.qwen.retry-max-tokens:32768}") int retryMaxTokens,
                            @Value("${app.model.qwen.read-timeout-seconds:120}") int readTimeoutSeconds,
                            @Value("${app.model.qwen.tools-enabled:true}") boolean toolsEnabled,
                            @Value("${app.model.qwen.max-tool-rounds:3}") int maxToolRounds,
                            @Value("${app.model.qwen.result-review-enabled:true}") boolean resultReviewEnabled) {
        super(objectMapper, prompts, baseUrl, apiKey, model, thinkingEnabled, maxTokens, retryMaxTokens,
                readTimeoutSeconds, toolsEnabled, maxToolRounds, resultReviewEnabled);
        if (!thinkingParam.isBlank()
                && !List.of("chat_template_kwargs", "enable_thinking", "think", "none").contains(thinkingParam.strip())) {
            throw new IllegalArgumentException("Qwen思考参数模式只支持chat_template_kwargs、enable_thinking、think或none");
        }
        this.thinkingParam = thinkingParam == null || thinkingParam.isBlank() ? "chat_template_kwargs" : thinkingParam.strip();
    }

    @Override
    public String provider() {
        return "qwen";
    }

    @Override
    protected String displayName() {
        return "Qwen(" + model + ")";
    }

    /**
     * DashScope对Qwen3开源模型的限制：enable_thinking=true只支持流式调用。
     * 思考开启时改用流式请求，并把增量响应聚合回非流式结构，上层协议不变。
     * 带工具调用的轮次保持非流式并关闭思考：工具循环需要稳定的完整消息结构。
     */
    @Override
    protected Map<String, Object> postChat(Map<String, Object> request) {
        boolean thinking = Boolean.TRUE.equals(request.get("enable_thinking"));
        if (!thinking) return super.postChat(request);
        if (request.containsKey("tools")) {
            request.put("enable_thinking", false);
            return super.postChat(request);
        }
        var streamed = new java.util.LinkedHashMap<String, Object>(request);
        streamed.put("stream", true);
        streamed.put("stream_options", Map.of("include_usage", true));
        String raw = restClient.post().uri(chatEndpoint()).header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON).body(streamed).retrieve().body(String.class);
        return assembleStream(raw);
    }

    /** 把SSE增量块拼回一个与非流式响应等价的Map：content与reasoning_content各自累计。 */
    private Map<String, Object> assembleStream(String raw) {
        var content = new StringBuilder();
        var reasoning = new StringBuilder();
        String finishReason = "";
        Map<String, Object> usage = null;
        for (String line : raw.split("\n")) {
            String payload = line.strip();
            if (!payload.startsWith("data:")) continue;
            payload = payload.substring("data:".length()).strip();
            if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> chunk = objectMapper.readValue(payload, Map.class);
            if (chunk.get("usage") instanceof Map<?, ?> u) usage = (Map<String, Object>) u;
            if (!(chunk.get("choices") instanceof List<?> choices) || choices.isEmpty()
                    || !(choices.get(0) instanceof Map<?, ?> choice)) continue;
            if (choice.get("finish_reason") instanceof String value && !value.isEmpty()) finishReason = value;
            if (!(choice.get("delta") instanceof Map<?, ?> delta)) continue;
            if (delta.get("content") instanceof String value) content.append(value);
            if (delta.get("reasoning_content") instanceof String value) reasoning.append(value);
        }
        var message = new java.util.LinkedHashMap<String, Object>();
        message.put("role", "assistant");
        message.put("content", content.toString());
        if (!reasoning.isEmpty()) message.put("reasoning_content", reasoning.toString());
        var choice = new java.util.LinkedHashMap<String, Object>();
        choice.put("message", message);
        choice.put("finish_reason", finishReason);
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("choices", List.of(choice));
        if (usage != null) response.put("usage", usage);
        return response;
    }

    @Override
    protected Map<String, Object> thinkingPayload(boolean thinking) {
        // 自建vLLM/SGLang用chat_template_kwargs，DashScope兼容模式用顶层enable_thinking，Ollama用think。
        return switch (thinkingParam) {
            case "chat_template_kwargs" -> Map.of("chat_template_kwargs", Map.of("enable_thinking", thinking));
            case "enable_thinking" -> Map.of("enable_thinking", thinking);
            case "think" -> Map.of("think", thinking);
            default -> Map.of();
        };
    }
}

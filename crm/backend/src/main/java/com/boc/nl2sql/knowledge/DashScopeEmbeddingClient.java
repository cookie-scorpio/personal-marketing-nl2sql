package com.boc.nl2sql.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * DashScope兼容端点的Embedding客户端（text-embedding-v3/v4等OpenAI兼容 /embeddings 协议）。
 * base-url与api-key留空时回退app.model.qwen的同名配置，复用同一个DashScope密钥。
 * 自建vLLM等无Embedding服务时available()为false，检索增强整体降级，不影响查询主链路。
 */
@Component
public class DashScopeEmbeddingClient implements EmbeddingClient {
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dim;

    public DashScopeEmbeddingClient(ObjectMapper objectMapper,
                                    @Value("${app.rag.enabled:true}") boolean enabled,
                                    @Value("${app.rag.embedding-base-url:}") String embeddingBaseUrl,
                                    @Value("${app.rag.embedding-api-key:}") String embeddingApiKey,
                                    @Value("${app.model.qwen.base-url:}") String qwenBaseUrl,
                                    @Value("${app.model.qwen.api-key:}") String qwenApiKey,
                                    @Value("${app.rag.embedding-model:text-embedding-v4}") String model,
                                    @Value("${app.rag.embedding-dim:1024}") int dim) {
        if (dim <= 0) {
            throw new IllegalArgumentException("Embedding维度必须为正数");
        }
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        // 独立配置优先，留空回退qwen；qwen走DashScope兼容模式时其密钥对embeddings端点同样有效。
        this.baseUrl = firstNonBlank(embeddingBaseUrl, qwenBaseUrl);
        this.apiKey = firstNonBlank(embeddingApiKey, qwenApiKey);
        this.model = model == null ? "" : model.strip();
        this.dim = dim;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(15));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public boolean available() {
        return enabled && !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    @Override
    public float[] embed(String text) {
        if (!available()) {
            throw new IllegalStateException("Embedding服务未配置，无法向量化");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("待向量化文本不能为空");
        }
        Map<String, Object> request = Map.of(
                "model", model,
                "input", text,
                "dimensions", dim,
                "encoding_format", "float");
        String raw;
        try {
            raw = restClient.post().uri(embeddingsEndpoint())
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException | ResourceAccessException exception) {
            throw new IllegalStateException("Embedding服务调用失败：" + exception.getMessage(), exception);
        }
        float[] vector = parseEmbedding(objectMapper, raw);
        if (vector.length != dim) {
            throw new IllegalStateException("Embedding维度不匹配：期望" + dim + "，实际" + vector.length);
        }
        return vector;
    }

    private String embeddingsEndpoint() {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized.endsWith("/embeddings") ? normalized : normalized + "/embeddings";
    }

    /** 解析OpenAI兼容embeddings响应的data[0].embedding；抽成静态方法便于单测。 */
    static float[] parseEmbedding(ObjectMapper objectMapper, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Embedding服务返回空响应");
        }
        Map<String, Object> response = objectMapper.readValue(raw, Map.class);
        if (!(response.get("data") instanceof List<?> data) || data.isEmpty()
                || !(data.get(0) instanceof Map<?, ?> first)
                || !(first.get("embedding") instanceof List<?> values)) {
            throw new IllegalStateException("Embedding响应缺少data[0].embedding");
        }
        var vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            if (!(values.get(i) instanceof Number number)) {
                throw new IllegalStateException("Embedding向量包含非数值元素");
            }
            vector[i] = number.floatValue();
        }
        return vector;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) return primary.strip();
        return fallback == null ? "" : fallback.strip();
    }

    /** 当前嵌入模型名；不向外暴露密钥。 */
    @Override
    public String model() {
        return model;
    }

    @Override
    public int dim() {
        return dim;
    }
}

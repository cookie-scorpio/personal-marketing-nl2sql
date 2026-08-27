package com.boc.nl2sql.model;

import com.boc.nl2sql.authorization.domain.CurrentUser;
import com.boc.nl2sql.common.exception.BusinessException;
import com.boc.nl2sql.nl2sql.domain.ClarificationQuestion;
import com.boc.nl2sql.nl2sql.domain.IntentType;
import com.boc.nl2sql.nl2sql.domain.SemanticQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DeepSeek V4 Flash的Chat Completions适配器。
 * 模型输出只是候选查询计划；只读、数据范围、风险确认仍由后端统一控制。
 */
@Component
public class DeepSeekModelAdapter implements ModelAdapter {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekModelAdapter.class);
    private final ObjectMapper objectMapper;
    private final Nl2SqlPrompts prompts;
    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final boolean thinkingEnabled;
    private final int maxTokens;
    private final int retryMaxTokens;

    public DeepSeekModelAdapter(ObjectMapper objectMapper, Nl2SqlPrompts prompts,
                                @Value("${app.model.deepseek.base-url:}") String baseUrl,
                                @Value("${app.model.deepseek.api-key:}") String apiKey,
                                @Value("${app.model.deepseek.model:}") String model,
                                @Value("${app.model.deepseek.thinking-enabled:false}") boolean thinkingEnabled,
                                @Value("${app.model.deepseek.max-tokens:4096}") int maxTokens,
                                @Value("${app.model.deepseek.retry-max-tokens:8192}") int retryMaxTokens,
                                @Value("${app.model.deepseek.read-timeout-seconds:60}") int readTimeoutSeconds) {
        if (maxTokens <= 0 || retryMaxTokens < maxTokens || readTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("DeepSeek输出上限和超时必须为正数，重试上限不能小于首次上限");
        }
        this.objectMapper = objectMapper;
        this.prompts = prompts;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null ? "" : model.strip();
        this.thinkingEnabled = thinkingEnabled;
        this.maxTokens = maxTokens;
        this.retryMaxTokens = retryMaxTokens;
    }

    @Override
    public String provider() {
        return "deepseek";
    }

    @Override
    public boolean available() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    @Override
    public QueryInterpretation interpret(String queryText, CurrentUser user) {
        if (!available()) throw new BusinessException(503102, "DeepSeek尚未配置，请填写API地址、密钥和模型名");
        // 两次尝试使用同一份提示词与数据范围；只重试无最终内容或输出截断，不自动修复/执行SQL。
        var messages = List.of(Map.of("role", "system", "content", prompts.systemPrompt()),
                Map.of("role", "user", "content", prompts.userPrompt(queryText, user)));
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return requestPlan(messages, attempt);
            } catch (BusinessException exception) {
                if (attempt == 2 || !List.of(502101, 502104, 502105).contains(exception.code())) throw exception;
                log.warn("DeepSeek计划未完整返回，将进行唯一一次重试：code={}, nextMaxTokens={}",
                        exception.code(), retryMaxTokens);
            }
        }
        throw new IllegalStateException("模型重试状态异常");
    }

    private QueryInterpretation requestPlan(List<Map<String, String>> messages, int attempt) {
        int tokenBudget = attempt == 1 ? maxTokens : retryMaxTokens;
        Map<String, Object> request = Map.of(
                "model", model,
                "messages", messages,
                // V4默认开启思考；必须显式设置，否则思考内容可能耗尽额度而content仍为空。
                "thinking", Map.of("type", thinkingEnabled ? "enabled" : "disabled"),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.0,
                "max_tokens", tokenBudget,
                "stream", false);
        long started = System.nanoTime();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post().uri(chatEndpoint())
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Map.class);
            String content = finalContent(response, attempt, tokenBudget, started);
            DeepSeekPlan plan = objectMapper.readValue(stripMarkdownFence(content), DeepSeekPlan.class);
            return toInterpretation(plan);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            // 不记录API密钥、请求正文、外部错误正文，HTTP失败也不自动重复计费调用。
            throw new BusinessException(502102, "DeepSeek调用失败，HTTP状态：" + exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            throw new BusinessException(502107, "DeepSeek连接失败或响应超时，请检查网络后重试");
        } catch (Exception exception) {
            throw new BusinessException(502103, "DeepSeek查询计划不是有效的JSON格式，请调整问题后重试");
        }
    }

    private String finalContent(Map<String, Object> response, int attempt, int tokenBudget, long started) {
        if (response == null || !(response.get("choices") instanceof List<?> choices) || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)) {
            throw new BusinessException(502106, "DeepSeek响应结构不符合Chat Completions协议，未返回有效候选消息");
        }
        String content = message.get("content") instanceof String value ? value : "";
        String reasoning = message.get("reasoning_content") instanceof String value ? value : "";
        String finishReason = choice.get("finish_reason") instanceof String value ? value : "";
        // 仅记录诊断元数据；不把SQL、客户问题、模型思考正文或认证信息写入日志。
        String safeReason = List.of("stop", "length", "content_filter", "tool_calls").contains(finishReason)
                ? finishReason : "unknown";
        Object tokens = response.get("usage") instanceof Map<?, ?> usage ? usage.get("completion_tokens") : null;
        log.info("DeepSeek响应：attempt={}, finishReason={}, contentChars={}, reasoningChars={}, completionTokens={}, maxTokens={}, elapsedMs={}",
                attempt, safeReason, content.length(), reasoning.length(), tokens instanceof Number ? tokens : -1,
                tokenBudget, Duration.ofNanos(System.nanoTime() - started).toMillis());
        // 即使截断内容碰巧可解析为JSON，也不能把一个未完成的计划送去执行。
        if ("length".equals(finishReason)) {
            throw new BusinessException(502104, "DeepSeek输出达到长度上限，未获得完整查询计划；请缩小问题范围或调整输出上限");
        }
        if ("content_filter".equals(finishReason) || "tool_calls".equals(finishReason)
                || (message.get("refusal") instanceof String refusal && !refusal.isBlank())) {
            throw new BusinessException(502106, "DeepSeek未返回可用的最终查询计划，请调整业务问题后重试");
        }
        // 思考内容不是最终协议，即便看起来包含SQL或JSON也绝不能拿来兜底执行。
        if (content.isBlank() && !reasoning.isBlank()) {
            throw new BusinessException(502105, "DeepSeek仅返回思考内容，没有最终查询计划；请关闭思考模式或增加输出上限");
        }
        if (content.isBlank()) throw new BusinessException(502101, "DeepSeek连续返回空的查询计划，请稍后重试");
        if (!finishReason.isEmpty() && !"stop".equals(finishReason)) {
            throw new BusinessException(502106, "DeepSeek响应结束状态异常，未执行查询");
        }
        return content;
    }

    private QueryInterpretation toInterpretation(DeepSeekPlan plan) {
        if (plan == null) throw new BusinessException(502103, "DeepSeek未返回有效查询计划");
        List<String> conflicts = plan.conflicts() == null ? List.of() : List.copyOf(plan.conflicts());
        Map<String, String> slots = plan.recognizedSlots() == null ? Map.of() : Map.copyOf(plan.recognizedSlots());
        SemanticQuery semantic = new SemanticQuery(parseIntent(plan.intent()), null, null, null, null, null, null,
                null, null, false, false, conflicts, slots);
        double confidence = plan.confidence() == null ? 0.5 : Math.max(0, Math.min(1, plan.confidence()));
        boolean asking = Boolean.TRUE.equals(plan.needsClarification()) || confidence < 0.65;
        ClarificationQuestion question = asking
                ? new ClarificationQuestion(UUID.randomUUID().toString(), "MODEL_CLARIFICATION",
                nonBlank(plan.clarificationQuestion(), confidence < 0.65
                        ? "我对当前问题的理解置信度较低，请补充查询对象、指标或时间范围。"
                        : "请补充查询所需的业务条件。"),
                plan.clarificationOptions() == null ? List.of() : List.copyOf(plan.clarificationOptions()), slots)
                : null;
        String sql = asking ? null : normalizeSql(plan.sql());
        if (!asking && (sql == null || sql.isBlank())) {
            throw new BusinessException(422103, "模型未能生成可执行SQL，请换一种方式描述问题");
        }
        return new QueryInterpretation(semantic, "DEEPSEEK", confidence, sql,
                nonBlank(plan.title(), "自由数据分析"), nonBlank(plan.preferredDisplay(), "AUTO"), question);
    }

    private String chatEndpoint() {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized.endsWith("/chat/completions") ? normalized : normalized + "/chat/completions";
    }

    private IntentType parseIntent(String value) {
        if (value == null) return IntentType.GENERIC_ANALYSIS;
        try {
            IntentType parsed = IntentType.valueOf(value.strip().toUpperCase());
            return parsed == IntentType.UNKNOWN ? IntentType.GENERIC_ANALYSIS : parsed;
        } catch (IllegalArgumentException ignored) {
            return IntentType.GENERIC_ANALYSIS;
        }
    }

    private String normalizeSql(String value) {
        if (value == null) return null;
        String sql = stripMarkdownFence(value).strip();
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).strip();
        return sql;
    }

    private String stripMarkdownFence(String value) {
        String stripped = value.strip();
        if (stripped.startsWith("```")) {
            stripped = stripped.replaceFirst("^```(?:json|sql)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return stripped;
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** 与提示词JSON字段一一对应的内部传输对象。 */
    private record DeepSeekPlan(
            String intent, Double confidence, Boolean needsClarification,
            String clarificationQuestion, List<String> clarificationOptions,
            List<String> conflicts, Map<String, String> recognizedSlots,
            String sql, String title, String preferredDisplay
    ) {
    }
}

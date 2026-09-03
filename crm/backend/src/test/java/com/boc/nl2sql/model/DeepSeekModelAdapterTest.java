package com.boc.nl2sql.model;

import com.boc.nl2sql.domain.authorization.CurrentUser;
import com.boc.nl2sql.domain.authorization.RoleCode;
import com.boc.nl2sql.knowledge.BusinessTermCatalog;
import com.boc.nl2sql.common.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeepSeekModelAdapterTest {
    @Test void taskThinkingOverrideAppliesToPlanningAndRepairWithoutChangingGlobalDefault() throws Exception {
        try(var fixture=new Fixture(List.of(response("stop",PLAN,"推理正文不进入查询计划")))){
            var adapter=fixture.adapter();
            adapter.interpret("比较营销渠道",user(),()->true,true);
            adapter.repair("比较营销渠道",user(),"SELECT customer_id FROM dim_customer LIMIT 10","SQL表达错误",true);
            adapter.interpret("比较营销渠道",user(),()->true,false);
            assertThat(fixture.requests.get(0)).containsEntry("thinking",Map.of("type","enabled"));
            assertThat(fixture.requests.get(1)).containsEntry("thinking",Map.of("type","enabled"));
            assertThat(fixture.requests.get(2)).containsEntry("thinking",Map.of("type","disabled"));
        }
    }
    @Test
    void cancellationPreventsInitialResponseRetry() throws Exception {
        try (var fixture = new Fixture(List.of(response("stop", "", "")))) {
            var checks = new AtomicInteger();
            assertThatThrownBy(() -> fixture.adapter().interpret("统计客户", user(), () -> checks.getAndIncrement() == 0))
                    .isInstanceOf(com.boc.nl2sql.dao.execution.QueryTerminatedException.class);
            assertThat(fixture.requests).hasSize(1);
        }
    }
    @Test
    void repairMakesOnlyOneHttpCallEvenWhenResponseIsEmpty() throws Exception {
        try (var fixture = new Fixture(List.of(response("stop", "", "")))) {
            var user = new CurrentUser(1L, "manager01", "经理", RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.adapter().repair("统计客户", user, "SELECT bad FROM dim_customer LIMIT 100", "字段不存在"))
                    .isInstanceOf(BusinessException.class);
            assertThat(fixture.requests).hasSize(1);
        }
    }
    @Test
    void resultReviewUsesOnlyStructureSummaryAndReturnsMismatchReason() throws Exception {
        try(var fixture=new Fixture(List.of(response("stop","{\"aligned\":false,\"reason\":\"缺少用户要求的分组指标\"}","")))){
            var summary=Map.<String,Object>of("returned_row_count",1,"columns",List.of(Map.of("name","customer_id","type","TEXT")));
            var review=fixture.adapter().reviewResult("按年龄段统计客户数量",user(),
                    "SELECT c.customer_id FROM dim_customer c WHERE c.manager_id='M0001' LIMIT 100",summary,true);
            assertThat(review.aligned()).isFalse();assertThat(review.reason()).contains("缺少");
            assertThat(fixture.requests).hasSize(1);
            assertThat(fixture.requests.get(0).toString()).contains("returned_row_count","customer_id").doesNotContain("C00000001");
        }
    }
    private static final String PLAN = """
            {"intent":"MARKETING_ANALYSIS","confidence":0.96,"needs_clarification":false,
             "clarification_question":"","clarification_options":[],"conflicts":[],
             "recognized_slots":{"分析维度":"触达渠道"},
             "sql":"SELECT m.contact_channel_code, COUNT(*) AS contact_count FROM fct_customer_marketing m JOIN dim_customer c ON c.customer_id=m.customer_id WHERE c.manager_id = 'M0001' GROUP BY m.contact_channel_code LIMIT 100",
             "title":"渠道营销比较","preferred_display":"BAR"}
            """;

    @Test
    void explicitlyDisablesThinkingForStructuredPlanning() throws Exception {
        try (var fixture = new Fixture(List.of(response("stop", PLAN, "")))) {
            var plan = fixture.adapter().interpret("比较本季度不同渠道的营销转化率", user());
            assertThat(plan.source()).isEqualTo("DEEPSEEK");
            assertThat(fixture.requests).hasSize(1);
            assertThat(fixture.requests.get(0)).containsEntry("thinking", Map.of("type", "disabled"));
            assertThat(fixture.requests.get(0).get("max_tokens")).isEqualTo(4096);
        }
    }

    @Test
    void retriesTruncatedThinkingOnlyResponseOnceAndUsesFinalContent() throws Exception {
        try (var fixture = new Fixture(List.of(response("length", "", PLAN), response("stop", PLAN, "")))) {
            var plan = fixture.adapter().interpret("比较本季度不同渠道的营销转化率", user());
            assertThat(plan.hasGeneratedSql()).isTrue();
            assertThat(fixture.requests).hasSize(2);
            assertThat(fixture.requests.get(1).get("max_tokens")).isEqualTo(8192);
        }
    }

    @Test
    void neverExecutesJsonMarkedAsTruncatedEvenIfItLooksComplete() throws Exception {
        try (var fixture = new Fixture(List.of(response("length", PLAN, "")))) {
            assertThatThrownBy(() -> fixture.adapter().interpret("比较本季度不同渠道的营销转化率", user()))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.code()).isEqualTo(502104));
            assertThat(fixture.requests).hasSize(2);
        }
    }

    @Test
    void distinguishesReasoningOnlyFromEmptyResponseAndDoesNotParseReasoningAsSql() throws Exception {
        try (var fixture = new Fixture(List.of(response("stop", "", PLAN)))) {
            assertThatThrownBy(() -> fixture.adapter().interpret("比较本季度不同渠道的营销转化率", user()))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.code()).isEqualTo(502105));
            assertThat(fixture.requests).hasSize(2);
        }
    }

    @Test
    void retriesGenuineEmptyResponseAtMostOnce() throws Exception {
        try (var fixture = new Fixture(List.of(response("stop", "", "")))) {
            assertThatThrownBy(() -> fixture.adapter().interpret("比较本季度不同渠道的营销转化率", user()))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.code()).isEqualTo(502101));
            assertThat(fixture.requests).hasSize(2);
        }
    }

    @Test
    void doesNotRetryMalformedEnvelopeOrIncompleteJson() throws Exception {
        try (var fixture = new Fixture(List.of(Map.of("choices", List.of())))) {
            assertThatThrownBy(() -> fixture.adapter().interpret("比较本季度不同渠道的营销转化率", user()))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.code()).isEqualTo(502106));
            assertThat(fixture.requests).hasSize(1);
        }
        try (var fixture = new Fixture(List.of(response("stop", "{\"sql\":", "")))) {
            assertThatThrownBy(() -> fixture.adapter().interpret("比较本季度不同渠道的营销转化率", user()))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.code()).isEqualTo(502103));
            assertThat(fixture.requests).hasSize(1);
        }
    }

    private static CurrentUser user() {
        return new CurrentUser(1L, "manager01", "演示经理", RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");
    }

    @Test
    void keepsClarificationAsQuestionInsteadOfTreatingEmptySqlAsFailure() throws Exception {
        String asking = """
                {"intent":"GENERIC_ANALYSIS","confidence":0.8,"needs_clarification":true,
                 "clarification_question":"当前没有历史资产数据，是否改为近三个月资产变化？",
                 "clarification_options":[],"conflicts":[],"recognized_slots":{},"sql":""}
                """;
        try (var fixture = new Fixture(List.of(response("stop", asking, "")))) {
            var plan = fixture.adapter().interpret("找出资产同比下降的客户", user());
            assertThat(plan.clarification()).isNotNull();
            assertThat(plan.hasGeneratedSql()).isFalse();
            assertThat(fixture.requests).hasSize(1);
        }
    }

    @Test
    void doesNotRetryProviderRejection() throws Exception {
        try (var fixture = new Fixture(List.of(response("content_filter", "", "")))) {
            assertThatThrownBy(() -> fixture.adapter().interpret("查询", user()))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.code()).isEqualTo(502106));
            assertThat(fixture.requests).hasSize(1);
        }
    }

    private static Map<String, Object> response(String finishReason, String content, String reasoning) {
        return Map.of("choices", List.of(Map.of("finish_reason", finishReason,
                "message", Map.of("content", content, "reasoning_content", reasoning))),
                "usage", Map.of("completion_tokens", 1800));
    }

    /** 用真实HTTP序列重现空回复与截断，而不是只测试JSON解析辅助函数。 */
    private static class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final JsonMapper mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();

        Fixture(List<Map<String, Object>> responses) throws Exception {
            AtomicInteger attempt = new AtomicInteger();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> request = mapper.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
                requests.add(request);
                byte[] bytes = mapper.writeValueAsBytes(responses.get(Math.min(attempt.getAndIncrement(), responses.size() - 1)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        DeepSeekModelAdapter adapter() {
            var terms = mock(BusinessTermCatalog.class);
            when(terms.promptContext()).thenReturn("营销转化率：转化客户数/触达客户数");
            return new DeepSeekModelAdapter(mapper, new Nl2SqlPrompts(terms, null, null, 100),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "local-test-key", "deepseek-v4-flash",
                    false, 4096, 8192, 60, false, 3, true);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void parsesChatCompletionsJsonIntoGenericQueryPlan() throws Exception {
        var mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        var terms = mock(BusinessTermCatalog.class);
        when(terms.promptContext()).thenReturn("AUM：总资产");
        String plan = """
                {"intent":"GENERIC_ANALYSIS","confidence":0.96,"needs_clarification":false,
                 "clarification_question":"","clarification_options":[],"conflicts":[],
                 "recognized_slots":{"分析维度":"年龄段"},
                 "sql":"SELECT age_band_code,COUNT(*) AS customer_count FROM dim_customer c WHERE c.manager_id = 'M0001' GROUP BY age_band_code LIMIT 100",
                 "title":"客户年龄分布","preferred_display":"BAR"}
                """;
        String response = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", plan)))));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var adapter = new DeepSeekModelAdapter(mapper, new Nl2SqlPrompts(terms, null, null, 100),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "local-test-key", "deepseek-v4-flash",
                    false, 4096, 8192, 60, false, 3, true);
            var user = new CurrentUser(1L, "manager01", "演示经理", RoleCode.CUSTOMER_MANAGER, "EAST", "B001", "M0001");

            var result = adapter.interpret("分析各年龄段客户数量分布", user);

            assertThat(result.source()).isEqualTo("DEEPSEEK");
            assertThat(result.hasGeneratedSql()).isTrue();
            assertThat(result.preferredDisplay()).isEqualTo("BAR");
            assertThat(result.confidence()).isEqualTo(0.96);
            assertThat(result.clarification()).isNull();
        } finally {
            server.stop(0);
        }
    }
}

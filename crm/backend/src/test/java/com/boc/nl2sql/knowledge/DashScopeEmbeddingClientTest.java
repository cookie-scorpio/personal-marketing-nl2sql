package com.boc.nl2sql.knowledge;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashScopeEmbeddingClientTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void fallsBackToQwenConfigWhenDedicatedConfigBlank() {
        var client = new DashScopeEmbeddingClient(mapper, true, "", "",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-test", "text-embedding-v4", 1024);
        assertThat(client.available()).isTrue();
        assertThat(client.model()).isEqualTo("text-embedding-v4");
        assertThat(client.dim()).isEqualTo(1024);
    }

    @Test
    void unavailableWhenBothConfigsBlankOrDisabled() {
        var noKey = new DashScopeEmbeddingClient(mapper, true, "https://x", "", "", "", "m", 1024);
        assertThat(noKey.available()).isFalse();
        var disabled = new DashScopeEmbeddingClient(mapper, false, "https://x", "k", "", "", "m", 1024);
        assertThat(disabled.available()).isFalse();
    }

    @Test
    void rejectsNonPositiveDim() {
        assertThatThrownBy(() -> new DashScopeEmbeddingClient(mapper, true, "", "", "", "", "m", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postsToEmbeddingsEndpointAndParsesVector() throws Exception {
        var requests = new CopyOnWriteArrayList<Map<String, Object>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/embeddings", exchange -> {
            requests.add(mapper.readValue(exchange.getRequestBody().readAllBytes(), Map.class));
            byte[] bytes = mapper.writeValueAsBytes(Map.of(
                    "data", List.of(Map.of("index", 0, "embedding", List.of(0.1f, 0.2f, 0.3f))),
                    "model", "text-embedding-v4"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new DashScopeEmbeddingClient(mapper, true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1",
                    "local-key", "", "", "text-embedding-v4", 3);
            assertThat(client.available()).isTrue();

            float[] vector = client.embed("高净值客户有多少");

            assertThat(vector).containsExactly(0.1f, 0.2f, 0.3f);
            assertThat(requests).singleElement().satisfies(request -> {
                assertThat(request.get("model")).isEqualTo("text-embedding-v4");
                assertThat(request.get("input")).isEqualTo("高净值客户有多少");
                assertThat(request.get("dimensions")).isEqualTo(3);
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsWhenResponseDimMismatchesConfig() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            byte[] bytes = mapper.writeValueAsBytes(Map.of("data",
                    List.of(Map.of("embedding", List.of(0.1f, 0.2f, 0.3f)))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new DashScopeEmbeddingClient(mapper, true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "k", "", "", "m", 8);
            assertThatThrownBy(() -> client.embed("文本"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("维度不匹配");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parseEmbeddingRejectsMalformedPayload() {
        assertThatThrownBy(() -> DashScopeEmbeddingClient.parseEmbedding(mapper, ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DashScopeEmbeddingClient.parseEmbedding(mapper, "{\"data\":[]}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DashScopeEmbeddingClient.parseEmbedding(mapper, "{\"data\":[{\"embedding\":[\"a\"]}]}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parseEmbeddingReadsVectorFromOpenAiCompatiblePayload() {
        String json = mapper.writeValueAsString(Map.of("data",
                List.of(Map.of("embedding", List.of(0.5f, -0.25f)))));
        assertThat(DashScopeEmbeddingClient.parseEmbedding(mapper, json)).containsExactly(0.5f, -0.25f);
    }
}

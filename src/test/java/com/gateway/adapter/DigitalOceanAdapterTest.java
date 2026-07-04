package com.gateway.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.adapter.digitalocean.DigitalOceanAdapter;
import com.gateway.config.DigitalOceanProviderProperties;
import com.gateway.dto.ChatCompletionRequest;
import com.gateway.dto.Message;
import com.gateway.routing.RouteDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DigitalOceanAdapterTest {

    private DigitalOceanAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new DigitalOceanAdapter(
                new DigitalOceanProviderProperties("https://inference.do-ai.run/v1", "doo_v1_test"),
                objectMapper);
    }

    @Test
    void translateRequest_usesDoUpstreamModelId() throws Exception {
        ChatCompletionRequest request = new ChatCompletionRequest(
                "gpt-4o-mini",
                List.of(new Message("user", "Hello")),
                false,
                0.5f,
                256,
                null);
        RouteDefinition.ProviderTarget target = new RouteDefinition.ProviderTarget(
                "digitalocean", "openai-gpt-4o-mini", 0);

        String json = objectMapper.writeValueAsString(adapter.translateRequest(request, target));

        assertThat(json).contains("\"model\":\"openai-gpt-4o-mini\"");
        assertThat(json).contains("\"stream\":false");
        assertThat(json).contains("\"content\":\"Hello\"");
    }

    @Test
    void chatCompletionsPath_pointsAtDoInferenceEndpoint() {
        assertThat(adapter.chatCompletionsPath(false))
                .isEqualTo("https://inference.do-ai.run/v1/chat/completions");
    }

    @Test
    void buildHeaders_usesBearerAuth() {
        assertThat(adapter.buildHeaders().getFirst("Authorization"))
                .isEqualTo("Bearer doo_v1_test");
    }

    @Test
    void isRetryableError_503() {
        assertThat(adapter.isRetryableError(503, "")).isTrue();
        assertThat(adapter.isRetryableError(401, "")).isFalse();
    }

    @Test
    void isRetryableError_403_modelUnavailable() {
        assertThat(adapter.isRetryableError(403, "this model is not available for your subscription tier"))
                .isTrue();
    }

    @Test
    void translateStreamChunk_addsIdAndModel() {
        String line = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}";
        List<String> chunks = adapter.translateStreamChunk(line, "chatcmpl-do", "gpt-4o-mini").collectList().block();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("\"content\":\"Hi\"");
        assertThat(chunks.get(0)).contains("\"id\":\"chatcmpl-do\"");
        assertThat(chunks.get(0)).contains("\"model\":\"gpt-4o-mini\"");
    }

    @Test
    void translateResponse_parsesDoJson() {
        String body = """
                {"id":"do-cmpl","object":"chat.completion","created":123,"model":"openai-gpt-4o-mini","choices":[{"index":0,"message":{"role":"assistant","content":"Done"},"finish_reason":"stop"}]}
                """;
        var response = adapter.translateResponse(body, "gpt-4o-mini");
        assertThat(response.choices()).hasSize(1);
        assertThat(response.choices().get(0).message().content()).isEqualTo("Done");
    }
}

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
}

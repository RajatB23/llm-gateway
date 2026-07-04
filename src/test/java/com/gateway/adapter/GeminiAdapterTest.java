package com.gateway.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.adapter.gemini.GeminiAdapter;
import com.gateway.config.GeminiProviderProperties;
import com.gateway.dto.ChatCompletionRequest;
import com.gateway.dto.Message;
import com.gateway.routing.RouteDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiAdapterTest {

    private GeminiAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new GeminiAdapter(new GeminiProviderProperties("https://generativelanguage.googleapis.com", "test-key"), objectMapper);
    }

    @Test
    void translateRequest_mapsToContentsParts() throws Exception {
        ChatCompletionRequest request = new ChatCompletionRequest(
                "gemini-1.5-pro",
                List.of(new Message("user", "Hello")),
                false,
                0.5f,
                100,
                null);
        RouteDefinition.ProviderTarget target = new RouteDefinition.ProviderTarget("gemini", "gemini-1.5-pro", 0);

        String json = objectMapper.writeValueAsString(adapter.translateRequest(request, target));

        assertThat(json).contains("\"contents\"");
        assertThat(json).contains("\"parts\"");
        assertThat(json).contains("\"text\":\"Hello\"");
    }

    @Test
    void translateStreamChunk_candidateDelta() {
        String line = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}";
        List<String> chunks = adapter.translateStreamChunk(line, "chatcmpl-test", "gemini-1.5-pro").collectList().block();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("\"content\":\"Hi\"");
    }

    @Test
    void resolveUrl_includesModelAndKey() {
        RouteDefinition.ProviderTarget target = new RouteDefinition.ProviderTarget("gemini", "gemini-1.5-pro", 0);
        String url = adapter.resolveUrl(target, true);
        assertThat(url).contains("gemini-1.5-pro");
        assertThat(url).contains("streamGenerateContent");
        assertThat(url).contains("key=test-key");
    }
}

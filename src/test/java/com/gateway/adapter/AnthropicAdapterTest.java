package com.gateway.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.adapter.anthropic.AnthropicAdapter;
import com.gateway.config.AnthropicProviderProperties;
import com.gateway.dto.ChatCompletionRequest;
import com.gateway.dto.Message;
import com.gateway.routing.RouteDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicAdapterTest {

    private AnthropicAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new AnthropicAdapter(new AnthropicProviderProperties("https://api.anthropic.com", "test-key"), objectMapper);
    }

    @Test
    void translateRequest_extractsSystemMessage() throws Exception {
        ChatCompletionRequest request = new ChatCompletionRequest(
                "claude-3-5-sonnet",
                List.of(
                        new Message("system", "Be helpful"),
                        new Message("user", "Hi")),
                false,
                null,
                256,
                null);
        RouteDefinition.ProviderTarget target = new RouteDefinition.ProviderTarget("anthropic", "claude-3-5-sonnet-20241022", 0);

        String json = objectMapper.writeValueAsString(adapter.translateRequest(request, target));

        assertThat(json).contains("\"system\":\"Be helpful\"");
        assertThat(json).doesNotContain("\"role\":\"system\"");
        assertThat(json).contains("\"role\":\"user\"");
    }

    @Test
    void translateStreamChunk_contentBlockDelta() {
        String line = "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Hello\"}}";
        List<String> chunks = adapter.translateStreamChunk(line, "chatcmpl-test", "claude-3-5-sonnet").collectList().block();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("\"content\":\"Hello\"");
    }

    @Test
    void translateStreamChunk_messageStop() {
        String line = "data: {\"type\":\"message_stop\"}";
        List<String> chunks = adapter.translateStreamChunk(line, "chatcmpl-test", "claude-3-5-sonnet").collectList().block();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("\"finish_reason\":\"stop\"");
    }
}

package com.gateway.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.adapter.anthropic.AnthropicAdapter;
import com.gateway.adapter.gemini.GeminiAdapter;
import com.gateway.adapter.openai.OpenAiAdapter;
import com.gateway.config.AnthropicProviderProperties;
import com.gateway.config.GeminiProviderProperties;
import com.gateway.config.OpenAiProviderProperties;
import com.gateway.dto.ChatCompletionRequest;
import com.gateway.dto.Message;
import com.gateway.routing.RouteDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiAdapterTest {

    private OpenAiAdapter adapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new OpenAiAdapter(new OpenAiProviderProperties("https://api.openai.com", "test-key"), objectMapper);
    }

    @Test
    void translateRequest_passthroughFields() throws Exception {
        ChatCompletionRequest request = new ChatCompletionRequest(
                "gpt-4o",
                List.of(new Message("user", "Hello")),
                true,
                0.7f,
                512,
                null);
        RouteDefinition.ProviderTarget target = new RouteDefinition.ProviderTarget("openai", "gpt-4o", 0);

        String json = objectMapper.writeValueAsString(adapter.translateRequest(request, target));

        assertThat(json).contains("\"model\":\"gpt-4o\"");
        assertThat(json).contains("\"stream\":true");
        assertThat(json).contains("\"content\":\"Hello\"");
    }

    @Test
    void translateStreamChunk_parsesOpenAiChunk() {
        String line = "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}";
        List<String> chunks = adapter.translateStreamChunk(line, "chatcmpl-test", "gpt-4o").collectList().block();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("\"content\":\"Hi\"");
    }

    @Test
    void isRetryableError_429() {
        assertThat(adapter.isRetryableError(429, "")).isTrue();
        assertThat(adapter.isRetryableError(401, "")).isFalse();
    }
}

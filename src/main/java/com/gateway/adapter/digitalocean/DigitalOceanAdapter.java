package com.gateway.adapter.digitalocean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gateway.adapter.ProviderAdapter;
import com.gateway.config.DigitalOceanProviderProperties;
import com.gateway.dto.ChatCompletionRequest;
import com.gateway.dto.ChatCompletionResponse;
import com.gateway.routing.RouteDefinition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * DigitalOcean Gradient serverless inference uses an OpenAI-compatible Chat Completions API.
 */
@Component
public class DigitalOceanAdapter implements ProviderAdapter {

    private static final String PROVIDER_ID = "digitalocean";

    private final DigitalOceanProviderProperties properties;
    private final ObjectMapper objectMapper;

    public DigitalOceanAdapter(DigitalOceanProviderProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public Object translateRequest(ChatCompletionRequest unified, RouteDefinition.ProviderTarget target) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", target.upstreamModel());
        payload.put("stream", unified.isStreaming());
        if (unified.temperature() != null) {
            payload.put("temperature", unified.temperature());
        }
        if (unified.maxTokens() != null) {
            payload.put("max_tokens", unified.maxTokens());
        }
        if (unified.topP() != null) {
            payload.put("top_p", unified.topP());
        }
        var messages = payload.putArray("messages");
        unified.messages().forEach(m -> messages.addObject()
                .put("role", m.role())
                .put("content", m.content()));
        return payload;
    }

    @Override
    public String chatCompletionsPath(boolean stream) {
        return properties.baseUrl() + "/chat/completions";
    }

    @Override
    public HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            headers.setBearerAuth(properties.apiKey());
        }
        return headers;
    }

    @Override
    public Flux<String> translateStreamChunk(String vendorLine, String completionId, String modelAlias) {
        if (isStreamDoneLine(vendorLine)) {
            return Flux.empty();
        }
        String line = vendorLine.startsWith("data:") ? vendorLine.substring(5).trim() : vendorLine.trim();
        if (line.isEmpty() || "[DONE]".equals(line)) {
            return Flux.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(line);
            if (node.has("object") && !node.get("object").asText().equals("chat.completion.chunk")) {
                ((ObjectNode) node).put("object", "chat.completion.chunk");
            }
            if (!node.has("id")) {
                ((ObjectNode) node).put("id", completionId);
            }
            if (!node.has("model")) {
                ((ObjectNode) node).put("model", modelAlias);
            }
            return Flux.just(objectMapper.writeValueAsString(node));
        } catch (Exception e) {
            return Flux.empty();
        }
    }

    @Override
    public ChatCompletionResponse translateResponse(String vendorBody, String modelAlias) {
        try {
            JsonNode node = objectMapper.readTree(vendorBody);
            String id = node.path("id").asText("chatcmpl-" + UUID.randomUUID());
            long created = node.path("created").asLong(System.currentTimeMillis() / 1000);
            String model = node.has("model") ? node.get("model").asText() : modelAlias;

            List<ChatCompletionResponse.Choice> choices = new ArrayList<>();
            if (node.has("choices")) {
                for (JsonNode choice : node.get("choices")) {
                    JsonNode msg = choice.path("message");
                    choices.add(new ChatCompletionResponse.Choice(
                            choice.path("index").asInt(0),
                            new com.gateway.dto.Message(
                                    msg.path("role").asText("assistant"),
                                    msg.path("content").asText("")),
                            choice.path("finish_reason").isNull() ? null : choice.get("finish_reason").asText()
                    ));
                }
            }

            ChatCompletionResponse.Usage usage = null;
            if (node.has("usage")) {
                JsonNode u = node.get("usage");
                usage = new ChatCompletionResponse.Usage(
                        u.path("prompt_tokens").asInt(0),
                        u.path("completion_tokens").asInt(0),
                        u.path("total_tokens").asInt(0));
            }

            return new ChatCompletionResponse(id, "chat.completion", created, model, choices, usage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse DigitalOcean response", e);
        }
    }

    @Override
    public boolean isRetryableError(int status, String body) {
        return status == 408 || status == 429 || status == 500 || status == 502
                || status == 503 || status == 504;
    }
}

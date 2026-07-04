package com.gateway.adapter.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gateway.adapter.ProviderAdapter;
import com.gateway.config.AnthropicProviderProperties;
import com.gateway.dto.ChatCompletionRequest;
import com.gateway.dto.ChatCompletionResponse;
import com.gateway.dto.Message;
import com.gateway.routing.RouteDefinition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AnthropicAdapter implements ProviderAdapter {

    private static final String PROVIDER_ID = "anthropic";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final AnthropicProviderProperties properties;
    private final ObjectMapper objectMapper;

    public AnthropicAdapter(AnthropicProviderProperties properties, ObjectMapper objectMapper) {
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
        if (unified.maxTokens() != null) {
            payload.put("max_tokens", unified.maxTokens());
        } else {
            payload.put("max_tokens", 1024);
        }
        if (unified.temperature() != null) {
            payload.put("temperature", unified.temperature());
        }
        if (unified.topP() != null) {
            payload.put("top_p", unified.topP());
        }

        String systemContent = null;
        var messages = payload.putArray("messages");
        for (Message msg : unified.messages()) {
            if ("system".equals(msg.role())) {
                systemContent = systemContent == null ? msg.content()
                        : systemContent + "\n" + msg.content();
            } else {
                messages.addObject()
                        .put("role", "assistant".equals(msg.role()) ? "assistant" : "user")
                        .put("content", msg.content());
            }
        }
        if (systemContent != null) {
            payload.put("system", systemContent);
        }
        return payload;
    }

    @Override
    public String chatCompletionsPath(boolean stream) {
        return properties.baseUrl() + "/v1/messages";
    }

    @Override
    public HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", properties.apiKey() != null ? properties.apiKey() : "");
        headers.set("anthropic-version", ANTHROPIC_VERSION);
        return headers;
    }

    @Override
    public Flux<String> translateStreamChunk(String vendorLine, String completionId, String modelAlias) {
        if (vendorLine == null || vendorLine.isBlank()) {
            return Flux.empty();
        }
        String trimmed = vendorLine.trim();
        if (trimmed.startsWith("event:")) {
            return Flux.empty();
        }
        String json = trimmed.startsWith("data:") ? trimmed.substring(5).trim() : trimmed;
        if (json.isEmpty()) {
            return Flux.empty();
        }
        try {
            JsonNode event = objectMapper.readTree(json);
            String type = event.path("type").asText("");
            return switch (type) {
                case "content_block_delta" -> {
                    String text = event.path("delta").path("text").asText("");
                    if (text.isEmpty()) {
                        yield Flux.empty();
                    }
                    yield Flux.just(serializeChunk(completionId, modelAlias, text, null));
                }
                case "message_stop" -> Flux.just(serializeChunk(completionId, modelAlias, null, "stop"));
                case "message_delta" -> {
                    String stopReason = event.path("delta").path("stop_reason").asText(null);
                    if (stopReason != null) {
                        yield Flux.just(serializeChunk(completionId, modelAlias, null, mapStopReason(stopReason)));
                    }
                    yield Flux.empty();
                }
                default -> Flux.empty();
            };
        } catch (Exception e) {
            return Flux.empty();
        }
    }

    @Override
    public ChatCompletionResponse translateResponse(String vendorBody, String modelAlias) {
        try {
            JsonNode node = objectMapper.readTree(vendorBody);
            String id = "chatcmpl-" + UUID.randomUUID();
            String content = extractTextContent(node.path("content"));
            String finishReason = mapStopReason(node.path("stop_reason").asText("stop"));
            return new ChatCompletionResponse(
                    id,
                    "chat.completion",
                    System.currentTimeMillis() / 1000,
                    modelAlias,
                    List.of(new ChatCompletionResponse.Choice(
                            0,
                            new Message("assistant", content),
                            finishReason)),
                    null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Anthropic response", e);
        }
    }

    @Override
    public boolean isRetryableError(int status, String body) {
        return status == 408 || status == 429 || status == 500 || status == 502
                || status == 503 || status == 504;
    }

    private String extractTextContent(JsonNode contentArray) {
        if (!contentArray.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : contentArray) {
            if ("text".equals(block.path("type").asText())) {
                sb.append(block.path("text").asText());
            }
        }
        return sb.toString();
    }

    private String mapStopReason(String anthropicReason) {
        if (anthropicReason == null) {
            return null;
        }
        return switch (anthropicReason) {
            case "end_turn" -> "stop";
            case "max_tokens" -> "length";
            case "stop_sequence" -> "stop";
            default -> anthropicReason;
        };
    }

    private String serializeChunk(String id, String model, String content, String finishReason) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", id);
        root.put("object", "chat.completion.chunk");
        root.put("created", System.currentTimeMillis() / 1000);
        root.put("model", model);
        var choices = root.putArray("choices");
        var choice = choices.addObject();
        choice.put("index", 0);
        var delta = choice.putObject("delta");
        if (content != null) {
            delta.put("content", content);
        }
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        } else {
            choice.putNull("finish_reason");
        }
        return objectMapper.writeValueAsString(root);
    }
}

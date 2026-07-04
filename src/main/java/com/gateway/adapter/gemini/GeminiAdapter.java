package com.gateway.adapter.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gateway.adapter.ProviderAdapter;
import com.gateway.config.GeminiProviderProperties;
import com.gateway.dto.ChatCompletionRequest;
import com.gateway.dto.ChatCompletionResponse;
import com.gateway.dto.Message;
import com.gateway.routing.RouteDefinition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

@Component
public class GeminiAdapter implements ProviderAdapter {

    private static final String PROVIDER_ID = "gemini";

    private final GeminiProviderProperties properties;
    private final ObjectMapper objectMapper;

    public GeminiAdapter(GeminiProviderProperties properties, ObjectMapper objectMapper) {
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
        var contents = payload.putArray("contents");
        for (Message msg : unified.messages()) {
            if ("system".equals(msg.role())) {
                var content = contents.addObject();
                content.put("role", "user");
                var parts = content.putArray("parts");
                parts.addObject().put("text", "[System] " + msg.content());
            } else {
                var content = contents.addObject();
                content.put("role", mapRole(msg.role()));
                var parts = content.putArray("parts");
                parts.addObject().put("text", msg.content());
            }
        }

        ObjectNode generationConfig = objectMapper.createObjectNode();
        if (unified.temperature() != null) {
            generationConfig.put("temperature", unified.temperature());
        }
        if (unified.maxTokens() != null) {
            generationConfig.put("maxOutputTokens", unified.maxTokens());
        }
        if (unified.topP() != null) {
            generationConfig.put("topP", unified.topP());
        }
        if (!generationConfig.isEmpty()) {
            payload.set("generationConfig", generationConfig);
        }
        return payload;
    }

    @Override
    public String chatCompletionsPath(boolean stream) {
        return null; // resolved per-request via resolveUrl
    }

    public String resolveUrl(RouteDefinition.ProviderTarget target, boolean stream) {
        String action = stream ? "streamGenerateContent" : "generateContent";
        return UriComponentsBuilder.fromHttpUrl(properties.baseUrl())
                .path("/v1beta/models/{model}:" + action)
                .queryParam("key", properties.apiKey())
                .queryParam("alt", stream ? "sse" : null)
                .buildAndExpand(target.upstreamModel())
                .toUriString()
                .replace("alt=null&", "")
                .replace("?alt=null", "");
    }

    @Override
    public HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Override
    public Flux<String> translateStreamChunk(String vendorLine, String completionId, String modelAlias) {
        if (vendorLine == null || vendorLine.isBlank()) {
            return Flux.empty();
        }
        String trimmed = vendorLine.trim();
        String json = trimmed.startsWith("data:") ? trimmed.substring(5).trim() : trimmed;
        if (json.isEmpty() || json.startsWith("[")) {
            // Gemini may send array wrapper on first chunk
            try {
                JsonNode node = objectMapper.readTree(json);
                if (node.isArray() && !node.isEmpty()) {
                    return translateGeminiResponse(node.get(0), completionId, modelAlias);
                }
                return translateGeminiResponse(node, completionId, modelAlias);
            } catch (Exception e) {
                return Flux.empty();
            }
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return translateGeminiResponse(node, completionId, modelAlias);
        } catch (Exception e) {
            return Flux.empty();
        }
    }

    private Flux<String> translateGeminiResponse(JsonNode node, String completionId, String modelAlias) {
        try {
            JsonNode candidates = node.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                return Flux.empty();
            }
            JsonNode candidate = candidates.get(0);
            String text = candidate.path("content").path("parts").path(0).path("text").asText("");
            String finishReason = candidate.path("finishReason").asText(null);
            if (text.isEmpty() && finishReason == null) {
                return Flux.empty();
            }
            if (!text.isEmpty()) {
                return Flux.just(serializeChunk(completionId, modelAlias, text, null));
            }
            return Flux.just(serializeChunk(completionId, modelAlias, null, mapFinishReason(finishReason)));
        } catch (Exception e) {
            return Flux.empty();
        }
    }

    @Override
    public ChatCompletionResponse translateResponse(String vendorBody, String modelAlias) {
        try {
            JsonNode node = objectMapper.readTree(vendorBody);
            JsonNode candidate = node.path("candidates").path(0);
            String content = candidate.path("content").path("parts").path(0).path("text").asText("");
            String finishReason = mapFinishReason(candidate.path("finishReason").asText("STOP"));
            return new ChatCompletionResponse(
                    "chatcmpl-" + UUID.randomUUID(),
                    "chat.completion",
                    System.currentTimeMillis() / 1000,
                    modelAlias,
                    List.of(new ChatCompletionResponse.Choice(
                            0,
                            new Message("assistant", content),
                            finishReason)),
                    null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Gemini response", e);
        }
    }

    @Override
    public boolean isRetryableError(int status, String body) {
        return status == 408 || status == 429 || status == 500 || status == 502
                || status == 503 || status == 504;
    }

    private String mapRole(String role) {
        return "assistant".equals(role) ? "model" : "user";
    }

    private String mapFinishReason(String geminiReason) {
        if (geminiReason == null) {
            return null;
        }
        return switch (geminiReason.toUpperCase()) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            default -> geminiReason.toLowerCase();
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

package com.gateway.adapter;

import com.gateway.dto.ChatCompletionRequest;
import com.gateway.dto.ChatCompletionResponse;
import com.gateway.routing.RouteDefinition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Flux;

public interface ProviderAdapter {

    String getProviderId();

    Object translateRequest(ChatCompletionRequest unified, RouteDefinition.ProviderTarget target);

    String chatCompletionsPath(boolean stream);

    HttpHeaders buildHeaders();

    Flux<String> translateStreamChunk(String vendorLine, String completionId, String modelAlias);

    ChatCompletionResponse translateResponse(String vendorBody, String modelAlias);

    boolean isRetryableError(int status, String body);

    default boolean isStreamDoneLine(String line) {
        return line == null || line.isBlank() || "[DONE]".equals(line.trim());
    }
}

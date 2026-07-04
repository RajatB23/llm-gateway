package com.gateway.fallback;

import com.gateway.adapter.ProviderAdapter;
import com.gateway.dto.ChatCompletionRequest;
import com.gateway.dto.ChatCompletionResponse;
import com.gateway.exception.GatewayException;
import com.gateway.routing.ProviderRegistry;
import com.gateway.routing.RouteDefinition;
import com.gateway.streaming.StreamContext;
import com.gateway.streaming.StreamPipeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Service
public class FallbackOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FallbackOrchestrator.class);

    private final StreamPipeService streamPipeService;
    private final ProviderRegistry providerRegistry;

    public FallbackOrchestrator(StreamPipeService streamPipeService, ProviderRegistry providerRegistry) {
        this.streamPipeService = streamPipeService;
        this.providerRegistry = providerRegistry;
    }

    public Flux<ServerSentEvent<String>> executeStreamWithFallback(
            ChatCompletionRequest request,
            List<RouteDefinition.ProviderTarget> chain,
            String requestId) {

        StreamContext context = new StreamContext(requestId);
        String completionId = "chatcmpl-" + UUID.randomUUID();

        return attemptStream(chain, 0, request, completionId, context)
                .concatWith(Mono.just(ServerSentEvent.<String>builder().data("[DONE]").build()))
                .onErrorResume(GatewayException.class, Flux::error);
    }

    public Mono<ChatCompletionResponse> executeNonStreamWithFallback(
            ChatCompletionRequest request,
            List<RouteDefinition.ProviderTarget> chain,
            String requestId) {

        StreamContext context = new StreamContext(requestId);
        return attemptNonStream(chain, 0, request, context);
    }

    private Flux<ServerSentEvent<String>> attemptStream(
            List<RouteDefinition.ProviderTarget> chain,
            int index,
            ChatCompletionRequest request,
            String completionId,
            StreamContext context) {

        if (index >= chain.size()) {
            return Flux.error(GatewayException.allProvidersFailed(request.model()));
        }

        RouteDefinition.ProviderTarget target = chain.get(index);
        ProviderAdapter adapter = providerRegistry.get(target.providerId());

        return streamPipeService.pipeStream(adapter, target, request, completionId, request.model(), context)
                .onErrorResume(error -> handleStreamError(error, chain, index, request, completionId, context))
                .switchIfEmpty(Flux.defer(() -> {
                    if (!context.hasContentSent() && index + 1 < chain.size()) {
                        log.warn("Empty stream from provider={}, falling back requestId={}",
                                target.providerId(), context.getRequestId());
                        return attemptStream(chain, index + 1, request, completionId, context);
                    }
                    return Flux.empty();
                }));
    }

    private Flux<ServerSentEvent<String>> handleStreamError(
            Throwable error,
            List<RouteDefinition.ProviderTarget> chain,
            int index,
            ChatCompletionRequest request,
            String completionId,
            StreamContext context) {

        if (!context.isClientConnected()) {
            log.info("Client disconnected, skipping fallback requestId={}", context.getRequestId());
            return Flux.empty();
        }

        RouteDefinition.ProviderTarget current = chain.get(index);
        boolean shouldFallback = shouldFallback(error, context);

        if (shouldFallback && index + 1 < chain.size()) {
            RouteDefinition.ProviderTarget next = chain.get(index + 1);
            log.warn("Fallback triggered from={} to={} reason={} requestId={}",
                    current.providerId(), next.providerId(), error.getMessage(), context.getRequestId());
            return attemptStream(chain, index + 1, request, completionId, context);
        }

        if (context.hasContentSent()) {
            log.warn("Stream broke mid-flight after content sent, closing gracefully requestId={}",
                    context.getRequestId());
            return Flux.empty();
        }

        if (error instanceof GatewayException ge) {
            return Flux.error(ge);
        }
        return Flux.error(GatewayException.allProvidersFailed(request.model()));
    }

    private Mono<ChatCompletionResponse> attemptNonStream(
            List<RouteDefinition.ProviderTarget> chain,
            int index,
            ChatCompletionRequest request,
            StreamContext context) {

        if (index >= chain.size()) {
            return Mono.error(GatewayException.allProvidersFailed(request.model()));
        }

        RouteDefinition.ProviderTarget target = chain.get(index);
        ProviderAdapter adapter = providerRegistry.get(target.providerId());

        return streamPipeService.callNonStream(adapter, target, request, context)
                .map(StreamPipeService.UpstreamResult::response)
                .onErrorResume(error -> {
                    if (!context.isClientConnected()) {
                        return Mono.empty();
                    }
                    if (shouldFallback(error, context) && index + 1 < chain.size()) {
                        RouteDefinition.ProviderTarget next = chain.get(index + 1);
                        log.warn("Non-stream fallback from={} to={} requestId={}",
                                target.providerId(), next.providerId(), context.getRequestId());
                        return attemptNonStream(chain, index + 1, request, context);
                    }
                    if (error instanceof GatewayException ge) {
                        return Mono.error(ge);
                    }
                    return Mono.error(GatewayException.allProvidersFailed(request.model()));
                });
    }

    boolean shouldFallback(Throwable error, StreamContext context) {
        if (context.hasContentSent()) {
            return false;
        }
        if (error instanceof StreamPipeService.UpstreamException ue) {
            return ue.isRetryable();
        }
        if (error instanceof java.io.IOException
                || error instanceof java.util.concurrent.TimeoutException
                || error.getCause() instanceof java.io.IOException) {
            return true;
        }
        return false;
    }
}

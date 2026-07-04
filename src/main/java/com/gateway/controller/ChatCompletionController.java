package com.gateway.controller;

import com.gateway.dto.ChatCompletionRequest;
import com.gateway.dto.ChatCompletionResponse;
import com.gateway.fallback.FallbackOrchestrator;
import com.gateway.routing.ModelRouter;
import com.gateway.routing.RouteDefinition;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionController.class);

    private final ModelRouter modelRouter;
    private final FallbackOrchestrator fallbackOrchestrator;

    public ChatCompletionController(ModelRouter modelRouter, FallbackOrchestrator fallbackOrchestrator) {
        this.modelRouter = modelRouter;
        this.fallbackOrchestrator = fallbackOrchestrator;
    }

    @PostMapping("/chat/completions")
    public Mono<ResponseEntity<?>> chatCompletions(@Valid @RequestBody ChatCompletionRequest request) {
        List<RouteDefinition.ProviderTarget> chain = modelRouter.resolveChain(request.model());
        String requestId = org.slf4j.MDC.get("requestId");

        log.info("Chat completion model={} stream={} requestId={}", request.model(), request.stream(), requestId);

        if (request.isStreaming()) {
            Flux<ServerSentEvent<String>> stream = fallbackOrchestrator.executeStreamWithFallback(
                    request, chain, requestId);
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(stream));
        }

        Mono<ChatCompletionResponse> response = fallbackOrchestrator.executeNonStreamWithFallback(
                request, chain, requestId);
        return response.map(body -> ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
    }
}

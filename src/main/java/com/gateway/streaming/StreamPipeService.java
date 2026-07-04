package com.gateway.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.adapter.ProviderAdapter;
import com.gateway.adapter.gemini.GeminiAdapter;
import com.gateway.dto.ChatCompletionRequest;
import com.gateway.dto.ChatCompletionResponse;
import com.gateway.routing.RouteDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class StreamPipeService {

    private static final Logger log = LoggerFactory.getLogger(StreamPipeService.class);
    private static final int BUFFER_SIZE = 8192;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public StreamPipeService(WebClient upstreamWebClient, ObjectMapper objectMapper) {
        this.webClient = upstreamWebClient;
        this.objectMapper = objectMapper;
    }

    public Flux<ServerSentEvent<String>> pipeStream(
            ProviderAdapter adapter,
            RouteDefinition.ProviderTarget target,
            ChatCompletionRequest request,
            String completionId,
            String modelAlias,
            StreamContext context) {

        Object payload = adapter.translateRequest(request, target);
        String url = resolveUrl(adapter, target, true);

        log.info("Opening upstream stream provider={} model={} requestId={}",
                adapter.getProviderId(), target.upstreamModel(), context.getRequestId());

        return webClient.post()
                .uri(url)
                .headers(h -> h.addAll(adapter.buildHeaders()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_NDJSON, MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(payload))
                .exchangeToFlux(response -> handleStreamResponse(
                        response, adapter, completionId, modelAlias, context))
                .doOnCancel(() -> {
                    context.markClientDisconnected();
                    log.info("Client disconnected, cancelling upstream requestId={}", context.getRequestId());
                });
    }

    public Mono<UpstreamResult> callNonStream(
            ProviderAdapter adapter,
            RouteDefinition.ProviderTarget target,
            ChatCompletionRequest request,
            StreamContext context) {

        Object payload = adapter.translateRequest(request, target);
        String url = resolveUrl(adapter, target, false);

        log.info("Opening upstream non-stream provider={} model={} requestId={}",
                adapter.getProviderId(), target.upstreamModel(), context.getRequestId());

        return webClient.post()
                .uri(url)
                .headers(h -> h.addAll(adapter.buildHeaders()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(payload))
                .exchangeToMono(response -> handleNonStreamResponse(response, adapter, request.model(), context))
                .doOnCancel(() -> {
                    context.markClientDisconnected();
                    log.info("Client disconnected, cancelling upstream requestId={}", context.getRequestId());
                });
    }

    private Flux<ServerSentEvent<String>> handleStreamResponse(
            ClientResponse response,
            ProviderAdapter adapter,
            String completionId,
            String modelAlias,
            StreamContext context) {

        int status = response.statusCode().value();
        if (status >= 400) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMapMany(body -> Flux.error(new UpstreamException(status, body, adapter.isRetryableError(status, body))));
        }

        context.markUpstreamOpen();
        AtomicReference<StringBuilder> lineBuffer = new AtomicReference<>(new StringBuilder());

        return response.bodyToFlux(DataBuffer.class)
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return new String(bytes, StandardCharsets.UTF_8);
                })
                .concatMap(chunk -> parseLines(chunk, lineBuffer))
                .concatMap(line -> adapter.translateStreamChunk(line, completionId, modelAlias))
                .filter(json -> json != null && !json.isBlank())
                .map(json -> {
                    context.markContentSent();
                    return ServerSentEvent.<String>builder().data(json).build();
                })
                .doOnComplete(context::markUpstreamClosed)
                .doOnError(e -> context.markUpstreamClosed());
    }

    private Mono<UpstreamResult> handleNonStreamResponse(
            ClientResponse response,
            ProviderAdapter adapter,
            String modelAlias,
            StreamContext context) {

        int status = response.statusCode().value();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> {
                    if (status >= 400) {
                        return Mono.error(new UpstreamException(status, body, adapter.isRetryableError(status, body)));
                    }
                    context.markContentSent();
                    ChatCompletionResponse translated = adapter.translateResponse(body, modelAlias);
                    return Mono.just(new UpstreamResult(translated, false));
                });
    }

    private Flux<String> parseLines(String chunk, AtomicReference<StringBuilder> bufferRef) {
        StringBuilder buffer = bufferRef.get();
        buffer.append(chunk);
        Flux<String> lines = Flux.empty();
        int newlineIndex;
        while ((newlineIndex = indexOfNewline(buffer)) >= 0) {
            String line = buffer.substring(0, newlineIndex).trim();
            buffer.delete(0, newlineIndex + 1);
            if (!line.isEmpty()) {
                lines = lines.concatWith(Flux.just(line));
            }
        }
        return lines;
    }

    private int indexOfNewline(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') {
                return i;
            }
        }
        return -1;
    }

    private String resolveUrl(ProviderAdapter adapter, RouteDefinition.ProviderTarget target, boolean stream) {
        if (adapter instanceof GeminiAdapter geminiAdapter) {
            return geminiAdapter.resolveUrl(target, stream);
        }
        return adapter.chatCompletionsPath(stream);
    }

    public record UpstreamResult(ChatCompletionResponse response, boolean retryable) {
    }

    public static class UpstreamException extends RuntimeException {
        private final int status;
        private final String body;
        private final boolean retryable;

        public UpstreamException(int status, String body, boolean retryable) {
            super("Upstream error " + status + ": " + body);
            this.status = status;
            this.body = body;
            this.retryable = retryable;
        }

        public int getStatus() {
            return status;
        }

        public String getBody() {
            return body;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }
}

package com.gateway.fallback;

import com.gateway.streaming.StreamContext;
import com.gateway.streaming.StreamPipeService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackOrchestratorTest {

    private final FallbackOrchestrator orchestrator = new FallbackOrchestrator(null, null);

    @Test
    void shouldFallback_retryableErrorBeforeContent() {
        StreamContext context = new StreamContext("req-1");
        StreamPipeService.UpstreamException error = new StreamPipeService.UpstreamException(503, "unavailable", true);

        assertThat(orchestrator.shouldFallback(error, context)).isTrue();
    }

    @Test
    void shouldFallback_nonRetryable401() {
        StreamContext context = new StreamContext("req-2");
        StreamPipeService.UpstreamException error = new StreamPipeService.UpstreamException(401, "unauthorized", false);

        assertThat(orchestrator.shouldFallback(error, context)).isFalse();
    }

    @Test
    void shouldFallback_noRetryAfterContentSent() {
        StreamContext context = new StreamContext("req-3");
        context.markContentSent();
        StreamPipeService.UpstreamException error = new StreamPipeService.UpstreamException(503, "unavailable", true);

        assertThat(orchestrator.shouldFallback(error, context)).isFalse();
    }

    @Test
    void shouldFallback_ioException() {
        StreamContext context = new StreamContext("req-4");
        assertThat(orchestrator.shouldFallback(new java.io.IOException("timeout"), context)).isTrue();
    }
}

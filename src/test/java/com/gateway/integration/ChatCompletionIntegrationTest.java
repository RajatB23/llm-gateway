package com.gateway.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class ChatCompletionIntegrationTest {

    static WireMockServer wireMock;

    @Autowired
    WebTestClient webTestClient;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("wiremock.port", () -> wireMock.port());
        registry.add("gateway.providers.openai.base-url", () -> "http://localhost:" + wireMock.port());
        registry.add("gateway.providers.anthropic.base-url", () -> "http://localhost:" + wireMock.port());
        registry.add("gateway.providers.gemini.base-url", () -> "http://localhost:" + wireMock.port());
        registry.add("gateway.providers.digitalocean.base-url", () -> "http://localhost:" + wireMock.port());
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(30)).build();
    }

    @Test
    void happyPathStreaming_returnsUnifiedSseChunksAndDone() {
        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, "text/event-stream")
                        .withBody("""
                                data: {"id":"cmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

                                data: {"id":"cmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                                data: [DONE]

                                """)));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"gpt-4o","messages":[{"role":"user","content":"Hi"}],"stream":true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> {
                    org.assertj.core.api.Assertions.assertThat(body).contains("Hello");
                    org.assertj.core.api.Assertions.assertThat(body).contains("[DONE]");
                });
    }

    @Test
    void primary503_backup200_seamlessStream() {
        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .inScenario("fallback")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"unavailable\"}"))
                .willSetStateTo("Failed"));

        stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, "text/event-stream")
                        .withBody("""
                                data: {"type":"content_block_delta","delta":{"text":"Backup"}}

                                data: {"type":"message_stop"}

                                """)));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"gpt-4o","messages":[{"role":"user","content":"Hi"}],"stream":true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body).contains("Backup"));
    }

    @Test
    void allProvidersFail_returns503() {
        stubFor(post(urlMatching("/v1/chat/completions|/v1/messages"))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"down\"}")));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"all-fail-model","messages":[{"role":"user","content":"Hi"}],"stream":true}
                        """)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("service_unavailable");
    }

    @Test
    void nonStreamMode_returnsJsonResponse() {
        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""
                                {"id":"cmpl-1","object":"chat.completion","created":123,"model":"gpt-4o","choices":[{"index":0,"message":{"role":"assistant","content":"Full response"},"finish_reason":"stop"}]}
                                """)));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"gpt-4o","messages":[{"role":"user","content":"Hi"}],"stream":false}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("Full response");
    }

    @Test
    void invalidModel_returns404() {
        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"does-not-exist","messages":[{"role":"user","content":"Hi"}],"stream":false}
                        """)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("not_found");
    }

    @Test
    void digitalOceanRoute_streamingViaDoAdapter() {
        stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, "text/event-stream")
                        .withBody("""
                                data: {"id":"do-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"DO reply"},"finish_reason":null}]}

                                data: {"id":"do-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                                data: [DONE]

                                """)));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hi"}],"stream":true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body).contains("DO reply"));
    }

    @Test
    void digitalOceanPrimary503_backup200_silentFallback() {
        stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("do-fallback")
                .whenScenarioStateIs("Started")
                .withRequestBody(containing("openai-gpt-4o-mini"))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"unavailable\"}"))
                .willSetStateTo("Failed"));

        stubFor(post(urlEqualTo("/chat/completions"))
                .inScenario("do-fallback")
                .whenScenarioStateIs("Failed")
                .withRequestBody(containing("openai-gpt-4o"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, "text/event-stream")
                        .withBody("""
                                data: {"id":"do-2","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"DO backup"},"finish_reason":null}]}

                                data: [DONE]

                                """)));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hi"}],"stream":true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body).contains("DO backup"));
    }

    @Test
    void invalidRequest_emptyMessages_returns400() {
        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"gpt-4o","messages":[],"stream":false}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request");
    }

    @Test
    void invalidRequest_blankModel_returns400() {
        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"","messages":[{"role":"user","content":"Hi"}],"stream":false}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error.type").isEqualTo("invalid_request");
    }

    @Test
    void clientDisconnect_cancelsUpstream() throws InterruptedException {
        stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, "text/event-stream")
                        .withFixedDelay(500)
                        .withBody("""
                                data: {"id":"cmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"Slow"},"finish_reason":null}]}

                                """)));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"gpt-4o","messages":[{"role":"user","content":"Hi"}],"stream":true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .take(1)
                .blockFirst(Duration.ofSeconds(5));

        Thread.sleep(300);
        verify(postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }
}

# LLM API Gateway — Architecture

This document describes the architecture of the LLM API Gateway, including component interactions, streaming design, and fallback logic.

## Layered Architecture

```mermaid
flowchart TB
    subgraph clientLayer [Client Layer]
        Client[HTTP Client]
    end
    subgraph gatewayLayer [Gateway - Spring Boot WebFlux]
        Controller[ChatCompletionController]
        AuthFilter["AuthFilter (Phase 2)"]
        Router[ModelRouter]
        Fallback[FallbackOrchestrator]
        StreamPipe[StreamPipeService]
        subgraph adapters [Adapter Layer]
            OpenAI[OpenAiAdapter]
            Anthropic[AnthropicAdapter]
            Gemini[GeminiAdapter]
        end
    end
    subgraph upstream [Upstream Providers]
        OAI[OpenAI API]
        ANT[Anthropic API]
        GEM[Google Gemini API]
    end
    Client -->|POST /v1/chat/completions| AuthFilter
    AuthFilter --> Controller
    Controller --> Router
    Router --> Fallback
    Fallback --> StreamPipe
    StreamPipe --> OpenAI
    StreamPipe --> Anthropic
    StreamPipe --> Gemini
    OpenAI --> OAI
    Anthropic --> ANT
    Gemini --> GEM
    StreamPipe -->|SSE chunks| Client
```

## Request Sequence

```mermaid
sequenceDiagram
    participant C as Client
    participant GW as Gateway
    participant R as ModelRouter
    participant F as FallbackOrchestrator
    participant A as ProviderAdapter
    participant U as Upstream

    C->>GW: POST /v1/chat/completions stream=true
    GW->>GW: Validate + assign requestId
    GW->>R: resolveModel(model)
    R-->>GW: RouteChain primary,backup1,backup2
    GW->>F: executeWithFallback(chain)
    F->>A: translateRequest(unified)
    A-->>F: vendorPayload
    F->>U: HTTP POST stream
    alt Transient error before stream
        U-->>F: 429/502/503
        F->>F: log + switch to backup
        F->>A: next adapter
        F->>U: retry
    else Success
        U-->>F: SSE/chunked stream
        loop Each chunk
            F->>A: translateChunk(vendorChunk)
            A-->>F: unifiedChunk
            F->>C: write SSE data line
        end
    end
    F->>C: data DONE
```

## Adapter Pattern

```mermaid
classDiagram
    class ProviderAdapter {
        <<interface>>
        +getProviderId() String
        +translateRequest(unified) Object
        +chatCompletionsPath(stream) String
        +buildHeaders() HttpHeaders
        +translateStreamChunk(vendorLine) String
        +translateResponse(vendorBody) ChatCompletionResponse
        +isRetryableError(status, body) boolean
    }
    class OpenAiAdapter {
        +translateRequest passthrough
    }
    class AnthropicAdapter {
        +translateRequest messages to system+messages
        +translateStreamChunk event types
    }
    class GeminiAdapter {
        +translateRequest contents/parts format
        +translateStreamChunk candidates
    }
    ProviderAdapter <|.. OpenAiAdapter
    ProviderAdapter <|.. AnthropicAdapter
    ProviderAdapter <|.. GeminiAdapter
```

Routing logic never branches on vendor JSON shape. `ModelRouter` and `FallbackOrchestrator` depend only on the `ProviderAdapter` interface.

### Translation Differences

| Field | OpenAI | Anthropic | Gemini |
|-------|--------|-----------|--------|
| Messages | `messages[]` | `system` + `messages[]` | `contents[].parts[]` |
| Stream format | SSE `data:` JSON | SSE `event:` + JSON | SSE JSON |
| Model param | `model` | `model` | `model` in URL path |
| Auth header | `Authorization: Bearer` | `x-api-key` + `anthropic-version` | `x-goog-api-key` (query param) |

## Stream Piping

```mermaid
flowchart LR
    subgraph upstreamConn [Upstream Connection]
        UR[WebClient DataBuffer Flux]
    end
    subgraph pipe [StreamPipeService]
        RB[Line Buffer 8KB]
        Parser[Line Parser]
        Translator[Adapter.translateStreamChunk]
    end
    subgraph clientConn [Client Connection]
        CW[Flux ServerSentEvent]
    end
    UR --> RB --> Parser --> Translator --> CW
```

**Implementation:**

- Upstream: `WebClient` with `bodyToFlux(DataBuffer)` — no full-response buffering
- Downstream: `Flux<ServerSentEvent<String>>` with flush per event
- Line parsing buffers incomplete lines until `\n` is received
- Client disconnect triggers `doOnCancel` → upstream subscription disposed

## Fallback Logic

```mermaid
flowchart TD
    Start[Receive request] --> Resolve[Resolve route chain]
    Resolve --> TryPrimary[Try provider N]
    TryPrimary --> CallUpstream[Open upstream connection]
    CallUpstream --> CheckError{Error before first token?}
    CheckError -->|429/502/503/timeout| HasNext{More fallbacks?}
    CheckError -->|4xx non-retryable| FailClient[Return error to client]
    CheckError -->|Success| Stream[Pipe stream to client]
    Stream --> MidFail{Stream breaks mid-flight?}
    MidFail -->|Yes + content sent| CloseGraceful[Close with DONE]
    MidFail -->|Yes + no content| HasNext
    MidFail -->|No| Done[Complete]
    HasNext -->|Yes| TryPrimary
    HasNext -->|No| FailClient
```

**Retryable:** 408, 429, 500, 502, 503, 504, IOExceptions, timeouts  
**Non-retryable:** 400, 401, 403, 404

## Entity Model

```mermaid
erDiagram
    ChatCompletionRequest ||--o{ Message : contains
    ChatCompletionRequest {
        string model
        Message[] messages
        boolean stream
        float temperature
        int max_tokens
    }
    Message {
        string role
        string content
    }
    RouteDefinition ||--|{ ProviderTarget : primaryAndFallbacks
    RouteDefinition {
        string modelAlias
        ProviderTarget[] chain
    }
    ProviderTarget {
        string providerId
        string upstreamModel
        int priority
    }
    ProviderAdapter ||--|| ProviderTarget : serves
    StreamContext {
        string requestId
        AtomicBoolean clientConnected
        AtomicBoolean contentSent
    }
```

## Timeouts

| Timeout | Default | Scope |
|---------|---------|-------|
| Connect | 5s | TCP to upstream |
| Read (first byte) | 30s | Time to first token |
| Read (inter-chunk) | 120s | Idle between chunks |
| Total request | 300s | Hard ceiling |

Configured in `application.yml` under `gateway.timeouts` and applied via Reactor Netty `HttpClient`.

## Cross-Cutting Concerns

- **RequestIdFilter** — Generates/propagates `X-Request-Id` → SLF4J MDC
- **GlobalExceptionHandler** — Maps validation → 400, unknown model → 404, exhausted fallbacks → 503
- **GatewayHealthIndicator** — Verifies routes are loaded
- **AuthFilter (Phase 2 stub)** — Bearer token validation when `gateway.auth.enabled=true`

## Package Structure

```
com.gateway/
├── config/          WebClient, routes, Jackson, properties
├── controller/      ChatCompletionController
├── dto/             Request/response DTOs
├── routing/         ModelRouter, ProviderRegistry, RouteDefinition
├── fallback/        FallbackOrchestrator
├── streaming/       StreamPipeService, StreamContext
├── adapter/         ProviderAdapter + vendor implementations
├── exception/       GatewayException, GlobalExceptionHandler
├── filter/          RequestIdFilter, AuthFilter
└── health/          GatewayHealthIndicator
```

# LLM API Gateway

A production-ready unified LLM API gateway that exposes an OpenAI-compatible `POST /v1/chat/completions` endpoint, routes requests to OpenAI, Anthropic, and Google Gemini via the adapter pattern, streams SSE responses without full buffering, and silently fails over to backup providers on transient errors.

## Features

- **Unified API** — OpenAI-compatible request/response schema
- **Multi-provider routing** — Config-driven model alias → provider chain (`routes.yml`)
- **Silent fallback** — Automatic retry on 429/502/503/timeouts before first token
- **Streaming** — Reactive WebFlux pipe with fixed 8KB buffer, flush per SSE event
- **Client disconnect** — Upstream cancellation via `doOnCancel`
- **Observability** — Structured JSON logs, request ID correlation, Actuator health/info

## Requirements

- Java 21
- Maven 3.9+
- Docker (optional, for containerized deployment)

## Quick Start

### 1. Set API keys

```bash
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-...
export GOOGLE_API_KEY=AIza...
```

### 2. Run locally

```bash
cd llm-gateway
mvn spring-boot:run
```

### 3. Stream a completion

```bash
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o",
    "messages": [{"role": "user", "content": "Say hello"}],
    "stream": true
  }'
```

### 4. Non-streaming request

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-3-5-sonnet",
    "messages": [{"role": "user", "content": "Say hello"}],
    "stream": false
  }'
```

## Docker

```bash
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-...
export GOOGLE_API_KEY=AIza...

docker compose up --build
```

Health check: `curl http://localhost:8080/actuator/health`

## Configuration

| Variable | Required | Description |
|----------|----------|-------------|
| `OPENAI_API_KEY` | For OpenAI routes | OpenAI bearer token |
| `ANTHROPIC_API_KEY` | For Anthropic routes | Anthropic API key |
| `GOOGLE_API_KEY` | For Gemini routes | Google AI API key |
| `GATEWAY_API_KEY` | Phase 2 | Gateway auth key |
| `SERVER_PORT` | No (default 8080) | HTTP port |

Model routing is defined in `src/main/resources/routes.yml`. Each model alias maps to an ordered provider chain (primary → fallbacks).

## Supported Models (default routes)

| Model Alias | Primary Provider | Fallback |
|-------------|------------------|----------|
| `gpt-4o` | OpenAI | Anthropic |
| `gpt-4o-mini` | OpenAI | — |
| `claude-3-5-sonnet` | Anthropic | OpenAI |
| `gemini-1.5-pro` | Gemini | OpenAI |
| `gemini-1.5-flash` | Gemini | — |

## Development

```bash
mvn verify          # Run all tests
mvn spring-boot:run # Start dev server
```

## Architecture

See [docs/architecture.md](docs/architecture.md) for detailed diagrams and component descriptions.

## Phase 2 (stubbed)

- **Gateway auth** — Set `gateway.auth.enabled=true` and `GATEWAY_API_KEY` to enable Bearer token validation
- **Cost-aware routing** — `costPerInputToken` / `costPerOutputToken` fields on route targets

## License

MIT

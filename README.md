# LLM API Gateway

A production-ready unified LLM API gateway that exposes an OpenAI-compatible `POST /v1/chat/completions` endpoint, routes requests to OpenAI, Anthropic, Google Gemini, and DigitalOcean Gradient via the adapter pattern, streams SSE responses without full buffering, and silently fails over to backup providers on transient errors.

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

**DigitalOcean Gradient (recommended for bundled model access):**

```bash
export DO_API_KEY=doo_v1_...   # or DIGITALOCEAN_API_KEY
```

**Direct provider keys (optional, for legacy routes):**

```bash
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-...
export GOOGLE_API_KEY=AIza...
```

> If your API key was ever exposed in chat or logs, rotate it in the [DigitalOcean control panel](https://cloud.digitalocean.com/) before use.

### 2. Run locally

```bash
cd llm-gateway
mvn spring-boot:run
```

### 3. Stream a completion (DigitalOcean GPT-4o mini)

```bash
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [{"role": "user", "content": "Say hello"}],
    "stream": true
  }'
```

Direct DigitalOcean inference (same key, bypasses gateway):

```bash
curl -N -X POST https://inference.do-ai.run/v1/chat/completions \
  -H "Authorization: Bearer $DO_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "openai-gpt-4o-mini",
    "messages": [{"role": "user", "content": "Say hello"}],
    "stream": true
  }'
```

### 4. Non-streaming request

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-sonnet-5",
    "messages": [{"role": "user", "content": "Say hello"}],
    "stream": false
  }'
```

## Docker

```bash
export DO_API_KEY=doo_v1_...
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-...
export GOOGLE_API_KEY=AIza...

docker compose up --build
```

Health check: `curl http://localhost:8080/actuator/health`

## Configuration

| Variable | Required | Description |
|----------|----------|-------------|
| `DO_API_KEY` | For DigitalOcean routes | DigitalOcean model access key (`doo_v1_...`). Alias: `DIGITALOCEAN_API_KEY` |
| `DO_INFERENCE_BASE_URL` | No (default `https://inference.do-ai.run`) | DigitalOcean serverless inference base URL |
| `OPENAI_API_KEY` | For direct OpenAI routes | OpenAI bearer token |
| `ANTHROPIC_API_KEY` | For direct Anthropic routes | Anthropic API key |
| `GOOGLE_API_KEY` | For Gemini routes | Google AI API key |
| `GATEWAY_API_KEY` | Phase 2 | Gateway auth key |
| `SERVER_PORT` | No (default 8080) | HTTP port |

Model routing is defined in `src/main/resources/routes.yml`. Each model alias maps to an ordered provider chain (primary → fallbacks).

## Supported Models (default routes)

All default routes use [DigitalOcean Gradient serverless inference](https://docs.digitalocean.com/products/inference/) via a single OpenAI-compatible upstream.

| Model Alias | DO Upstream Model ID | Fallback |
|-------------|----------------------|----------|
| `gpt-4o-mini` | `openai-gpt-4o-mini` | `openai-gpt-4o` |
| `gpt-4o` | `openai-gpt-4o` | `anthropic-claude-5-sonnet` |
| `claude-haiku-4.5` | `anthropic-claude-haiku-4.5` | `anthropic-claude-5-sonnet` |
| `claude-sonnet-5` | `anthropic-claude-5-sonnet` | `openai-gpt-4o` |
| `deepseek-v4-flash` | `deepseek-4-flash` | `openai-gpt-4o-mini` |
| `gpt-5-nano` | `openai-gpt-5-nano` | `openai-gpt-4o-mini` |
| `ministral-3-14b` | `mistral-3-14B` | `openai-gpt-4o-mini` |

List available upstream model IDs at runtime:

```bash
curl -s -H "Authorization: Bearer $DO_API_KEY" \
  https://inference.do-ai.run/v1/models | jq '.data[].id'
```

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

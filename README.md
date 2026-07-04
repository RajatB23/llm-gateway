# LLM API Gateway

A production-ready unified LLM API gateway that exposes an OpenAI-compatible `POST /v1/chat/completions` endpoint, routes requests to OpenAI, Anthropic, Google Gemini, and DigitalOcean Gradient via the adapter pattern, streams SSE responses without full buffering, and silently fails over to backup providers on transient errors..

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
export DO_API_KEY=doo_v1_...   # aliases: MODEL_ACCESS_KEY, DIGITALOCEAN_API_KEY
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

## Deployment (DigitalOcean)

For full droplet bootstrap, `.env` setup, manual first deploy, and CI/CD workflow details, see **[docs/deployment.md](docs/deployment.md)**.

After deploy (manual or GitHub Actions), use the steps below to confirm the gateway is healthy on the droplet.

### Verify on droplet

SSH into the droplet, then run these commands from `/opt/llm-gateway`:

```bash
ssh root@YOUR_DROPLET_IP
cd /opt/llm-gateway
```

**1. Get public IP**

```bash
curl -s ifconfig.me
```

**2. Container status (expect `healthy`)**

```bash
docker compose -f deploy/docker-compose.prod.yml ps
```

**3. Health check (localhost)**

```bash
curl -s http://localhost:8080/actuator/health | jq .
# Expect: "status":"UP"
```

**4. Budget-safe streaming test (on droplet)**

```bash
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-v4-flash",
    "messages": [{"role": "user", "content": "Hi"}],
    "stream": true,
    "max_tokens": 10
  }'
```

**5. Public IP test (from your laptop)** — replace `YOUR_DROPLET_IP` with the droplet's public IP (from step 1 or the DO control panel)

```bash
curl -s http://YOUR_DROPLET_IP:8080/actuator/health | jq .

curl -N -X POST http://YOUR_DROPLET_IP:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-v4-flash",
    "messages": [{"role": "user", "content": "Hi"}],
    "stream": true,
    "max_tokens": 10
  }'
```

**6. Expected logs**

```bash
docker compose -f deploy/docker-compose.prod.yml logs --tail=50
```

Follow live logs with `logs -f` — press **Ctrl+C** to stop following without stopping the container:

```bash
docker compose -f deploy/docker-compose.prod.yml logs -f
```

Startup lines to confirm:

```
DigitalOcean API key configured: yes
Started LlmGatewayApplication
```

If you see `DigitalOcean API key configured: no`, check `/opt/llm-gateway/.env` has a valid `DO_API_KEY`, then redeploy.

On a **512MB budget tier**, some OpenAI upstream models return **403 Forbidden** (tier-restricted). The gateway fails over silently — the curl response still succeeds. After a `gpt-5-nano` request, look for a fallback line such as:

```
Fallback triggered from=openai-gpt-5-nano to=deepseek-4-flash reason=... requestId=...
```

For smoke tests on budget droplets, prefer **`deepseek-v4-flash`** (avoids OpenAI 403 fallback) or **`gpt-5-nano`** with `"max_tokens": 10`.

### GitHub Actions secrets

Configure these in **[Settings → Secrets and variables → Actions](https://github.com/RajatB23/llm-gateway/settings/secrets/actions)** before the deploy workflow can succeed:

| Secret | Value | Description |
|--------|-------|-------------|
| `DROPLET_HOST` | Droplet public IP or hostname | e.g. `157.x.x.x` |
| `DROPLET_USER` | `root` | SSH user for deploy |
| `DROPLET_SSH_KEY` | Private key (PEM) | Full private key file contents |
| `DO_API_KEY` | `doo_v1_...` | Synced to droplet `.env` on each deploy |

Workflow: [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) — runs tests then deploys on push to `main`.

### SSH key note

The **public** key must be on the droplet in `/root/.ssh/authorized_keys`. The **private** key goes in GitHub secret `DROPLET_SSH_KEY`.

Generate the key pair on your **laptop** (not on the droplet):

```bash
ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/llm-gateway-deploy -N ""
```

On the droplet: paste the `.pub` contents into `authorized_keys`. In GitHub: paste the private key file (including `BEGIN`/`END` lines) into `DROPLET_SSH_KEY`.

See [docs/deployment.md — Generate a deploy SSH key pair](docs/deployment.md#generate-a-deploy-ssh-key-pair) for the full walkthrough.

### Droplet troubleshooting

| Issue | Fix |
|-------|-----|
| Stuck in `logs -f` | Press **Ctrl+C** to exit log follow mode |
| Port 8080 unreachable from laptop | Check UFW on droplet (`ufw status`) and **DigitalOcean cloud firewall** — allow inbound TCP **8080** |
| Container OOM / restart loop | 512MB droplets need swap (see `deploy/setup-droplet.sh`); **1GB+ RAM recommended** for production |
| `DigitalOcean API key configured: no` | Verify `/opt/llm-gateway/.env` has a valid `DO_API_KEY` |
| Deploy SSH fails | Confirm `DROPLET_SSH_KEY` matches the public key in droplet `authorized_keys` |

More detail: [docs/deployment.md — Troubleshooting](docs/deployment.md#troubleshooting).

## Docker

Only `DO_API_KEY` is required for the default DigitalOcean routes:

```bash
export DO_API_KEY=doo_v1_...
docker compose up --build
```

Health check: `curl http://localhost:8080/actuator/health`

### Docker Verification (Budget-Safe)

Use this flow to smoke-test the container on a **$5 DigitalOcean budget**. Keep prompts short and cap output tokens.

```bash
export DO_API_KEY=doo_v1_...

# Build and start in the background
docker compose up --build -d

# Wait for health (Compose also runs an internal wget healthcheck)
curl -s http://localhost:8080/actuator/health | jq .
# Expect: "status":"UP"

# ONE cheap streaming test (~pennies). Prefer gpt-5-nano or deepseek-v4-flash.
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-5-nano",
    "messages": [{"role": "user", "content": "Hi"}],
    "stream": true,
    "max_tokens": 10
  }'

# Tear down when done
docker compose down
```

**Cost note:** Avoid `gpt-4o`, `claude-sonnet-5`, and other premium aliases for smoke tests — fallbacks can escalate to expensive upstream models. Stick to `gpt-5-nano` or `deepseek-v4-flash` with `max_tokens: 10` and a one-word prompt.

| Model alias | Input / Output (per 1M tokens) | Smoke-test fit |
|-------------|-------------------------------|----------------|
| `gpt-5-nano` | Cheapest OpenAI-class on DO | **Best** — default for verification |
| `deepseek-v4-flash` | $0.14 / $0.28 | **Best** — strong cheap alternative |
| `ministral-3-14b` | $0.20 / $0.20 | Good — flat pricing |
| `gpt-4o-mini` | $0.15 / $0.60 | OK — higher output cost |
| `gpt-4o` | Premium | **Avoid** for smoke tests |
| `claude-sonnet-5` | Premium | **Avoid** for smoke tests |

## Configuration

| Variable | Required | Description |
|----------|----------|-------------|
| `DO_API_KEY` | For DigitalOcean routes | DigitalOcean model access key (`doo_v1_...`). Aliases: `MODEL_ACCESS_KEY`, `DIGITALOCEAN_API_KEY` |
| `DO_INFERENCE_BASE_URL` | No (default `https://inference.do-ai.run/v1`) | DigitalOcean serverless inference base URL (OpenAI-compatible `/v1` prefix) |
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
| `gpt-5-nano` | `openai-gpt-5-nano` | `deepseek-4-flash` |
| `ministral-3-14b` | `mistral-3-14B` | `openai-gpt-4o-mini` |

List available upstream model IDs at runtime:

```bash
curl -s -H "Authorization: Bearer $DO_API_KEY" \
  https://inference.do-ai.run/v1/models | jq '.data[].id'
```

## Verification & Testing

### Run all tests locally

Unit and integration tests (WireMock) run together via Maven:

```bash
cd llm-gateway
mvn verify
```

Expected output ends with `BUILD SUCCESS` and **35 tests** passing (unit + integration).

### Run a single test class

```bash
# Unit: adapter translation
mvn -Dtest=OpenAiAdapterTest test

# Unit: fallback logic
mvn -Dtest=FallbackOrchestratorTest test

# Integration: full HTTP + WireMock
mvn -Dtest=ChatCompletionIntegrationTest test
```

Run one method:

```bash
mvn -Dtest=ChatCompletionIntegrationTest#happyPathStreaming_returnsUnifiedSseChunksAndDone test
```

### Two-terminal local workflow

Use **two terminals**: one to run the gateway, one for curl tests.

**Terminal 1 — start the gateway**

```bash
cd llm-gateway
export DO_API_KEY=doo_v1_...   # MUST be in this same shell before startup
mvn spring-boot:run
```

> **Important:** `export DO_API_KEY=...` must run in the **same shell session** as `mvn spring-boot:run`. If you export in one terminal and start the app in another, the key will not be visible and DigitalOcean routes will fail.

**Terminal 2 — run curl tests** (after startup logs confirm readiness; see below)

```bash
curl -s http://localhost:8080/actuator/health | jq .
```

### Startup confirmation

In Terminal 1, wait for these log lines before sending requests from Terminal 2:

```
DigitalOcean API key configured: yes
Started LlmGatewayApplication
```

If you see `DigitalOcean API key configured: no`, stop the app (`Ctrl+C`), re-export `DO_API_KEY` in that same shell, and restart.

### Troubleshooting: port 8080 already in use

If startup fails with `Port 8080 was already in use`:

```bash
# Option A — kill whatever holds the port
fuser -k 8080/tcp

# Option B — inspect then kill manually
lsof -i :8080
kill <PID>
```

Then restart in Terminal 1 (`mvn spring-boot:run`).

### DigitalOcean budget tier fallback

On a **budget DigitalOcean tier** ($5 and similar), some OpenAI upstream models may return **403 Forbidden** (tier-restricted). The gateway handles this silently:

- **`gpt-5-nano`** — primary `openai-gpt-5-nano` may 403; gateway falls back to **`deepseek-4-flash`**
- **`gpt-4o-mini`** — primary `openai-gpt-4o-mini` may 403; gateway falls back to **`openai-gpt-4o`** (more expensive — avoid for smoke tests)

You will not see an error in the curl response; check gateway logs for fallback lines if you want to confirm which upstream was used. For local verification on a budget tier, prefer **`deepseek-v4-flash`** or **`gpt-5-nano`** with `"max_tokens": 10`.

### Health check

```bash
curl -s http://localhost:8080/actuator/health | jq .
```

Expect `"status":"UP"`.

### Gateway streaming test (curl)

Budget-safe examples — use **`deepseek-v4-flash`** or **`gpt-5-nano`** with a short prompt and `"max_tokens": 10`:

```bash
# Preferred on budget DO tier (no OpenAI 403 fallback needed)
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-v4-flash",
    "messages": [{"role": "user", "content": "Hi"}],
    "stream": true,
    "max_tokens": 10
  }'
```

```bash
# Also cheap; may silently fall back to deepseek-4-flash on budget tier
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-5-nano",
    "messages": [{"role": "user", "content": "Hi"}],
    "stream": true,
    "max_tokens": 10
  }'
```

You should see SSE `data:` lines ending with `data: [DONE]`.

### Direct DigitalOcean upstream (comparison)

Same key, bypasses the gateway — useful to confirm DO credentials and model IDs:

```bash
curl -N -X POST https://inference.do-ai.run/v1/chat/completions \
  -H "Authorization: Bearer $DO_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "openai-gpt-4o-mini",
    "messages": [{"role": "user", "content": "Say hello in one word"}],
    "stream": true
  }'
```

### Docker Compose verification

See [Docker Verification (Budget-Safe)](#docker-verification-budget-safe) above.

### CI on GitHub Actions

Workflow: [`.github/workflows/ci.yml`](.github/workflows/ci.yml)

| Trigger | Branches |
|---------|----------|
| `push` | `main`, `master` |
| `pull_request` | `main`, `master` |

Steps on every run:

1. Checkout repository
2. JDK **21** (Temurin) with **Maven dependency cache**
3. `mvn -B verify` — compile, run all tests, package JAR

On success you should see a green check with **BUILD SUCCESS** in the job log. On failure, inspect the **Build and test** step for the failing test name and stack trace.

### Deploy to DigitalOcean (CI/CD)

See **[Deployment (DigitalOcean)](#deployment-digitalocean)** above for verify-on-droplet steps, GitHub secrets, SSH key setup, and troubleshooting. Full bootstrap guide: **[docs/deployment.md](docs/deployment.md)**.

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

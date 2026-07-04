# Deploying LLM Gateway to DigitalOcean

This guide covers one-time droplet setup, manual first deploy, and automated CI/CD via GitHub Actions.

> **RAM note:** A **512MB** droplet can run the gateway with swap and tight JVM limits, but **1GB+ RAM is recommended** for production. The production compose file sets `-Xmx256m` and a 384MB container limit.

## Prerequisites

- DigitalOcean droplet (Ubuntu 22.04/24.04), e.g. `ubuntu-s-1vcpu-512mb-10gb-nyc1`
- [DigitalOcean model access key](https://docs.digitalocean.com/products/inference/) (`DO_API_KEY`, starts with `doo_v1_...`)
- GitHub repository: [RajatB23/llm-gateway](https://github.com/RajatB23/llm-gateway)
- SSH access to the droplet as `root`

## 1. Bootstrap the droplet (one-time)

SSH into the droplet and run the setup script:

```bash
ssh root@YOUR_DROPLET_IP

# Option A — clone repo first, then run script
apt-get update && apt-get install -y git
git clone https://github.com/RajatB23/llm-gateway.git /opt/llm-gateway
bash /opt/llm-gateway/deploy/setup-droplet.sh

# Option B — download script only (repo cloned by script)
curl -fsSL https://raw.githubusercontent.com/RajatB23/llm-gateway/main/deploy/setup-droplet.sh -o /tmp/setup-droplet.sh
bash /tmp/setup-droplet.sh
```

The script installs Docker, Docker Compose, Git, configures UFW (ports **22** and **8080**), adds **1GB swap** for small droplets, and clones the repo to `/opt/llm-gateway`.

## 2. Create environment file on the droplet

```bash
cd /opt/llm-gateway
cp .env.example .env
nano .env   # set DO_API_KEY=your_key_here
chmod 600 .env
```

Never commit `.env` or API keys to Git.

## 3. Configure GitHub Actions secrets

In your GitHub repo: **Settings → Secrets and variables → Actions → New repository secret**

| Secret | Value | Description |
|--------|-------|-------------|
| `DROPLET_HOST` | Droplet public IP or hostname | e.g. `157.x.x.x` |
| `DROPLET_USER` | `root` | SSH user |
| `DROPLET_SSH_KEY` | Private key (PEM) | Matching public key in `/root/.ssh/authorized_keys` on droplet |
| `DO_API_KEY` | `doo_v1_...` | Synced to droplet `.env` on each deploy |

### Generate a deploy SSH key pair

On your **local machine**:

```bash
ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/llm-gateway-deploy -N ""
cat ~/.ssh/llm-gateway-deploy.pub
```

On the **droplet**:

```bash
echo "PASTE_PUBLIC_KEY_HERE" >> /root/.ssh/authorized_keys
chmod 600 /root/.ssh/authorized_keys
```

Add the **private** key contents to GitHub secret `DROPLET_SSH_KEY`:

```bash
cat ~/.ssh/llm-gateway-deploy   # copy entire file including BEGIN/END lines
```

## 4. Manual first deploy

Before enabling CI/CD, verify the stack on the droplet:

```bash
cd /opt/llm-gateway
git pull origin main
docker compose -f deploy/docker-compose.prod.yml up --build -d
docker compose -f deploy/docker-compose.prod.yml ps
docker compose -f deploy/docker-compose.prod.yml logs -f
```

## 5. CI/CD auto deploy

Workflow: [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml)

| Trigger | Behavior |
|---------|----------|
| Push to `main` | Run tests → deploy via SSH |
| `workflow_dispatch` | Manual deploy from Actions tab |

On each deploy the workflow:

1. Runs `mvn verify`
2. SSHs to the droplet
3. Writes `/opt/llm-gateway/.env` from `DO_API_KEY` secret
4. `git reset --hard origin/main`
5. `docker compose -f deploy/docker-compose.prod.yml up --build -d`
6. Waits for `/actuator/health`

Monitor runs under **Actions → Deploy**.

## 6. Verify deployment

Replace `YOUR_DROPLET_IP` with your droplet's public IP.

```bash
# Health
curl -s http://YOUR_DROPLET_IP:8080/actuator/health | jq .

# Budget-safe chat test (from any machine)
curl -N -X POST http://YOUR_DROPLET_IP:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-5-nano",
    "messages": [{"role": "user", "content": "Hi"}],
    "stream": true,
    "max_tokens": 10
  }'
```

On the droplet itself:

```bash
curl -s http://localhost:8080/actuator/health
docker compose -f /opt/llm-gateway/deploy/docker-compose.prod.yml logs --tail=50
```

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Container OOM / restart loop | Add swap (`setup-droplet.sh`), upgrade to 1GB RAM, or lower `-Xmx` in compose |
| Port 8080 unreachable | `ufw status`, DigitalOcean cloud firewall, `docker compose ps` |
| `DigitalOcean API key configured: no` | Check `/opt/llm-gateway/.env` has valid `DO_API_KEY` |
| Deploy SSH fails | Verify `DROPLET_SSH_KEY` matches droplet `authorized_keys` |
| Build slow on 512MB | Normal — swap helps; first build may take several minutes |

## Files reference

| File | Purpose |
|------|---------|
| `deploy/setup-droplet.sh` | One-time droplet bootstrap |
| `deploy/docker-compose.prod.yml` | Production compose with JVM/memory limits |
| `.env.example` | Environment variable template |
| `.github/workflows/deploy.yml` | CI/CD deploy workflow |
| `Dockerfile` | Multi-stage build; respects `JAVA_OPTS` |

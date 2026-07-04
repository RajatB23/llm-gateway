#!/usr/bin/env bash
# One-time bootstrap for Ubuntu DigitalOcean droplet (512MB+ RAM).
# Run as root: curl -fsSL ... | bash   OR   bash setup-droplet.sh
set -euo pipefail

APP_DIR="/opt/llm-gateway"
SWAP_SIZE="${SWAP_SIZE:-1G}"
REPO_URL="${REPO_URL:-https://github.com/RajatB23/llm-gateway.git}"

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root (e.g. sudo bash $0)"
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive

echo "==> Updating packages..."
apt-get update -qq
apt-get install -y -qq ca-certificates curl git ufw

echo "==> Installing Docker..."
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker

echo "==> Ensuring Docker Compose plugin..."
apt-get install -y -qq docker-compose-plugin

echo "==> Configuring swap (${SWAP_SIZE}) for small-RAM droplets..."
if [ ! -f /swapfile ]; then
  fallocate -l "${SWAP_SIZE}" /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=1024 status=progress
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  sysctl vm.swappiness=10
  grep -q 'vm.swappiness' /etc/sysctl.conf || echo 'vm.swappiness=10' >> /etc/sysctl.conf
  echo "Swap enabled."
else
  echo "Swap file already exists, skipping."
fi

echo "==> Configuring UFW (allow SSH + gateway port)..."
ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp comment 'SSH'
ufw allow 8080/tcp comment 'LLM Gateway'
ufw --force enable

echo "==> Creating app directory ${APP_DIR}..."
mkdir -p "${APP_DIR}"

if [ ! -d "${APP_DIR}/.git" ]; then
  echo "==> Cloning repository..."
  git clone "${REPO_URL}" "${APP_DIR}"
else
  echo "Repository already cloned at ${APP_DIR}, skipping clone."
fi

cat <<'NEXT'

Bootstrap complete.

Next steps (on this droplet):
  1. Create /opt/llm-gateway/.env with your DO_API_KEY (see .env.example)
  2. Add your SSH public key to /root/.ssh/authorized_keys for GitHub Actions
  3. Configure GitHub repository secrets (see docs/deployment.md)
  4. First deploy:
       cd /opt/llm-gateway
       docker compose -f deploy/docker-compose.prod.yml up --build -d

For 512MB droplets, consider upgrading to 1GB+ for production workloads.

NEXT

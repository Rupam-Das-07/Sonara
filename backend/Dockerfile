# ============================================================
# Sonara Backend — Multi-process container
# ============================================================
#
# Runs three processes in a single Railway container:
#   1. Python YTMusic service   (gevent WSGIServer, 127.0.0.1:5000)
#   2. Python yt-dlp audio service (gevent WSGIServer, 127.0.0.1:5001)
#   3. Node.js gateway          (Express, 0.0.0.0:$PORT)
#
# Python services are loopback-only. Only the Node gateway port
# is reachable from outside the container.
#
# Railway service root must be set to /backend so this Dockerfile
# is the deployment entry point.
# ============================================================

# ── Base image ────────────────────────────────────────────────────────────────
# Debian-based Node 22 LTS (Bookworm slim).
# Node 22 is the current LTS line (>= 20 required by package.json engines).
FROM node:22-bookworm-slim

# ── System packages ───────────────────────────────────────────────────────────
# - python3, python3-venv, python3-pip: Python runtime + venv/pip support
# - ffmpeg: required by yt-dlp for audio format handling
# - ca-certificates: TLS root store for outbound HTTPS (ytmusicapi, JioSaavn, etc.)
# - curl: lightweight health-check tool (available for manual inspection)
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-venv \
    python3-pip \
    ffmpeg \
    ca-certificates \
    curl \
 && rm -rf /var/lib/apt/lists/*

# ── Python virtual environment ─────────────────────────────────────────────────
# Create an isolated venv so pip installs never conflict with system Python.
ENV VIRTUAL_ENV=/opt/venv
RUN python3 -m venv "$VIRTUAL_ENV"
ENV PATH="$VIRTUAL_ENV/bin:$PATH"

# ── Python dependencies ────────────────────────────────────────────────────────
# Copy only requirements.txt first to maximise layer cache hits.
WORKDIR /app
COPY python/requirements.txt /app/python/requirements.txt
RUN pip install --no-cache-dir -r /app/python/requirements.txt

# ── Node production dependencies ───────────────────────────────────────────────
# Copy package files first for cache efficiency.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

# ── Backend source ─────────────────────────────────────────────────────────────
COPY --chown=node:node . .

# ── Runtime-writable directories & ownership ───────────────────────────────────
# blitz.cloud runs containers as UID 1000:GID 1000 with dropped capabilities.
# The base image already provides the 'node' user (uid=1000, gid=1000).
# Ensure /app/data (identity store) and /home/node are owned by node:node.
RUN mkdir -p /app/data /home/node/.cache \
 && chown -R node:node /app /home/node

# ── Runtime environment ────────────────────────────────────────────────────────
# NODE_ENV=production ensures rate limiting is active and dev-bypass is off.
# PORT is supplied by Railway at runtime — do NOT hard-code it here.
# Python service URLs use loopback defaults matching src/config/env.js.
ENV NODE_ENV=production \
    HOME=/home/node \
    PYTHON_YTMUSIC_URL=http://127.0.0.1:5000 \
    PYTHON_AUDIO_URL=http://127.0.0.1:5001

# ── Startup script ─────────────────────────────────────────────────────────────
COPY docker/start.sh /start.sh
RUN chmod +x /start.sh

# ── Run as non-root user ───────────────────────────────────────────────────────
USER node

# Expose is documentation only — Railway routes traffic to $PORT on the Node
# gateway. Python ports 5000/5001 are intentionally NOT listed here.
EXPOSE 3002

CMD ["/start.sh"]


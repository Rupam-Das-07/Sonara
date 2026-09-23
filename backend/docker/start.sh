#!/usr/bin/env bash
# =============================================================================
# docker/start.sh — Sonara backend process launcher
# =============================================================================
#
# Starts three processes inside the container:
#   1. Python YTMusic service   → 127.0.0.1:5000
#   2. Python yt-dlp audio service → 127.0.0.1:5001
#   3. Node.js gateway          → 0.0.0.0:$PORT
#
# Design:
#   - All three PIDs are tracked explicitly.
#   - SIGTERM/SIGINT are caught and forwarded to all children.
#   - If any required child exits unexpectedly before the shutdown signal,
#     the script terminates the container (so Railway can restart it rather
#     than leaving a silent degraded state).
#   - Uses bash for reliable process management; guaranteed present in
#     node:22-bookworm-slim via bash package.
#
# =============================================================================

set -euo pipefail

# ── Activate the Python virtual environment ───────────────────────────────────
VIRTUAL_ENV=/opt/venv
PATH="$VIRTUAL_ENV/bin:$PATH"
export PATH

# Working directory is /app (set in Dockerfile WORKDIR)
cd /app

# ── PIDs (populated after each process launches) ──────────────────────────────
PID_YTMUSIC=""
PID_AUDIO=""
PID_NODE=""

# ── Shutdown handler ──────────────────────────────────────────────────────────
# Called on SIGTERM, SIGINT, or unexpected child exit.
# Sends SIGTERM to all living children and waits for them to exit.
shutdown() {
    local signal="${1:-SIGTERM}"
    echo "[start.sh] Received ${signal}. Shutting down all processes..."

    # Forward to each child if still running
    for pid in "$PID_YTMUSIC" "$PID_AUDIO" "$PID_NODE"; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            echo "[start.sh] Sending SIGTERM to PID $pid"
            kill -TERM "$pid" 2>/dev/null || true
        fi
    done

    # Wait for all children
    for pid in "$PID_YTMUSIC" "$PID_AUDIO" "$PID_NODE"; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            wait "$pid" 2>/dev/null || true
        fi
    done

    echo "[start.sh] All processes stopped."
    if [ "$signal" = "SIGTERM" ] || [ "$signal" = "SIGINT" ]; then
        exit 0
    fi
}

trap 'shutdown SIGTERM' TERM
trap 'shutdown SIGINT'  INT

# ── Helper: wait a moment and confirm a child is still running ────────────────
# Returns 1 if the process has already died (start failure).
check_pid_alive() {
    local pid="$1"
    local label="$2"
    sleep 1
    if ! kill -0 "$pid" 2>/dev/null; then
        echo "[start.sh] ERROR: ${label} (PID ${pid}) failed to start." >&2
        return 1
    fi
    return 0
}

# ── 1. Start Python YTMusic service ──────────────────────────────────────────
# PORT=5000: overrides Railway's injected PORT so the service always binds
# 127.0.0.1:5000, not the public Railway port that Node.js will use.
echo "[start.sh] Starting Python YTMusic service (127.0.0.1:5000)..."
PORT=5000 python python/ytmusic_service.py &
PID_YTMUSIC=$!
echo "[start.sh] YTMusic service PID: ${PID_YTMUSIC}"
check_pid_alive "$PID_YTMUSIC" "YTMusic service" || { shutdown; exit 1; }

# ── 2. Start Python audio service ────────────────────────────────────────────
# PORT=5001: same rationale as above — isolates this service from Railway's PORT.
echo "[start.sh] Starting Python audio service (127.0.0.1:5001)..."
PORT=5001 python python/youtube_audio_api.py &
PID_AUDIO=$!
echo "[start.sh] Audio service PID: ${PID_AUDIO}"
check_pid_alive "$PID_AUDIO" "Audio service" || { shutdown; exit 1; }

# ── 3. Start Node.js gateway ─────────────────────────────────────────────────
echo "[start.sh] Starting Node.js gateway (0.0.0.0:${PORT:-3002})..."
node src/index.js &
PID_NODE=$!
echo "[start.sh] Node gateway PID: ${PID_NODE}"
check_pid_alive "$PID_NODE" "Node gateway" || { shutdown; exit 1; }

echo "[start.sh] All three processes running. Container is live."

# ── Monitor: exit container if any required process dies unexpectedly ─────────
# wait -n returns when any child exits; we then check which one died.
while true; do
    # Wait for any child to finish.
    # "|| true" guards against set -e: wait -n returns the exit code of the
    # reaped child, and a non-zero exit would abort the script before the
    # kill -0 liveness checks below can determine which process died.
    wait -n "$PID_YTMUSIC" "$PID_AUDIO" "$PID_NODE" 2>/dev/null || true
    exit_status=$?

    # Determine which process exited
    if ! kill -0 "$PID_YTMUSIC" 2>/dev/null; then
        echo "[start.sh] ERROR: YTMusic service (PID ${PID_YTMUSIC}) exited unexpectedly (status ${exit_status})." >&2
        shutdown "unexpected-exit"
        exit 1
    fi
    if ! kill -0 "$PID_AUDIO" 2>/dev/null; then
        echo "[start.sh] ERROR: Audio service (PID ${PID_AUDIO}) exited unexpectedly (status ${exit_status})." >&2
        shutdown "unexpected-exit"
        exit 1
    fi
    if ! kill -0 "$PID_NODE" 2>/dev/null; then
        echo "[start.sh] ERROR: Node gateway (PID ${PID_NODE}) exited unexpectedly (status ${exit_status})." >&2
        shutdown "unexpected-exit"
        exit 1
    fi

    # All three still alive — a different child (e.g. orphan) exited, loop again
done

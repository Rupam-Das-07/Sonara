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

# Validate before launching children or evaluating the value as shell arithmetic.
# Keep the deadline within one day and reject empty, fractional and signed values.
STARTUP_TIMEOUT_SECONDS="${STARTUP_TIMEOUT_SECONDS-60}"
if [[ ! "$STARTUP_TIMEOUT_SECONDS" =~ ^[0-9]{1,5}$ ]] ||
   (( 10#$STARTUP_TIMEOUT_SECONDS < 1 || 10#$STARTUP_TIMEOUT_SECONDS > 86400 )); then
    echo "[start.sh] ERROR: STARTUP_TIMEOUT_SECONDS must be an integer from 1 to 86400." >&2
    exit 1
fi
STARTUP_TIMEOUT_SECONDS=$((10#$STARTUP_TIMEOUT_SECONDS))

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
# Allows ten seconds for graceful exit, then kills only the tracked children.
shutdown() {
    local signal="${1:-SIGTERM}"
    local pid alive
    local deadline=$((SECONDS + 10))
    # A second signal must not re-enter cleanup or restart its deadline.
    trap '' TERM INT
    echo "[start.sh] Received ${signal}. Shutting down all processes..."

    # Forward to each child if still running
    for pid in "$PID_YTMUSIC" "$PID_AUDIO" "$PID_NODE"; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            echo "[start.sh] Sending SIGTERM to PID $pid"
            kill -TERM "$pid" 2>/dev/null || true
        fi
    done

    while (( SECONDS < deadline )); do
        alive=0
        for pid in "$PID_YTMUSIC" "$PID_AUDIO" "$PID_NODE"; do
            if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
                alive=1
            fi
        done
        if [ "$alive" -eq 0 ]; then
            break
        fi
        sleep 0.1
    done

    for pid in "$PID_YTMUSIC" "$PID_AUDIO" "$PID_NODE"; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            echo "[start.sh] Sending SIGKILL to PID $pid"
            kill -KILL "$pid" 2>/dev/null || true
        fi
        # Never block in wait on a still-live child (even after SIGKILL).
        if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
            wait "$pid" 2>/dev/null || true
        fi
    done

    echo "[start.sh] Shutdown complete."
    if [ "$signal" = "SIGTERM" ] || [ "$signal" = "SIGINT" ]; then
        exit 0
    fi
}

trap 'shutdown SIGTERM' TERM
trap 'shutdown SIGINT'  INT

# ── Helper: wait for process liveness and HTTP readiness ──────────────────────
# A live Python process can still be importing ytmusicapi/yt-dlp. Starting Node
# after only a fixed sleep makes the first Android requests fail unpredictably.
# Returns 1 if the child exits or its readiness endpoint does not respond before
# STARTUP_TIMEOUT_SECONDS (bounded so a broken dependency fails the container).
wait_for_http() {
    local pid="$1"
    local label="$2"
    local url="$3"
    local timeout_seconds="${STARTUP_TIMEOUT_SECONDS:-60}"
    local deadline=$((SECONDS + timeout_seconds))

    while (( SECONDS < deadline )); do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "[start.sh] ERROR: ${label} (PID ${pid}) exited before becoming ready." >&2
            return 1
        fi
        if curl --fail --silent --show-error --max-time 2 "$url" >/dev/null 2>&1; then
            echo "[start.sh] ${label} is ready at ${url}."
            return 0
        fi
        sleep 1
    done

    echo "[start.sh] ERROR: ${label} did not become ready at ${url} within ${timeout_seconds}s." >&2
    return 1
}

# ── 1. Start Python YTMusic service ──────────────────────────────────────────
# PORT=5000: overrides Railway's injected PORT so the service always binds
# 127.0.0.1:5000, not the public Railway port that Node.js will use.
echo "[start.sh] Starting Python YTMusic service (127.0.0.1:5000)..."
PORT=5000 python python/ytmusic_service.py &
PID_YTMUSIC=$!
echo "[start.sh] YTMusic service PID: ${PID_YTMUSIC}"
wait_for_http "$PID_YTMUSIC" "YTMusic service" "http://127.0.0.1:5000/health" || { shutdown "startup-failure"; exit 1; }

# ── 2. Start Python audio service ────────────────────────────────────────────
# PORT=5001: same rationale as above — isolates this service from Railway's PORT.
echo "[start.sh] Starting Python audio service (127.0.0.1:5001)..."
PORT=5001 python python/youtube_audio_api.py &
PID_AUDIO=$!
echo "[start.sh] Audio service PID: ${PID_AUDIO}"
wait_for_http "$PID_AUDIO" "Audio service" "http://127.0.0.1:5001/" || { shutdown "startup-failure"; exit 1; }

# ── 3. Start Node.js gateway ─────────────────────────────────────────────────
echo "[start.sh] Starting Node.js gateway (0.0.0.0:${PORT:-3002})..."
node src/index.js &
PID_NODE=$!
echo "[start.sh] Node gateway PID: ${PID_NODE}"
wait_for_http "$PID_NODE" "Node gateway" "http://127.0.0.1:${PORT:-3002}/health" || { shutdown "startup-failure"; exit 1; }

echo "[start.sh] All three processes running. Container is live."

# ── Monitor: exit container if any required process dies unexpectedly ─────────
# wait -n returns when any child exits; we then check which one died.
while true; do
    # Wait for any child to finish.
    # Temporarily disable errexit: wait -n returns the exit code of the reaped
    # child, and a non-zero exit must not abort before liveness checks identify
    # which required process died.
    set +e
    wait -n "$PID_YTMUSIC" "$PID_AUDIO" "$PID_NODE" 2>/dev/null
    exit_status=$?
    set -e

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

"""
youtube_audio_api.py
--------------------
Stand-alone Flask micro-server for YouTube audio extraction via yt-dlp.
Runs on port 5001 — completely separate from the existing ytmusic_service.py
(port 5000) and the Node.js backend (port 3001).

Start this server independently:
  python youtube_audio_api.py

Endpoints:
  GET /get-youtube-audio?url=<YOUTUBE_URL>
  GET /stream-youtube-audio?video_id=<ID>&audio_url=<DIRECT_STREAM_URL>  (proxy)
  GET /search-youtube?q=<QUERY>&limit=10
  GET /api/debug/stream-cache

Response JSON:
  { "title": "...", "thumbnail": "...", "audio_url": "..." }
"""

# STRICT MONKEY-PATCHING
# MUST BE EXECUTED BEFORE ANY OTHER IMPORTS
import gevent.monkey
gevent.monkey.patch_all()

import requests
import re
from flask import Flask, request, jsonify, Response, stream_with_context
from flask_cors import CORS

# Import only our new service — existing files are never touched
from youtube_service import get_audio_info, search_youtube

# Import URL encoding for proxy URL construction
from urllib.parse import quote, urlparse, parse_qs, urljoin
import ipaddress
import socket


# Server-side cache: thread-safe, bounded TTL cache (raw audio_url → http_headers dict)
# This prevents memory leaks and OOM crashes under long-running sessions.
import time
from collections import OrderedDict
from threading import Lock

class ThreadSafeTTLCache:
    """
    A thread-safe in-memory cache with Time-To-Live (TTL) expiration and size boundaries.
    """
    def __init__(self, maxsize: int, ttl_seconds: float):
        self.maxsize = maxsize
        self.ttl = ttl_seconds
        self._cache = OrderedDict()
        self._lock = Lock()

    def __setitem__(self, key: str, value: dict):
        if not key:
            return
        with self._lock:
            now = time.time()
            # Clean expired first to control capacity
            self._cleanup_unlocked(now)

            # Evict if capacity exceeded
            if key in self._cache:
                self._cache.pop(key)
            elif len(self._cache) >= self.maxsize:
                oldest_key, _ = self._cache.popitem(last=False)
                app.logger.info("[CacheEviction] Cache full. Evicted oldest entry: %s...", oldest_key[:50])

            self._cache[key] = (value, now + self.ttl)
            app.logger.info("[CacheInsert] Cached. Size: %d/%d", len(self._cache), self.maxsize)

    def get(self, key: str, default=None) -> dict:
        if not key:
            return default
        with self._lock:
            now = time.time()
            if key not in self._cache:
                return default

            value, expires_at = self._cache[key]
            if now > expires_at:
                self._cache.pop(key)
                return default

            return value

    def _cleanup_unlocked(self, now: float):
        expired_keys = [k for k, (_, exp) in self._cache.items() if now > exp]
        for k in expired_keys:
            self._cache.pop(k)

    def cleanup(self):
        with self._lock:
            self._cleanup_unlocked(time.time())

# ── App setup ────────────────────────────────────────────────────────────────

app = Flask(__name__)

# Bounded TTL cache instance (Max 100 entries, 2.5 hours TTL)
_stream_headers_cache = ThreadSafeTTLCache(maxsize=100, ttl_seconds=9000.0)

# Phase 2: Stream URL Cache
_stream_url_cache = ThreadSafeTTLCache(maxsize=5000, ttl_seconds=9000.0)
_cache_metrics = {
    "cacheHits": 0,
    "cacheMisses": 0,
    "ytDlpExtractions": 0,
    "totalExtractionTimeMs": 0,
    "lastExtractionTimeMs": 0,
    "staleUrlRecoveries": 0
}

import threading
_in_flight_cache_fills = {}
_in_flight_lock = threading.Lock()


def _resolve_and_cache_stream(video_id: str) -> dict:
    """
    Run a yt-dlp extraction for *video_id*, record timing metrics, update the
    headers cache and stream-URL cache, and return the completed payload dict.

    Called by both the normal extraction path in ``get_youtube_audio()`` and
    the 403-recovery path in ``stream_youtube_audio()``.

    Returns:
        payload dict with keys: title, thumbnail, audio_url (proxy), expiresAt,
        cachedAt.

    Raises:
        Whatever ``get_audio_info()`` raises (ValueError, RuntimeError, …).
    """
    url = f"https://www.youtube.com/watch?v={video_id}"
    t0 = time.time()
    data = get_audio_info(url)
    t1 = time.time()

    raw_audio_url = data["audio_url"]

    _cache_metrics["ytDlpExtractions"] += 1
    extraction_time_ms = int((t1 - t0) * 1000)
    _cache_metrics["totalExtractionTimeMs"] += extraction_time_ms
    _cache_metrics["lastExtractionTimeMs"] = extraction_time_ms

    # Cache the http_headers that yt-dlp requires for streaming
    try:
        _stream_headers_cache[raw_audio_url] = data.get("http_headers", {})
    except Exception as cache_err:
        app.logger.warning("Failed to write to stream headers cache: %s", cache_err)

    # Return a local proxy URL rather than the raw googlevideo.com URL
    proxy_url = f"/stream-youtube-audio?video_id={video_id}&audio_url={quote(raw_audio_url, safe='')}"

    # Parse the URL's ?expire= param; fall back to +2.5 hours if absent
    try:
        parsed = urlparse(raw_audio_url)
        qs = parse_qs(parsed.query)
        expire = int(qs.get("expire", ["0"])[0])
        if expire == 0:
            expire = int(time.time()) + 9000
    except Exception:
        expire = int(time.time()) + 9000

    payload = {
        "title": data.get("title", ""),
        "thumbnail": data.get("thumbnail", ""),
        "audio_url": proxy_url,
        "expiresAt": expire,
        "cachedAt": int(time.time()),
    }
    _stream_url_cache[video_id] = payload
    return payload


def extract_video_id(url: str) -> str:
    match = re.search(r"(?:v=|\/)([0-9A-Za-z_-]{11})", url)
    return match.group(1) if match else None


# Allow requests from any origin so the React frontend (localhost:3000)
# can call this API without CORS errors.
CORS(app)


# ── Health check ─────────────────────────────────────────────────────────────

@app.get("/")
def root():
    """Quick sanity-check endpoint."""
    return jsonify({
        "service": "youtube-audio-api",
        "status": "ok",
        "endpoints": [
            "/get-youtube-audio?url=<YOUTUBE_URL>",
            "/stream-youtube-audio?video_id=<ID>&audio_url=<DIRECT_STREAM_URL>  (proxy)",
            "/search-youtube?q=<QUERY>&limit=10",
            "/api/debug/stream-cache"
        ],
    })


# ── Main endpoint ─────────────────────────────────────────────────────────────

@app.get("/get-youtube-audio")
def get_youtube_audio():
    """
    Extract audio info from a YouTube URL without downloading anything.
    Uses Phase 2 stream caching.
    """

    url = request.args.get("url", "").strip()

    # ── Validate input ───────────────────────────────────────────────────────
    if not url:
        return jsonify({
            "error": "Missing required query param: url",
            "example": "/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        }), 400

    video_id = extract_video_id(url)
    if not video_id:
        return jsonify({"error": "Invalid YouTube URL"}), 400

    # Normalize URL to ensure in_flight_extractions deduplicates perfectly
    normalized_url = f"https://www.youtube.com/watch?v={video_id}"

    # Check Cache
    cached_payload = _stream_url_cache.get(video_id)
    if cached_payload:
        _cache_metrics["cacheHits"] += 1
        return jsonify(cached_payload), 200

    with _in_flight_lock:
        if video_id in _in_flight_cache_fills:
            event = _in_flight_cache_fills[video_id]
            is_new = False
        else:
            event = threading.Event()
            _in_flight_cache_fills[video_id] = event
            is_new = True

    if not is_new:
        event.wait()
        cached_payload = _stream_url_cache.get(video_id)
        if cached_payload:
            _cache_metrics["cacheHits"] += 1
            return jsonify(cached_payload), 200
        else:
            return jsonify({"error": "Concurrent extraction failed."}), 500

    _cache_metrics["cacheMisses"] += 1

    # ── Call service ─────────────────────────────────────────────────────────
    try:
        payload = _resolve_and_cache_stream(video_id)
        return jsonify(payload), 200

    except ValueError as exc:
        # Bad / non-YouTube URL
        return jsonify({"error": str(exc)}), 400

    except RuntimeError as exc:
        # Video unavailable or no audio streams
        return jsonify({"error": str(exc)}), 422

    except Exception as exc:
        # Catch-all for unexpected errors (network glitch, yt-dlp issue, etc.)
        app.logger.exception("Unexpected error processing URL: %s", url)
        return jsonify({"error": "An unexpected server error occurred.", "detail": str(exc)}), 500

    finally:
        with _in_flight_lock:
            if video_id in _in_flight_cache_fills:
                del _in_flight_cache_fills[video_id]
        event.set()


# ── Stream Proxy endpoint ───────────────────────────────────────────────────

# ── Audio Proxy Security Constants & Validators (Finding #4 Remediation) ──────
MAX_AUDIO_REDIRECTS = 3
REDIRECT_STATUS_CODES = {301, 302, 303, 307, 308}

def is_safe_ip(ip_str_or_obj) -> bool:
    """
    Validates that an IP address is not private, loopback, link-local,
    multicast, reserved, or unspecified.
    """
    try:
        if isinstance(ip_str_or_obj, (ipaddress.IPv4Address, ipaddress.IPv6Address)):
            ip_obj = ip_str_or_obj
        else:
            ip_obj = ipaddress.ip_address(str(ip_str_or_obj).strip("[]"))
        return (
            not ip_obj.is_private
            and not ip_obj.is_loopback
            and not ip_obj.is_link_local
            and not ip_obj.is_multicast
            and not ip_obj.is_reserved
            and not ip_obj.is_unspecified
        )
    except ValueError:
        return False


def is_safe_resolved_ip(hostname: str, port: int = 443) -> bool:
    """
    Resolves hostname via DNS and verifies that all resolved IP addresses
    belong to globally routable public address space.
    """
    try:
        addr_info = socket.getaddrinfo(hostname, port, type=socket.SOCK_STREAM)
        if not addr_info:
            return False
        for res in addr_info:
            sockaddr = res[4]
            ip_str = sockaddr[0]
            if not is_safe_ip(ip_str):
                return False
        return True
    except (socket.gaierror, socket.herror, OSError) as exc:
        app.logger.warning("DNS resolution failed for %s: %s", hostname, exc)
        return False


def is_valid_googlevideo_url(url: str) -> bool:
    """
    Validates that an initial audio URL belongs to Google Video CDN.
    Strictly requires HTTPS, rejects IP literals and localhost.
    """
    try:
        parsed = urlparse(url)
        if parsed.scheme != "https":
            return False
        hostname = parsed.hostname
        if not hostname:
            return False
        try:
            ipaddress.ip_address(hostname.strip("[]"))
            return False  # Reject IP literals
        except ValueError:
            pass
        if hostname == "localhost" or hostname.startswith("127."):
            return False
        if hostname == "googlevideo.com" or hostname.endswith(".googlevideo.com"):
            return True
        return False
    except Exception:
        return False


def validate_audio_redirect_url(url: str) -> bool:
    """
    Validates an HTTP redirect target URL before it can be fetched by the proxy.
    
    Enforces:
      - Valid HTTPS scheme (prevents HTTPS -> HTTP downgrade)
      - Port must be 443 (or default HTTPS port)
      - Hostname must be present and not contain credentials/userinfo
      - Hostname must not be an IP literal (IPv4 or IPv6)
      - Hostname must not be 'localhost' or '127.*'
      - Hostname must end with '.googlevideo.com' or equal 'googlevideo.com'
      - DNS resolution must not resolve to loopback, private RFC 1918,
        link-local / cloud metadata (169.254.x.x), or reserved IP addresses.
    """
    try:
        parsed = urlparse(url)
        if parsed.scheme != "https":
            return False

        # Reject userinfo (e.g. https://user:pass@host)
        if parsed.username or parsed.password:
            return False

        # Port validation: only port 443 or None allowed
        if parsed.port is not None and parsed.port != 443:
            return False

        hostname = parsed.hostname
        if not hostname:
            return False

        hostname_lower = hostname.lower()

        # Reject IP literals (IPv4 or IPv6)
        try:
            ipaddress.ip_address(hostname_lower.strip("[]"))
            return False
        except ValueError:
            pass

        # Reject localhost or 127.*
        if hostname_lower == "localhost" or hostname_lower.startswith("127."):
            return False

        # Enforce googlevideo domain allowlist
        if not (hostname_lower == "googlevideo.com" or hostname_lower.endswith(".googlevideo.com")):
            return False

        if ".." in hostname_lower:
            return False

        # Verify resolved IP safety against private/loopback/cloud metadata
        if not is_safe_resolved_ip(hostname_lower, parsed.port or 443):
            return False

        return True
    except Exception:
        return False


def fetch_stream_with_safe_redirects(
    initial_url: str,
    headers: dict,
    timeout: int = 10,
    max_redirects: int = MAX_AUDIO_REDIRECTS
):
    """
    Executes an HTTP stream fetch with explicit, hop-by-hop redirect validation.
    
    Invariants enforced:
      1. Automatic redirect following is strictly disabled (allow_redirects=False).
      2. Hop count is capped at max_redirects (default 3).
      3. For every redirect hop (301, 302, 303, 307, 308):
         a. A Location header must be present.
         b. Target URL is resolved relative to the current URL.
         c. Target URL must pass validate_audio_redirect_url:
            - HTTPS only (no downgrade)
            - googlevideo.com or *.googlevideo.com
            - No IP literals, no localhost, no userinfo
            - Target port must be 443 (or default HTTPS)
            - Resolved IP must be safe (not private/loopback/link-local)
         d. Intermediate response socket is explicitly closed (r.close()) before following.
      4. If redirect validation fails, intermediate socket is closed and an error tuple is returned.
      5. The final successful response (e.g. 200, 206) is returned with open stream for generator consumption.

    Returns:
        tuple (response, error_tuple)
        If successful: (response, None)
        If error: (None, (error_message, status_code))
    """
    current_url = initial_url
    redirect_count = 0

    while True:
        try:
            r = requests.get(
                current_url,
                headers=headers,
                stream=True,
                timeout=timeout,
                allow_redirects=False
            )
        except requests.RequestException as e:
            app.logger.warning("Upstream audio request failed for %s: %s", current_url, e)
            return None, (f"Upstream request failed: {str(e)}", 502)

        if r.status_code in REDIRECT_STATUS_CODES:
            redirect_count += 1
            if redirect_count > max_redirects:
                app.logger.warning(
                    "Exceeded maximum audio redirect hops (%d) from %s",
                    max_redirects,
                    initial_url
                )
                r.close()
                return None, ("Forbidden: Too many redirects", 403)

            location = r.headers.get("Location")
            if not location:
                app.logger.warning("Redirect %d received without Location header", r.status_code)
                r.close()
                return None, ("Bad Gateway: Missing redirect location", 502)

            # Resolve relative Location against current URL
            target_url = urljoin(current_url, location)

            # Strictly validate redirect target
            if not validate_audio_redirect_url(target_url):
                app.logger.warning(
                    "Blocked unsafe redirect hop %d to %s",
                    redirect_count,
                    target_url
                )
                r.close()
                return None, ("Forbidden: Invalid redirect URL", 403)

            # Close intermediate response connection to prevent socket leak
            r.close()
            current_url = target_url
            continue

        return r, None


# ── Stream Proxy endpoint ───────────────────────────────────────────────────

@app.get("/stream-youtube-audio")
def stream_youtube_audio():
    """
    Proxies the audio stream from YouTube to bypass browser 403/CORS issues.
    Uses the exact HTTP headers that yt-dlp specified for the stream.
    Includes 403 Cache Recovery Logic and strict hop-by-hop redirect security validation.
    """
    audio_url = request.args.get("audio_url", "")
    video_id = request.args.get("video_id", "")
    
    if not audio_url:
        return "Missing audio_url", 400

    if not is_valid_googlevideo_url(audio_url):
        app.logger.warning(f"SSRF attempt blocked. Invalid audio_url: {audio_url}")
        return "Forbidden: Invalid audio URL", 403

    def get_proxy_headers(target_url):
        try:
            cached_headers = _stream_headers_cache.get(target_url, {})
        except Exception as cache_err:
            app.logger.warning("Failed to read from stream headers cache: %s", cache_err)
            cached_headers = {}
        headers = {}
        if cached_headers.get("User-Agent"):
            headers["User-Agent"] = cached_headers["User-Agent"]
        else:
            headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        
        range_header = request.headers.get("Range")
        if range_header:
            headers["Range"] = range_header
        return headers

    try:
        headers = get_proxy_headers(audio_url)
        r, err = fetch_stream_with_safe_redirects(audio_url, headers=headers, timeout=10)
        if err:
            return err[0], err[1]
        
        # Phase 2: 403 Recovery Logic
        if r.status_code == 403 and video_id:
            app.logger.warning(f"Cached URL returned 403. Attempting recovery for video_id: {video_id}")
            _cache_metrics["staleUrlRecoveries"] += 1
            
            # 1. Invalidate stale cache entry
            with _stream_url_cache._lock:
                _stream_url_cache._cache.pop(video_id, None)

            # 2–4. Re-extract, update headers cache, and rebuild stream-URL cache
            payload = _resolve_and_cache_stream(video_id)
            # Derive the new raw audio URL from the proxy URL in the payload
            from urllib.parse import urlparse as _up, parse_qs as _pqs, unquote as _uq
            _qs = _pqs(_up(payload["audio_url"]).query)
            new_raw_audio_url = _uq(_qs.get("audio_url", [""])[0])

            # 5. Close the stale 403 socket before retry to prevent connection leak
            r.close()

            # 6. Retry request (Max 1 attempt) using safe redirects
            audio_url = new_raw_audio_url
            if not is_valid_googlevideo_url(audio_url):
                app.logger.warning(f"SSRF attempt blocked in 403 recovery. Invalid audio_url: {audio_url}")
                return "Forbidden: Invalid audio URL", 403

            headers = get_proxy_headers(audio_url)
            r, err = fetch_stream_with_safe_redirects(audio_url, headers=headers, timeout=10)
            if err:
                return err[0], err[1]

        # Forward the Content-Type and Content-Length to the browser
        response_headers = {
            'Content-Type': r.headers.get('Content-Type', 'audio/webm'),
            'Accept-Ranges': 'bytes'
        }
        if 'Content-Length' in r.headers:
            response_headers['Content-Length'] = r.headers['Content-Length']
        if 'Content-Range' in r.headers:
            response_headers['Content-Range'] = r.headers['Content-Range']

        def generate():
            try:
                for chunk in r.iter_content(chunk_size=8192):
                    if chunk:
                        yield chunk
            finally:
                r.close()

        return Response(stream_with_context(generate()), headers=response_headers, status=r.status_code)
    except Exception as e:
        app.logger.exception("Proxy stream failed")
        return str(e), 500


# ── Debug endpoint ───────────────────────────────────────────────────────────

@app.get("/api/debug/stream-cache")
def debug_stream_cache():
    hits = _cache_metrics['cacheHits']
    misses = _cache_metrics['cacheMisses']
    total = hits + misses
    hitRate = (hits / total * 100) if total > 0 else 0
    extractions = _cache_metrics['ytDlpExtractions']
    avg = (_cache_metrics['totalExtractionTimeMs'] / extractions) if extractions > 0 else 0
    
    with _stream_url_cache._lock:
        size = len(_stream_url_cache._cache)

    return jsonify({
        "cacheHits": hits,
        "cacheMisses": misses,
        "hitRate": round(hitRate, 1),
        "ytDlpExtractions": extractions,
        "avgExtractionTimeMs": round(avg),
        "lastExtractionTimeMs": _cache_metrics['lastExtractionTimeMs'],
        "staleUrlRecoveries": _cache_metrics['staleUrlRecoveries'],
        "cacheSize": size
    })


# ── Search endpoint ──────────────────────────────────────────────────────────

@app.get("/search-youtube")
def search_youtube_endpoint():
    """
    Search YouTube videos using yt-dlp.
    """
    query = request.args.get("q", "").strip()
    limit = request.args.get("limit", 10, type=int)

    if not query:
        return jsonify({"error": "Missing required query param: q"}), 400

    try:
        results = search_youtube(query, max_results=limit)
        return jsonify({"items": results}), 200

    except Exception as exc:
        app.logger.exception("Unexpected error searching YouTube for: %s", query)
        return jsonify({"error": "Search failed.", "detail": str(exc)}), 500


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import os
    import gevent
    from gevent.pywsgi import WSGIServer
    
    port = int(os.environ.get('PORT', 5001))
    print(f"[INFO] YouTube Audio API (Production WSGI) running on http://127.0.0.1:{port}")
    print(f"       Test: http://127.0.0.1:{port}/get-youtube-audio?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ")
    
    http_server = WSGIServer(('127.0.0.1', port), app)

    def shutdown():
        print("Shutting down gracefully...")
        http_server.stop()
        
    gevent.signal_handler(gevent.signal.SIGTERM, shutdown)
    gevent.signal_handler(gevent.signal.SIGINT, shutdown)
    
    http_server.serve_forever()

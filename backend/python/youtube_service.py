"""
youtube_service.py
------------------
Pure helper module — no Flask, no HTTP.
Encapsulates all yt-dlp logic for extracting YouTube audio info.

Responsibilities:
  - Validate the URL is a proper YouTube link
  - Fetch video metadata (title, thumbnail)
  - Locate the best audio-only stream URL (no download)
"""

import yt_dlp
import gevent
import json
from gevent.event import AsyncResult
from gevent.lock import Semaphore

_in_flight_extractions = {}

# ─────────────────────────────────────────────────────────────────────────────
# Bounded, reusable YoutubeDL instance pool (Stage C-1A, Part A)
#
# yt-dlp is used as an in-process library. Previously a brand-new
# ``yt_dlp.YoutubeDL`` was constructed for EVERY extraction, re-paying per-instance
# setup each time — most importantly yt-dlp's lazily-built request director and
# its HTTP handlers / connection pool. yt-dlp owns its networking stack, so a
# hand-rolled ``requests.Session`` would NOT be reused by it; the correct unit of
# reuse is the YoutubeDL instance itself. Reusing an open instance across
# sequential extractions amortizes that setup and permits upstream connection reuse.
#
# A single shared instance is NOT safe here: the service runs under gevent and a
# YoutubeDL carries mutable per-extraction state, so two greenlets must never drive
# the same instance concurrently. We therefore keep a SMALL, bounded pool with
# checkout/checkin semantics (exactly one greenlet owns an instance at a time),
# keyed by a stable signature of ``ydl_opts`` (the module uses two distinct opts
# sets: audio extraction and flat search). When every pooled instance is busy and
# the cap is reached, we fall back to a transient instance that is closed after use
# — i.e. exactly the previous behaviour — so callers never block. A failed
# extraction DISCARDS its instance (it is closed and never returned to the pool),
# so an instance left in an edge state can never poison a later extraction.
#
# Memory is bounded by the hard cap below. Only yt-dlp process state is retained;
# no audio bytes and nothing on disk. All existing yt-dlp options, extraction
# semantics, retry behaviour, stream-URL parsing and expiry handling are preserved.
_YDL_POOL_MAX_INSTANCES = 4           # hard cap on retained reusable instances
_ydl_pool = {}                         # opts-signature -> list[idle YoutubeDL]
_ydl_live_count = 0                    # pooled instances alive (idle + checked-out)
_ydl_pool_lock = Semaphore()           # guards _ydl_pool and _ydl_live_count


def _opts_signature(ydl_opts):
    """Stable key for an options dict so identical configurations share instances."""
    try:
        return json.dumps(ydl_opts, sort_keys=True, default=str)
    except Exception:
        return repr(sorted(ydl_opts.items()))


def _acquire_ydl(ydl_opts):
    """Check out a reusable YoutubeDL for *ydl_opts*, or construct one.

    Returns ``(ydl, pooled)``. A ``pooled=False`` instance is transient (the cap
    was reached) and MUST be closed by the caller rather than returned to the pool.
    Construction happens outside the lock; a failed construction restores the count.
    """
    global _ydl_live_count
    sig = _opts_signature(ydl_opts)
    with _ydl_pool_lock:
        idle = _ydl_pool.get(sig)
        if idle:
            return idle.pop(), True
        pooled = _ydl_live_count < _YDL_POOL_MAX_INSTANCES
        if pooled:
            _ydl_live_count += 1
    try:
        ydl = yt_dlp.YoutubeDL(ydl_opts)
    except Exception:
        if pooled:
            with _ydl_pool_lock:
                _ydl_live_count -= 1
        raise
    ydl._sonara_sig = sig
    return ydl, pooled


def _release_ydl(ydl, pooled):
    """Return a healthy instance to the pool for reuse (or close a transient one)."""
    if not pooled:
        _close_ydl_quietly(ydl)
        return
    sig = getattr(ydl, "_sonara_sig", None)
    with _ydl_pool_lock:
        _ydl_pool.setdefault(sig, []).append(ydl)


def _discard_ydl(ydl, pooled):
    """Drop an instance after an error so it is never reused; keeps the pool clean."""
    global _ydl_live_count
    if pooled:
        with _ydl_pool_lock:
            _ydl_live_count -= 1
    _close_ydl_quietly(ydl)


def _close_ydl_quietly(ydl):
    """Best-effort close; never raises."""
    try:
        ydl.close()
    except Exception:
        pass


def _extract_with_retry(ydl_opts, url):
    """Executes yt_dlp extract_info with a 15-second timeout and up to 2 retries on DownloadError.

    Uses the bounded, reusable YoutubeDL pool above. The observable behaviour —
    the 15s timeout, retry/backoff on DownloadError, timeout/error wrapping into
    RuntimeError — is IDENTICAL to the previous per-call implementation; only the
    YoutubeDL instance lifecycle changed (checkout/checkin instead of construct/close
    per attempt). Any failing attempt discards its instance so it is never reused.
    """
    max_retries = 2
    backoff = 1.0

    for attempt in range(max_retries + 1):
        ydl, pooled = _acquire_ydl(ydl_opts)
        try:
            try:
                with gevent.Timeout(15):
                    info = ydl.extract_info(url, download=False)
            except gevent.Timeout:
                raise RuntimeError("yt-dlp extraction timed out after 15 seconds")
        except yt_dlp.utils.DownloadError as exc:
            _discard_ydl(ydl, pooled)
            if attempt < max_retries:
                gevent.sleep(backoff)
                backoff *= 2
                continue
            raise RuntimeError(f"Video is unavailable: {exc}") from exc
        except RuntimeError:
            _discard_ydl(ydl, pooled)
            raise
        except Exception as exc:
            _discard_ydl(ydl, pooled)
            raise RuntimeError(f"Failed to extract info: {exc}") from exc
        else:
            _release_ydl(ydl, pooled)
            return info



def get_audio_info(url: str) -> dict:
    """
    Given a YouTube video URL, return a dict with:
      - title     : video title
      - thumbnail : highest-resolution thumbnail URL
      - audio_url : direct URL to the best audio-only stream (no download)

    Raises:
      ValueError  – if the URL is invalid or not a YouTube URL
      RuntimeError – if the video is unavailable or has no audio streams
    """

    # ── 1. Basic URL sanity check ────────────────────────────────────────────
    if not url or not isinstance(url, str):
        raise ValueError("URL must be a non-empty string.")

    url = url.strip()
    if "youtube.com" not in url and "youtu.be" not in url:
        raise ValueError("URL does not appear to be a YouTube link.")

    # ── In-Flight Deduplication ──────────────────────────────────────────────
    if url in _in_flight_extractions:
        result_event = _in_flight_extractions[url]
        try:
            # Wait up to 30 seconds for the primary extraction to finish
            return result_event.get(timeout=30)
        except gevent.Timeout:
            raise RuntimeError("Timed out waiting for shared in-flight extraction to complete")

    result_event = AsyncResult()
    _in_flight_extractions[url] = result_event

    try:
        # ── 2. Extract info using yt-dlp ─────────────────────────────────────────
        ydl_opts = {
            "format": "bestaudio/best",
            "quiet": True,
            "no_warnings": True,
            "noplaylist": True,
            # Do NOT require login or cookies, use Android API bypass instead
            "extract_flat": False,
            "extractor_args": {
                "youtube": {
                    "player_client": ["android"],
                    "client": ["android"]
                }
            },
            # Use Node.js (already in the production container) for YouTube
            # player JS evaluation. yt-dlp-ejs must be installed.
            "js_runtimes": {"node": {}},
        }

        try:
            info = _extract_with_retry(ydl_opts, url)
        except Exception as exc:
            raise RuntimeError(f"Failed to extract audio: {exc}") from exc

        if not info:
            raise RuntimeError("yt-dlp returned no info for this video.")

        # ── 3. Extract metadata ──────────────────────────────────────────────────
        title = info.get("title") or "Unknown Title"

        video_id = info.get("id", "")
        thumbnail = (
            f"https://img.youtube.com/vi/{video_id}/maxresdefault.jpg"
            if video_id
            else info.get("thumbnail", "")
        )

        # ── 4. Get the audio stream URL ──────────────────────────────────────────
        audio_url = info.get("url")

        if not audio_url:
            # Sometimes yt-dlp returns the URL inside requested_formats
            formats = info.get("requested_formats") or []
            for fmt in formats:
                if fmt.get("acodec") and fmt["acodec"] != "none":
                    audio_url = fmt.get("url")
                    break

        if not audio_url:
            raise RuntimeError("No audio streams found for this video.")

        # ── 5. Get the HTTP headers yt-dlp recommends for this stream ──────────
        # YouTube validates the User-Agent against the client type used.
        # e.g. ANDROID_VR streams require a specific User-Agent.
        http_headers = info.get("http_headers") or {}

        result_dict = {
            "title": title,
            "thumbnail": thumbnail,
            "audio_url": audio_url,
            "http_headers": http_headers,
        }
        
        result_event.set(result_dict)
        return result_dict

    except Exception as exc:
        result_event.set_exception(exc)
        raise
    finally:
        _in_flight_extractions.pop(url, None)


def search_youtube(query: str, max_results: int = 10) -> list:
    """
    Search YouTube using yt-dlp and return a list of normalized result dicts.
    """
    if not query or not isinstance(query, str):
        raise ValueError("Query must be a non-empty string.")

    ydl_opts = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": True,       # Don't download, just get metadata
        "noplaylist": True,
    }

    try:
        search_url = f"ytsearch{max_results}:{query} music"
        info = _extract_with_retry(ydl_opts, search_url)

        entries = info.get("entries") or []

        results = []
        for entry in entries[:max_results]:
            video_id = entry.get("id", "")
            thumbnail = (
                f"https://img.youtube.com/vi/{video_id}/maxresdefault.jpg"
                if video_id
                else entry.get("thumbnail", "")
            )

            results.append({
                "videoId": video_id,
                "title": entry.get("title") or "Unknown Title",
                "channelTitle": entry.get("channel") or entry.get("uploader") or "Unknown Artist",
                "thumbnail": thumbnail,
                "duration": entry.get("duration") or 0,
            })

        return results
    except Exception as exc:
        raise RuntimeError(f"Search failed: {exc}") from exc

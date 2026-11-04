# sonara-backend

Independent Sonara Android backend — search, stream proxy, and media services.

## Architecture

```
Sonara Android
    ↓  HTTP (dev) / HTTPS (prod)
sonara-backend :3002
    ↓  Internal HTTP
Shared Python Services
    ├── YTMusic service :5000  (ytmusicapi / ytmusic_service.py)
    └── yt-dlp audio service :5001  (youtube_audio_api.py)
```

**The existing Sonara Web backend (:3001) is NOT required for Android.**
Both backends share the same Python infrastructure but are independently runnable.

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Health check |
| `GET` | `/api/v1/search?q={query}` | Ranked music search |
| `GET` | `/api/v1/stream/resolve?url={yt-url}` | Resolve a YouTube URL to a stream |
| `GET` | `/api/v1/stream/play?video_id=...&audio_url=...` | Proxy stream bytes to Android |
| `GET` | `/api/v1/videos/:videoId/status` | Read separate YouTube made-for-kids and embeddability statuses |

### Video status

`GET /api/v1/videos/:videoId/status` accepts an 11-character YouTube video ID and
uses the server-side `YOUTUBE_DATA_API_KEY` in one YouTube Data API `videos.list`
GET request with `part=id,status` and `id` equal to the requested video ID.
The Made-for-Kids guide explicitly specifies both parts; `status` includes both
`madeForKids` and `embeddable`, so no second request is needed.

A successful response has this shape (illustrative, not a live lookup):

```json
{
  "videoId": "dQw4w9WgXcQ",
  "madeForKidsStatus": "NOT_MADE_FOR_KIDS",
  "embeddabilityStatus": "EMBEDDABLE"
}
```

| Upstream field | `true` | `false` | Missing, null, or nonboolean |
|---|---|---|---|
| `status.madeForKids` | `MADE_FOR_KIDS` | `NOT_MADE_FOR_KIDS` | `UNKNOWN` |
| `status.embeddable` | `EMBEDDABLE` | `NOT_EMBEDDABLE` | `UNKNOWN` |

These results are independent. Missing `status` makes both results `UNKNOWN`.
Only a returned item whose `id` matches the request can supply either result;
malformed/missing item IDs or duplicate matching IDs produce a malformed-response
error rather than a fabricated verification result.

This endpoint reports metadata, not playback authorization. A future player must
not embed when either result is `UNKNOWN`, embeddability is `NOT_EMBEDDABLE`, or
verification fails (including quota exhaustion). `MADE_FOR_KIDS` additionally
requires the applicable tracking/data-collection safeguards before embedding.
Even `EMBEDDABLE` does not guarantee playback: the official reference notes that
platform policies or third-party claims can still block an embedded player.
This endpoint does not implement player origin/referrer handling or legal compliance.

Official references checked for this contract:
- `https://developers.google.com/youtube/data/guides/made_for_kids_status`
- `https://developers.google.com/youtube/v3/docs/videos/list`
- `https://developers.google.com/youtube/v3/docs/videos#status.embeddable`

The route returns `400` (`INVALID_VIDEO_ID`) for an invalid ID, `404`
(`YOUTUBE_VIDEO_NOT_FOUND`) when YouTube returns no matching items, and `503`
(`YOUTUBE_CONFIG_MISSING`) when the API key is empty. An upstream `403` whose
error reason is `quotaExceeded` returns `429` (`YOUTUBE_QUOTA_EXCEEDED`); a
generic upstream `429` returns `429` (`YOUTUBE_RATE_LIMITED`). Generic upstream
`403` and `5xx` responses return sanitized `502` (`YOUTUBE_UPSTREAM_FAILURE`).
Malformed JSON returns `502` (`YOUTUBE_MALFORMED_RESPONSE`), network failures
return `502` (`YOUTUBE_NETWORK_ERROR`), and timeouts return `504`
(`YOUTUBE_TIMEOUT`). An empty `YOUTUBE_DATA_API_KEY` must fail before calling
upstream. API credentials are never returned to Android or written to logs.

For local configuration, copy `.env.example` to `.env` and set
`YOUTUBE_DATA_API_KEY` only in that untracked file. The mocked backend tests cover
the route contract without internet access or real credentials. Live YouTube
verification has not been performed.

## Security Boundary

- All server-side credentials (API keys, provider secrets) remain server-side only.
- Android receives only sanitized DTOs and controlled `/api/v1/stream/play` endpoints.
- `/api/v1/stream/play` validates `video_id` and `audio_url` against a trusted domain allowlist.
- Arbitrary external URLs are rejected with HTTP 403.
- Internal Python service addresses are never sent to clients.

## Development Setup

### Prerequisites
- Node.js ≥ 20
- The shared Python services must be running:
  - `python youtube_audio_api.py` → Port 5001
  - `python ytmusic_service.py` → Port 5000

### Environment
```bash
cp .env.example .env
# Edit .env if needed — defaults work for local development
```

### Start
```bash
npm install
npm run dev     # local development (explicit development mode, rate-limiting bypassed)
npm start       # production start (fails closed to production, rate-limiting active)
```

The server starts on `PORT` from `.env` (default: 3002).

### Android Configuration

The debug Android build defaults to `http://127.0.0.1:3002`. For a USB-connected physical
device, forward that device-local address to the host Node gateway:

```powershell
adb -s <device-serial> reverse tcp:3002 tcp:3002
adb -s <device-serial> reverse --list
```

The reverse mapping must be re-established after the device disconnects. Do not point Android
at Python ports `5000` or `5001`; Node owns the public client boundary and calls Python over
its internal loopback.

For a LAN topology, keep source unchanged and provide an origin at build time:

```powershell
.\gradlew.bat :app:assembleDebug -PSONARA_DEBUG_BACKEND_URL=http://192.168.x.x:3002
```

The emulator alternative is `-PSONARA_DEBUG_BACKEND_URL=http://10.0.2.2:3002`.

## Tests

```bash
npm test               # all tests
npm run test:unit      # unit tests only
npm run test:security  # security/proxy & rate-limit validation tests only
```

## Production Deployment

```
Internet → HTTPS → Nginx Reverse Proxy → sonara-backend :3002
                                              ↓  (internal only)
                                     Python :5000 / :5001
```

- Python services MUST NOT be publicly exposed (bind to 127.0.0.1).
- TLS termination at Nginx.
- Manage process with PM2: `pm2 start src/index.js --name sonara-backend`.
  - By default, `sonara-backend` starts in `production` mode with all rate limiters enabled.
  - No manual environment configuration is required to achieve secure fail-closed operation.

## Docker / Railway Deployment

The backend runs as a single Docker container with three processes managed by `docker/start.sh`:

| Process | Binding | Port |
|---|---|---|
| Python YTMusic service | `127.0.0.1` (loopback only) | 5000 |
| Python yt-dlp audio service | `127.0.0.1` (loopback only) | 5001 |
| Node.js gateway | `0.0.0.0` (public) | `$PORT` |

**Railway configuration:**
- Set the Railway service root to `/backend` — Railway will detect `Dockerfile` automatically.
- `PORT` is injected by Railway at runtime. Do not hard-code it.
- `NODE_ENV=production` is set in the Dockerfile. Rate limiting is active.
- Python services remain loopback-only inside the container and are never publicly exposed.

**Local Docker build** (requires Docker Desktop running):
```bash
cd backend
docker build -t sonara-backend:local .
docker run --rm -p 3002:3002 -e PORT=3002 sonara-backend:local
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `3002` | Server port |
| `NODE_ENV` | `production` | Environment ('production' by default; pass `--dev` or set 'development' for local dev) |
| `PYTHON_YTMUSIC_URL` | `http://127.0.0.1:5000` | YTMusic service |
| `PYTHON_AUDIO_URL` | `http://127.0.0.1:5001` | yt-dlp audio service |
| `YTMUSIC_TIMEOUT_MS` | `8000` | Search timeout |
| `STREAM_RESOLVE_TIMEOUT_MS` | `30000` | Stream resolution timeout |
| `SEARCH_TIMEOUT_MS` | `12000` | Video search timeout (yt-dlp) |
| `YOUTUBE_DATA_API_TIMEOUT_MS` | `8000` | Bounded YouTube Data API status-request timeout |
| `SEARCH_RATE_LIMIT_MAX` | `60` | Max search requests/min |
| `STREAM_RESOLVE_RATE_LIMIT_MAX` | `20` | Max stream resolves/min |
| `GLOBAL_RATE_LIMIT_MAX` | `200` | Global max requests/min |

## Deferred (Future Phases)

- Authentication (Phase 4E)
- User library, likes, history (Phase 4E)
- MongoDB integration (Phase 4E)
- HTTPS/TLS (Phase 4F — Nginx handles this in prod)
- Lyrics backend proxy (Future)
- Artwork backend proxy (Future)

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

**Emulator:**
```
http://10.0.2.2:3002
```

**Physical device (update SonaraBackendConfig.kt):**
```
http://192.168.x.x:3002
```

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

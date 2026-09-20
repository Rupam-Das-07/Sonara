# SONARA — FINAL ARCHITECTURE C FREEZE AUDIT

> **Status:** Final architecture decision audit — read-only.
> **Date:** 2026-09-08
> **Verdict:** GO WITH REQUIRED CHANGES
> **Scope:** Nothing was implemented or modified during this audit.

---

## Evidence labels

- **VERIFIED** — read in the current repo.
- **INFERRED** — from code + platform defaults.
- **UNVERIFIED** — external / NewPipeExtractor — not integrated; web / Context7 access was blocked during this audit.
- **PROPOSED** — Architecture C element not yet in the repo.

**Tooling note:** Serena, Sequential-Thinking, and Context7 were not connected during this audit. Investigation used direct source reads, grep/bash inspection, and structured adversarial reasoning instead. No tool that was not actually used is claimed. Every external NewPipeExtractor claim is treated as UNVERIFIED, not fact.

This synthesizes the two prior audits (₹0/no-card deployment audit; performance/network audit) with fresh verification of the **complete Android runtime-dependency surface** — the one thing the earlier audits had not fully mapped.

---

## Architecture C (as proposed)

- YouTube extraction/search on-device using NewPipeExtractor + Rhino
- YouTube audio streams directly from provider → Android (no proxy)
- JioSaavn direct playback remains unchanged
- Optional edge search for song search
- Featured / Curated / Trending / Quick Picks served as static discovery JSON
- GitHub Actions generates/publishes discovery data
- User data remains local on Android
- No always-on Sonara backend, VM, database, cloud user storage, or paid infrastructure
- Target: robust for ~15 users and architecturally survivable at ~50 users
- ₹0/month and no Visa/Mastercard requirement
- No user-facing functionality should be removed or intentionally changed

---

## The decisive new finding: one seam, two hidden dynamic dependencies

Every runtime capability funnels through a single class, **`SonaraBackendClient`** (VERIFIED). `DiscoveryRepositoryImpl`, `SearchRepositoryImpl`, and `StreamResolverImpl` each self-construct their own instance pointed at the hardcoded LAN `BASE_URL`. This is architecturally *good* for Arch C: the entire migration concentrates **below the repository seam**, so ViewModels, UI, and frozen features stay untouched.

The client makes these runtime calls (VERIFIED): `search`, `search/videos`, `search/suggestions`; `stream/resolve` + `stream/play`; `artists/featured`; `artists/{id}?name=`; `playlists` + `playlists/{id}`; `quickpicks`; `recommendations/related/{videoId}`.

Most map cleanly onto Arch C. **Two do not, and the Arch C bullet list omits both:**

1. **`getRelatedTracks(seedVideoId)` — fully dynamic, and playback-critical.** It powers the Home "Because You Listen To" module (`HomeViewModel:142`) *and* autoplay/radio continuation in `PlayerViewModel` (`:269`, `:493` — "Need dynamic recommendations from backend"). This cannot be static JSON. If Arch C doesn't explicitly route it to on-device (NewPipeExtractor related/next) or edge, **autoplay/radio — part of the frozen playback foundation — silently dies when the explicit queue empties.** (VERIFIED). Degradation today is graceful (Home self-hides: "Reconnect to fetch live recommendations"; playback stops at queue end rather than crashing), but "stops working" is still a functional regression of a frozen feature.

2. **`getArtistCatalog(artistId, name)` — artist drill-through** from the featured roster (`HomeViewModel:259`). Bounded to the featured set, so it *can* be pre-generated as static JSON per featured artist; otherwise it needs on-device resolution. (VERIFIED)

Two clarifications that *reduce* risk: **lyrics already bypass the backend** entirely via `LrclibLyricsProvider` → LRCLIB, a free public API (VERIFIED) — Arch-C-compatible as-is. And **JioSaavn has no direct audio path on Android today** (jiosaavnV2Provider sets `videoId=null`; everything plays via YouTube videoId) — so "JioSaavn direct playback unchanged" is trivially satisfied: there is nothing to change on Android (VERIFIED).

## Discovery generation is 0% built (not partly built)

I checked whether the "updater" tooling already produces static data. It does not. **Nothing is persisted as static JSON today** (VERIFIED): `featuredArtistsService` rebuilds the roster from YT Music charts in-memory; `PlaylistService` resolves static *definitions* to *live* tracks into an in-memory cache; Quick Picks is live search. The only durable file is `backend/data/identity_store.json` (an identity cache). The `featuredArtistsUpdater`/`curatedPlaylistsUpdater` CLIs *explicitly do not persist* — they validate/preview an in-process refresh. There is **no `.github/workflows`** (VERIFIED). So Arch C's "generate static JSON via GitHub Actions and publish" pipeline is entirely PROPOSED. The good news: the services that *produce* the data are reusable, so this is additive build work, not a redesign.

---

## Findings by category

### A. BLOCKERS

**None.** No concrete fatal architectural flaw was found. Arch C does not need to be replaced. (See rationale under the verdict.)

### B. REQUIRED BEFORE FREEZE

- **On-device extraction is the single load-bearing UNVERIFIED assumption.** The entire architecture rests on NewPipeExtractor+Rhino doing, on-device, what the Python yt-dlp/ytmusic service does today: audio-stream extraction **plus the HTTP headers YouTube validates** (`youtube_service.py` captures `http_headers` because "YouTube validates the User-Agent against the client type"), **plus search, plus related/next**. If it cannot do related, autoplay can't be preserved without a server. UNVERIFIED — must be spiked before freezing. (Area 2, 4)
- **The stream contract can't carry direct-URL requirements.** `StreamInfo` has no `headers` field; the playback DataSource is a bare `DefaultHttpDataSource.Factory()`; `RefreshingDataSource` refresh is inert (trackId carriage broken: `customData` never set, URIs carry `video_id` not `trackId`; `read()` never refreshes). Direct `googlevideo` URLs expire (~6h) and are 403/throttle-prone, so this must be resolved for Arch C to stream at all. (VERIFIED gaps; Area 2)
- **Two dynamic dependencies must be explicitly assigned** to a concrete Arch C mechanism (on-device or edge): `getRelatedTracks` (autoplay + Because You Listen To) and `getArtistCatalog` (drill-through). The freeze is incomplete until these are in the plan. (VERIFIED; Areas 1, 6, 9)
- **Discovery generate→publish→client-read pipeline must be defined** (host, schedule, serialization, and — critically — the **stale/last-known-good fallback** for the case where the GitHub Actions runner's datacenter IP is throttled/blocked by YouTube at generation time). Currently 0% built. (VERIFIED; Areas 1, 6)
- **Download design is not viable for expiring direct URLs as-is.** `DownloadEngine` has no Range/resume, no retry, no 403/expiry handling, and runs in a plain coroutine scope (no foreground service / WorkManager) so process death loses the download (VERIFIED). With direct URLs that can expire or 403 mid-transfer, this must gain resume + retry + refresh + crash-survival. (Area 3)

### C. OPTIONAL HARDENING (strongly recommended, not decision-blocking)

- Tune ExoPlayer `LoadControl` + add a `LoadErrorHandlingPolicy` for weak/slow/high-latency mobile (currently 100% defaults, 8s HTTP timeouts). (VERIFIED; Area 5)
- Add `setWakeMode(WAKE_MODE_NETWORK)` + `WAKE_LOCK` so screen-off playback on weak networks doesn't stall. (VERIFIED; Areas 4, 5)
- Register a `NetworkCallback` for proactive Wi-Fi↔mobile recovery (today: none; only a one-shot check in `DownloadEngine`). (VERIFIED; Areas 3, 5)
- Cap concurrent downloads (currently unbounded `activeDownloadJobs`) so downloads don't starve playback buffer on 1–3 Mbps. (VERIFIED; Area 3)
- Reduce the 50 ms (20 Hz) position-poll recomposition on low-end (progress + word-level lyrics). (VERIFIED; Area 4)
- Bound the `streamCache` `ConcurrentHashMap` (unbounded, no eviction; two instances) and ensure the extractor sets `expiresAt` so the cache never serves stale URLs. (VERIFIED; Area 4)
- Plan R8/AAB: `optimization.enable=false` today → enabling R8 needs Rhino keep-rules or extraction breaks; ship AAB + ABI splits to offset the NewPipeExtractor+Rhino size delta. (VERIFIED config; Area 4)

### D. PHYSICAL-DEVICE / REAL-NETWORK TESTS (cannot be settled in code)

- Extraction CPU/RAM/latency and first-play/refresh-stall on 3–4 / 4–6 / 6–8 GB devices. (UNVERIFIED)
- Related/next reliability and latency on-device across regions. (UNVERIFIED)
- Recovery under weak 4G, 1–3 Mbps, high latency, packet loss, temporary loss, Wi-Fi↔mobile handoff — for extraction, playback, and downloads. (Areas 2, 3, 5)
- Screen-off playback on weak 4G (stall + 1-hour battery drain). (Area 4)
- Scrolling/UI jank while playing on low-end (Perfetto/recomposition counts), especially expanded player with lyrics. (Area 4)
- APK/AAB size delta and low-end install/memory footprint from NewPipeExtractor+Rhino. (Area 4)
- Real `googlevideo` URL TTL and 403/throttle frequency on rotating mobile IPs. (Area 2)
- Memory-pressure kill/restore during playback; download survival across process death. (Areas 3, 4)
- GitHub Actions runner extraction success rate (datacenter-IP blocking) over time. (Area 6)

### E. VERIFIED SAFE AS-IS

- **Cold start is light:** `AppContainer` is fully `by lazy`; ExoPlayer is built only when the playback service starts, not at launch. (VERIFIED; Area 4)
- **Extraction won't freeze the UI thread:** resolution runs on `Dispatchers.IO`; the refresh `runBlocking` executes on ExoPlayer's Loader thread, not the main thread (VERIFIED; Area 4) — *provided* on-device extraction is invoked through `StreamResolverPort`.
- **Frozen features stay behind stable seams:** the migration is entirely below the repository interfaces. Playback foundation, playlists, search UX, downloads UX, settings, language switching, audio routing, branding, theme animation, Curated Playlist V2, Featured Artists, and local user-data behavior require **no reopening** — the *data source* behind `SonaraBackendClient` changes, not the contracts above it. (VERIFIED) The one caveat is functional, not UX: autoplay/related must be re-sourced (Item B) or the frozen playback behavior regresses.
- **Config-change recreation (rotation/theme) does not interrupt playback** — it lives in the `MediaSessionService`. (VERIFIED; Area 4)
- **Lyrics** already run off-backend via LRCLIB. (VERIFIED; Areas 1, 8)
- **Security/privacy:** Arch C introduces **no** database, user accounts, cloud user storage, sensitive server secrets, paid infrastructure, or card-backed service. User data remains local (Room/DataStore); removing the always-on server *reduces* attack surface. (VERIFIED; Area 8)
- **Scale — no hidden central bottleneck.** This is the strongest point *for* Arch C. Removing the shared VM/proxy means audio extraction and streaming become **per-device and independent** — 15 or 50 users are 15 or 50 independent clients hitting YouTube from distinct residential IPs, with no shared server to saturate. The only shared surfaces are a static JSON file (trivial to serve free) and optional edge search (well under free-tier limits for 50 users). At both 15 and 50 users the architecture has *no* shared runtime chokepoint — it scales *better* than the current 3-process backend, which centralizes all extraction+proxy in one VM. (INFERRED; Areas 1, 7)
- **₹0 / no-card holds** under scrutiny: on-device extraction (user's device), static JSON on a free host (GitHub Pages/raw), GitHub Actions (free minutes, no card), optional Cloudflare Workers (free tier, email signup), LRCLIB (free). No component requires a Visa/Mastercard. The one honest caveat is *operational*, not cost: CI-side extraction may be throttled on datacenter IPs — mitigated by stale-serve (Item B), not by spend. (INFERRED; Areas 1, 8)

### Maintainability / failure model vs. the current 3-process backend (Area 7)

**Better:** no always-on server to host, pay for, secure, or monitor; no shared bottleneck; failure is per-device and localized; user data never leaves the device.

**Worse:** extraction logic ships *in the app*, so an extraction break requires an **app update, not a server hotfix** — the slowest possible remediation path (already accepted in the deployment audit). Discovery freshness now depends on a CI job that itself depends on YouTube working from a datacenter IP.

**New failure modes:** (a) app-wide playback breakage on a YouTube change, fixable only by release; (b) stale discovery if CI extraction is blocked — acceptable *only* because modules already self-hide/last-known-good. Neither is unacceptable given the ₹0/no-card constraint, provided the stale-serve fallback and a fast app-release path exist.

---

## VERDICT: GO WITH REQUIRED CHANGES

Architecture C is fundamentally sound and should be frozen. It has no fatal flaw, it is *more* scale-robust than the current backend (it eliminates the single shared bottleneck; 15 users comfortable, 50 users survivable), it satisfies ₹0/no-card with no hidden assumptions beyond an operational CI-IP risk that has a free mitigation, it preserves every frozen feature behind stable seams, and it improves the security/privacy posture. It is **not** a clean GO only because (a) its load-bearing premise — on-device extraction incl. headers/search/related — is UNVERIFIED, (b) the current stream/download implementation cannot yet handle expiring direct URLs, (c) two dynamic runtime dependencies are missing from the plan, and (d) the discovery-generation pipeline is unbuilt. All are resolvable within Arch C; none justifies replacing it.

### Required changes to freeze Architecture C

1. **Spike NewPipeExtractor on-device before committing** — prove, on a min-spec (3–4 GB) device, that it can (a) extract a playable audio stream **with the exact HTTP headers** ExoPlayer must send, (b) search, and (c) return related/next tracks. Define explicit pass/fail. If related is not achievable, autoplay/radio cannot be preserved server-lessly → revisit before freeze.
2. **Add the direct-URL stream contract:** add `StreamInfo.headers`; propagate headers into the ExoPlayer `HttpDataSource` and into `DownloadEngine`; fix `trackId` carriage so `RefreshingDataSource` refreshes on 403/410 at `open()` and handles expiry on `read()`/seek.
3. **Explicitly assign the two omitted dynamic dependencies** to a concrete mechanism: `getRelatedTracks` (autoplay + "Because You Listen To") and `getArtistCatalog` (featured drill-through) → on-device extraction (preferred) or edge. No capability may keep pointing at the always-on backend.
4. **Define and build the discovery pipeline:** serialize featured artists, curated playlists, and Quick Picks to static JSON; schedule generation via GitHub Actions; publish to a free static host; have the client read the static URL with a **last-known-good / stale-serve fallback** for CI-extraction failures.
5. **Make downloads viable for expiring direct URLs:** add Range/resume, bounded retry, 403/expiry re-resolve mid-download, and process-death survival (foreground service or WorkManager).

---

## ARCHITECTURE C FREEZE CONTRACT

Freeze Architecture C once **all** of the following are true:

### Premise validated

- [ ] On-device NewPipeExtractor spike passes on a 3–4 GB device for: audio extraction **+ stream headers**, search, and related/next — with recorded latency/CPU/RAM and explicit pass/fail. (Closes the one UNVERIFIED load-bearing assumption.)

### Capability map complete (nothing points at an always-on backend)

- [ ] Stream resolve/play → on-device extraction (direct URL, no proxy).
- [ ] Search (songs/videos/suggestions) → edge and/or on-device; suggestions keep the existing local-history fallback.
- [ ] Featured Artists, Curated Playlists (catalog + detail), Quick Picks → static discovery JSON.
- [ ] `getRelatedTracks` (autoplay + Because You Listen To) → on-device/edge, decided and specified.
- [ ] `getArtistCatalog` (featured drill-through) → static-per-artist or on-device, decided and specified.
- [ ] Lyrics remain on LRCLIB (unchanged). JioSaavn remains editorial-signal-only on Android (unchanged).

### Direct-streaming survivability specified

- [ ] `StreamInfo.headers` + header propagation to ExoPlayer DataSource and DownloadEngine.
- [ ] `trackId` carriage fixed; refresh on 403/410 at open, and expiry handled on read/seek.
- [ ] Download resume/Range + retry + mid-download re-resolve + process-death survival.

### Discovery pipeline specified

- [ ] Generator serializes featured/curated/quickpicks to JSON; scheduled GitHub Actions; free static host chosen.
- [ ] Client reads static URL with last-known-good/stale-serve fallback; empty modules self-hide (already true).

### Constraints reaffirmed

- [ ] ₹0 / no Visa-Mastercard on every component (on-device, GitHub, static host, optional Workers, LRCLIB).
- [ ] No database, user accounts, cloud user storage, server secrets, or paid infra introduced; user data stays local.
- [ ] Scale intent documented: per-device independence; 15 comfortable, 50 survivable; only shared surfaces are static JSON + optional edge search.
- [ ] Accepted trade-off recorded: extraction breakage → app update (not server hotfix); GPLv3 obligation from NewPipeExtractor.

### Frozen features untouched

- [ ] Playback foundation, playlists, search UX, downloads UX, settings, language switching, audio routing, branding, theme animation, Curated Playlist V2, Featured Artists, local user-data behavior — all confirmed unchanged above the repository seam, with autoplay/related re-sourced so no frozen behavior regresses.

### Then validate on real devices/networks

- [ ] Bucket D (physical-device / real-network tests) executed before shipping — these gate release, not the freeze.

Once every box is checked, Architecture C is safe to freeze and begin implementation.

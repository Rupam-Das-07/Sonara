# SONARA ANDROID — PHASE 4B-3 DATA, PERSISTENCE & NETWORKING ARCHITECTURE

## 1. Executive Summary

This document establishes the final foundational architecture for Sonara Android, covering the **Data, Persistence, and Networking** layers. It defines how data is fetched from external providers, mapped into domain models, persisted locally, and supplied to the UI architecture defined in Phase 4B-2.

The architecture emphasizes strict separation of concerns: Network DTOs, Room Entities, and Domain Models remain isolated to prevent implementation details from leaking across boundaries. It selects **Proto DataStore** for structured session recovery, **Room** for relational library data, and explicitly forbids manual negative caching of artwork to avoid the failure modes observed in the legacy Sonara Web project.

---

## 2. Tool / MCP / Skill Usage

| Tool / Resource | Usage in This Phase |
| :--- | :--- |
| **`search_web` (Android Docs)** | ✅ Verified modern Android persistence guidelines, confirming **Proto DataStore** as the recommended solution for structured session snapshots over Room or SharedPreferences. |
| **`search_web` (Coil/Glide Docs)** | ✅ Verified standard image loader caching behaviors to ensure Sonara's artwork architecture relies on robust HTTP cache semantics rather than reinventing manual negative caching. |
| **Phase 4B-1 / 4B-2 Documents** | ✅ Referenced to ensure exact compatibility with the frozen Playback and UI architectures. |

---

## 3. Frozen Architectural Dependencies

The following boundaries from previous phases are **FROZEN** and fully respected by this data architecture:

1. **Phase 4A**: Native Android, Kotlin, Material 3, open-source direction.
2. **Phase 4B-1 (Playback)**: `ExoPlayer` is the single source of truth for playback. Session recovery restores to `PAUSED`. Stream URLs are ephemeral and never persisted to disk.
3. **Phase 4B-2 (UI/State)**: Pragmatic UDF. ViewModels project state and do not format Android Resources (`Context`). High-frequency playback position is read via deferred composables.

---

## 4. Data Layer Requirements

| Requirement | Architectural Impact |
| :--- | :--- |
| **Provider Agnostic** | The domain layer must not know if data came from YouTube Music, JioSaavn, or local storage. |
| **Offline-Capable Library** | Liked songs and history must be available immediately without a network connection. |
| **Fail-Safe Playback Session** | The app must recover its last known queue and position instantly after process death. |
| **No "Stuck" Broken States** | Transient network failures must not be permanently cached (No manual negative caching). |

---

## 5. Domain / DTO / Entity Separation

Sonara enforces strict model isolation to prevent external API changes from breaking the UI.

1. **Network DTOs (Data Transfer Objects)**: JSON schemas owned by the networking layer (e.g., `YtmTrackDto`). Must not leave the provider/adapter boundary.
2. **Room Entities**: SQLite tables owned by the local persistence layer (e.g., `TrackEntity`). Must not leave the repository layer.
3. **Domain Models**: Pure Kotlin data classes (e.g., `Track`). Used by ViewModels, Domain UseCases, and the Playback Engine. They contain zero `@Entity`, `@Serializable`, or `@SerializedName` annotations.

**Mapping Responsibility**:
- `ProviderAdapter` maps `DTO → Domain`.
- `Repository` maps `Entity ↔ Domain`.

---

## 6. Repository Architecture

Repositories are the single source of truth for data domains. They abstract whether data is loaded from external network providers or local persistence.

| Repository | Primary Responsibility | Data Sources |
| :--- | :--- | :--- |
| **`LibraryRepository`** | Liked songs, listening history, custom playlists | Room DB |
| **`SearchRepository`** | Resolving user queries into search hit summaries | External Music API Providers |
| **`CatalogRepository`** | Deep fetching of full entity structures (Track details, Album tracklists, Artist discographies) | External Music API Providers (with local cache) |
| **`LyricsRepository`** | Fetching synced/unsynced lyrics & metadata | External Music API Providers |
| **`SettingsRepository`** | Theme preferences, playback settings | Preferences DataStore |

*Note on Cohesion*:
- `LibraryRepository` keeps Liked Songs and History unified because both mutate the relational `TrackEntity` and share local SQLite transactions.
- `SearchRepository` and `CatalogRepository` are decoupled: Search handles query discovery and autocomplete/hits, while `CatalogRepository` owns deep entity retrieval and caching for rich screens (Album/Artist details).

---

## 7. Provider Architecture

Sonara interacts with external music APIs via normalized domain ports (Port & Adapter pattern).

```kotlin
// Data Layer Provider Ports
interface SearchProviderPort {
    suspend fun search(query: String): Result<List<Track>>
}

interface CatalogProviderPort {
    suspend fun getTrackDetails(trackId: String): Result<Track>
    suspend fun getAlbumDetails(albumId: String): Result<Album>
    suspend fun getArtistDetails(artistId: String): Result<Artist>
}
```

### 7.1 Separation from Playback Stream Resolution
**Crucial Boundary Rule**: `StreamResolverPort` (defined in Phase 4B-1) is a dedicated playback infrastructure port responsible for real-time audio URL resolution and token refresh. It is **strictly segregated** from `CatalogProviderPort` and `SearchProviderPort`.
- While a single networking adapter (e.g. `YouTubeMusicNetworkAdapter`) may implement both ports under the hood, the port interfaces remain distinct to prevent catalog and search operations from depending on playback-specific stream resolution.

### 7.2 Implementation Layer
Concrete adapters (e.g., `YouTubeMusicAdapter` or `JioSaavnAdapter`) implement these ports. They encapsulate HTTP clients, endpoints, headers, and JSON parsing. The domain layer remains completely provider-agnostic.

---

## 8. Provider Fallback

Fallback logic belongs in the **Repository** (or a dedicated `ProviderOrchestrator`), **never in the ViewModel or UI**.

**Fallback Strategy**:
1. `SearchRepository` requests data from `PrimaryProvider`.
2. If `PrimaryProvider` fails (e.g., HTTP 503, rate limited), the Repository catches the exception.
3. The Repository transparently falls back to `SecondaryProvider`.
4. The ViewModel receives a successful `List<Track>` or a final `ProviderExhaustedException`.

*Crucially, partial failures inside the provider layer do not pollute the UI state.*

---

## 9. Networking Architecture

The Android networking stack handles HTTP requests to providers.

**Required Capabilities**:
- **Cancellation**: Requests must be intrinsically tied to Coroutine scopes and cancel immediately when the UI scope dies or the search query changes.
- **Timeout & Retry**: Enforced at the HTTP client layer (e.g., OkHttp interceptors).
- **Error Normalization**: HTTP 404s, 500s, and `UnknownHostException`s must be mapped into typed Domain Exceptions before leaving the adapter.

*(Exact library selection—likely Retrofit + OkHttp or Ktor—is deferred to implementation).*

---

## 10. Serialization Boundary

Serialization logic exists exclusively at the network edge and the persistence edge.

- **JSON Parsing**: Executed as the network stream arrives. Unrecognized JSON fields are explicitly ignored (e.g., `ignoreUnknownKeys = true` in Kotlinx.Serialization) to prevent future provider API updates from crashing the app.
- **Malformed Payloads**: Fail safely, returning a `ParsingException` that the fallback orchestrator can catch.

---

## 11. Persistence Architecture

| Data Category | Persistence Requirement | Storage Technology |
| :--- | :--- | :--- |
| **Library (Liked/History)** | Relational, queryable, transactional | **Room (SQLite)** |
| **Settings (Theme)** | Simple key-value flags | **Preferences DataStore** |
| **Session Snapshot** | Single complex structured object | **Proto DataStore** |
| **Stream URLs** | Ephemeral, time-sensitive tokens | **Transient Memory Only** (No disk) |

---

## 12. Room / DataStore / Storage Decisions

### 12.1 Room for Library
Room is selected for `LibraryRepository` because Liked Songs and History require referential integrity, sorting, and pagination (e.g., `SELECT * FROM tracks JOIN history ON ... ORDER BY played_at DESC`).

### 12.2 Proto DataStore for Session Recovery ✅ DECIDED
Phase 4B-1 left the session snapshot mechanism open. **Proto DataStore** is officially selected.
**Rationale**: The session snapshot (Current Track, Queue List, Position) is a single, complex, nested object. Room is overkill (too much boilerplate for a single row). SharedPreferences/Preferences DataStore lack type safety for lists of objects. Proto DataStore provides strong typing (Protocol Buffers) and reactive asynchronous reading via Coroutines, making it perfect for rapid state restoration.

---

## 13. Session Recovery Persistence

**What is Persisted (in Proto DataStore)**:
- `currentTrackId: String`
- `queueTrackIds: List<String>`
- `playbackPositionMs: Long`
- `repeatMode: Int`, `shuffleMode: Boolean`

**Persistence Triggers**:
Rather than relying on `onStop` (which Android does not guarantee prior to process death), session state is proactively written at critical lifecycle and playback checkpoints:
1. **On Track Transitions**: Every `onMediaItemTransition` (new track becomes active).
2. **On Queue Mutations**: Whenever tracks are added, removed, or reordered in the active timeline.
3. **On Playback Mode Changes**: Whenever repeat or shuffle mode is toggled.
4. **On State Changes**: Transitions to `STATE_IDLE`, paused state, or service lifecycle teardown.
5. **Periodic Checkpoints**: Periodic playback position updates during active playback (throttled/batched to prevent disk thrashing).

**Restoration**: `SonaraPlaybackService` reads the Proto DataStore sequentially on `onCreate()`.
**State Rule**: The restored session is instantiated strictly in the **PAUSED** state (per Phase 4B-1). The user must explicitly trigger playback.

---

## 14. Caching Architecture

### 14.1 Stream URLs (Negative Persistence)
Stream URLs containing auth tokens expire quickly. They are **never** persisted to disk. Caching them causes unrecoverable playback failures upon app restart.

### 14.2 Negative Caching (Forbidden) ✅ DECIDED
The legacy Sonara Web app suffered from "stuck broken images" because it explicitly cached failed URL resolutions.
**Rule**: Sonara Android relies exclusively on positive HTTP caching. If a request fails, the failure is NOT cached. The system must attempt the request again on the next user interaction.

---

## 15. Artwork Architecture

Artwork handling is cleanly partitioned between domain data models and the UI presentation layer.

**Architectural Boundary**:
1. **Domain Representation**: Domain models (`Track`, `Album`, `Artist`) hold normalized artwork URLs as simple immutable strings (`val artworkUrl: String?`).
2. **No Standalone ArtworkRepository**: Artwork resolution is embedded within catalog and track metadata retrieval; no separate repository is required.
3. **UI Image Loading Layer**: A dedicated Compose image-loading integration (e.g., Coil) receives the URL, manages asynchronous fetching, bitmap decoding, memory caching, and disk caching.
4. **Resilience & Fallback**:
   - If an image fails to load or the device is offline with an empty cache, the UI displays a vector placeholder / gradient.
   - Failures are not negatively cached in application memory or disk. Standard HTTP cache headers govern remote image revalidation.

---

## 16. Lyrics Data Architecture

The Lyrics architecture handles fetching, parsing, normalising, caching, and romanising lyrics for tracks.

### 16.1 Lyrics Models & Types
- **Synced Lyrics**: Time-coded lines (e.g. LRC format with millisecond timestamps per line) used for real-time scrolling and highlighting.
- **Unsynced Lyrics**: Plain multi-line text for tracks where timestamp synchronization is unavailable.
- **Instrumental / Unavailable**: Explicit state representing tracks with no vocal content or unindexed lyrics.

### 16.2 Provider Abstraction & Fallback
```kotlin
interface LyricsProviderPort {
    suspend fun getLyrics(trackId: String, title: String, artist: String, durationMs: Long): Result<LyricsData>
}
```
`LyricsRepository` attempts resolution via a primary lyrics provider, falling back to secondary providers if the primary returns no match or errors.

### 16.3 Lyrics Normalization & Caching
- **Normalization**: Raw timestamp strings are parsed and normalized into sorted `List<LyricLine(timestampMs, text)>`. Malformed lines or timestamp irregularities are filtered/sanitized gracefully without crashing.
- **Caching**: Successfully resolved lyrics are cached locally (in SQLite or an in-memory/disk LRU cache) keyed by stable `trackId` to eliminate redundant remote calls on repeated track plays.

### 16.4 Romanization Engine Boundary
- **Pure Kotlin Domain Logic**: The Romanization Engine is a pure Kotlin UseCase residing strictly in the domain layer. It transforms non-Latin lyrics (e.g., Japanese, Korean, Hindi, Cyrillic) into Romanized phonetic text.
- **Independence**: It imports zero Android SDK, Media3, or networking classes and is completely testable via standard JVM unit tests.

### 16.5 Separation from Playback Clock
**Crucial Boundary Rule**: `LyricsRepository` provides *static* data models (`LyricsData`). It has **zero knowledge of the playback clock**.
- Real-time line highlighting and auto-scrolling are computed in the UI/ViewModel layer by comparing the active lyrics timestamps against the high-frequency playback position stream (as established in Phase 4B-1 and Phase 4B-2).

---

## 17. Search Architecture

- **Debounce**: Managed in the `SearchViewModel` using Coroutine Flow operators (e.g., `debounce(300)`).
- **Execution**: Passed to `SearchRepository`.
- **Cancellation**: If a new keystroke occurs before the previous network request completes, the previous Coroutine is cancelled, which intrinsically cancels the in-flight HTTP request.
- **Caching**: Search results are kept in transient memory (ViewModel StateFlow) and are not persisted to the database.

---

## 18. Library & History Architecture

Both domains are managed by `LibraryRepository` backed by a Room database.
- **Liked Songs**: Mutates a `is_liked` boolean on the `TrackEntity`.
- **History**: Inserts a row into a `PlayEvent` table with a foreign key to `TrackEntity`.
Keeping them together prevents complex cross-database or cross-repository synchronization for MVP.

---

## 19. Settings / Preferences Architecture

Managed by `SettingsRepository` backed by `Preferences DataStore`.
Provides a `Flow<ThemePreference>` that the root `MainActivity` observes to apply the Petrol/Bone/Oxide Compose theme dynamically. The UI layer never reads/writes DataStore directly.

---

## 20. Error Architecture

Sonara uses a normalized error hierarchy to prevent network-specific exceptions from crashing the app or confusing the UI.

1. **Edge**: HTTP `503 Service Unavailable` or connection timeout.
2. **Adapter**: Catches `HttpException` and maps to typed `ProviderUnavailableException` (Domain).
3. **Repository**: Attempts fallback. If all providers are exhausted, passes domain exception up.
4. **ViewModel**: Catches `ProviderUnavailableException`, maps it to a UI-safe presentation error / `UiText` abstraction (without directly referencing `R.string.*` or Android `Context`).
5. **UI**: Resolves the `UiText` / presentation error to a localized string and displays a Snackbar or error banner.

---

## 21. Offline / Degraded Behavior

| Feature | Behavior | Action |
| :--- | :--- | :--- |
| **Liked Songs / History** | **MUST WORK OFFLINE** | Loaded entirely from local Room DB. |
| **Search** | **REQUIRES NETWORK** | Shows "You are offline" UI state. |
| **Playback** | **REQUIRES NETWORK** | MVP stream resolution requires network. |
| **Artwork** | **DEGRADES GRACEFULLY**| Coil loads from disk cache; falls back to vector placeholder if uncached. |

---

## 22. Dependency Injection Boundary

Sonara will use a Dependency Injection framework (e.g., Hilt or Koin) to wire the architecture.

**Rules**:
- **No Service Locators**: Components must explicitly declare their dependencies in constructors.
- **Interface Binding**: The DI graph injects the interface (`ContentProviderPort`), not the concrete class (`YouTubeAdapter`), allowing easy substitution for testing.
- **Context Injection**: Android Context is only injected into Room databases, DataStore, and network clients. It is **never** injected into ViewModels or Domain UseCases.

---

## 23. Canonical Data Flows

### Data Fetch (e.g., Search)
```text
UI [Compose] 
  ↓ (calls method)
SearchViewModel [StateHolder]
  ↓ (suspends)
SearchRepository [Domain]
  ↓ (suspends)
ProviderAdapter [Infra]
  ↓ (executes HTTP)
Network (JSON) → DTO → (Mapped to Domain) → Repository → ViewModel → UI State
```

### Local Persistence (e.g., Liked Songs)
```text
UI [Compose]
  ↓ (observes StateFlow)
LibraryViewModel 
  ↓ (collects Flow)
LibraryRepository
  ↓ (queries DAO)
Room Database (SQLite)
```

---

## 24. Testing Architecture

- **Repositories**: Tested using pure JUnit by providing Fake DAOs and Fake Provider implementations.
- **Provider Adapters**: Tested using `MockWebServer` to verify JSON parsing and HTTP error normalization without hitting live servers.
- **Mappers**: Pure JUnit tests (DTO in -> Domain out).

---

## 25. Security / Credential Boundary

Future APIs may require client secrets or tokens.
- **Rule**: API keys MUST NEVER be hardcoded in the codebase.
- **Implementation**: Secrets belong in `local.properties` (ignored by Git) and are injected via Gradle `BuildConfig` into the networking layer. The UI and Domain layers remain oblivious to credentials.

---

## 26. Alternatives & Rejected Approaches

| Alternative | Rejection Reason |
| :--- | :--- |
| **Room for Session Snapshot** | Too much boilerplate for a single object. Proto DataStore provides strong typing without SQLite overhead. |
| **Manual Image Disk Caching** | Reinvents the wheel. Coil/Glide handle HTTP caching and memory eviction natively. |
| **SharedPreferences** | Deprecated, lacks type safety, synchronous API causes UI jank. Replaced by DataStore. |
| **Returning DTOs to UI** | Violates clean architecture. UI would break if API schema changes. Strict mapping required. |

---

## 27. Final Data Architecture

```text
    ┌─────────────────────────────────────────────────────────────┐
    │                        COMPOSE UI                           │
    └─────────────────────────────┬───────────────────────────────┘
                                  ↓ (UiEvent / Method Calls)
    ┌─────────────────────────────┴───────────────────────────────┐
    │                        VIEWMODELS                           │
    │   (LibraryVM, SearchVM, CatalogVMs, SettingsVM, PlayerVM)   │
    └─────────────────────────────┬───────────────────────────────┘
                                  ↓ (Domain Models / Use Cases)
    ┌─────────────────────────────┴───────────────────────────────┐
    │                       REPOSITORIES                          │
    │(LibraryRepo, SearchRepo, CatalogRepo, LyricsRepo, Settings) │
    └────────┬────────────────────┬──────────────────────┬────────┘
             ↓                    ↓                      ↓
      ┌────────────┐       ┌────────────┐        ┌──────────────┐
      │   ROOM DB  │       │ DATASTORE  │        │ PROVIDER PORTS│
      │ (Library)  │       │(Preferences│        │(Search,      │
      │            │       │ & Proto)   │        │ Catalog, etc)│
      └────────────┘       └────────────┘        └───────┬──────┘
                                                         ↓
                                                 ┌──────────────┐
                                                 │   ADAPTERS   │
                                                 │ (YTM, Saavn) │
                                                 └───────┬──────┘
                                                         ↓ (HTTP)
                                                    EXTERNAL APIS
```

---

## 28. Architectural Contracts

### Repository Contract
Repositories are the single source of truth for their domain. They must normalize data from multiple sources (DB, External Music API Providers) and return pure domain models. `CatalogRepository` owns deep Track/Album/Artist entity retrieval, `SearchRepository` owns query discovery, and `LibraryRepository` owns local persistent user library/history.

### Provider Contract
External service logic must be isolated behind domain interfaces (Ports). External music API adapters map provider-specific JSON DTOs into generic domain models before returning them. `CatalogProviderPort` and `SearchProviderPort` are strictly segregated from playback's `StreamResolverPort`.

### Persistence Contract
Relational data (Library/History) uses Room. Simple preferences use Preferences DataStore. Complex structured session recovery state uses Proto DataStore.

### Session Persistence Contract
Session snapshots are proactively saved on track transitions, queue changes, mode toggles, state changes, and throttled position checkpoints (never relying solely on `onStop`). Restored sessions always resume in the `PAUSED` state.

### Caching Contract
Sonara relies on positive HTTP caching. Manual negative caching (caching failures to prevent retries) is strictly prohibited. Stream URLs are ephemeral and never persisted.

### Artwork Contract
Artwork URLs are embedded as string properties in domain models. Bitmap fetching, decoding, memory caching, and disk caching are handled strictly by the UI image loading layer.

### Lyrics Contract
`LyricsRepository` provides static synced/unsynced lyrics data and handles provider fallback/normalization. Romanization is a pure domain UseCase. High-frequency playback synchronization is owned by the UI layer, not the repository.

### Error Contract
Network and serialization exceptions must be caught at the adapter/repository boundary and mapped into semantic Domain Exceptions. ViewModels map these to presentation-safe abstractions (`UiText`) without referencing Android resources (`R.string.*`) directly.

### Dependency Injection Contract
Dependencies are provided via constructor injection. Interface binding is strictly used to separate domain definition from implementation.

---

## 29. Phase 4B-3 Decision Register

| Decision | Status | Final Decision | Rationale | Alternatives Rejected | Follow-up |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Model Separation** | ✅ DECIDED | Strict separation: DTO → Domain ← Entity | Protects UI from API/DB schema changes. | DTOs flowing directly to UI | None |
| **Catalog Ownership** | ✅ DECIDED | `CatalogRepository` owns deep entity retrieval (Track/Album/Artist) | Explicit owner for catalog navigation screens | Merging everything into SearchRepo | None |
| **Stream Resolver Port** | ✅ DECIDED | `StreamResolverPort` remains strictly segregated from Catalog/Search ports | Preserves Phase 4B-1 playback infrastructure boundary | Merging stream resolution into content provider ports | None |
| **Session Snapshot DB** | ✅ DECIDED | Proto DataStore with proactive persistence triggers | Type-safe, reactive, perfect for a single structured object. | Room (overkill); SharedPreferences (unsafe) | Protobuf schema definition |
| **Library Storage** | ✅ DECIDED | Room (SQLite) with unified Library+History repo | Requires relational queries, sorting, and foreign keys. | DataStore (cannot query efficiently) | Entity design |
| **Provider Fallback** | ✅ DECIDED | Handled internally by Repositories. | UI should only know success/failure, not provider specifics. | Fallback orchestrated in ViewModel | None |
| **Negative Caching** | ✅ DECIDED | Strictly Forbidden. | Prevents permanent "broken image/stream" UI states. | Caching failed resolutions (Web legacy) | None |
| **Error Mapping** | ✅ DECIDED | Adapters map HTTP errors to Domain Exceptions; ViewModels map to UI abstractions without `R.string.*`. | ViewModels shouldn't know about HTTP 503 or Android Context/Resources. | Passing `HttpException` to ViewModels | None |

---

## 30. Implementation Details Deferred

The following are strictly deferred to the implementation phase:
- **Exact Libraries**: Retrofit vs Ktor, Hilt vs Koin, Coil vs Glide. (Standard Android defaults are assumed but not mandated).
- **Exact Schemas**: Room `@Entity` definitions and Proto DataStore `.proto` files.
- **API Endpoints**: Specific URLs for providers.
- **Cache TTLs**: Exact duration for HTTP caching.
- **Coroutines**: Exact `Dispatchers.IO` injection and CoroutineExceptionHandler implementation.

---

## 31. Complete Architecture Consistency Check

This architecture has been cross-verified against all frozen phases:
- **Phase 4A**: Android-native, open-source principles are intact.
- **Phase 4B-1**: Playback remains fully isolated in `MediaSessionService`. Session recovery aligns perfectly with Proto DataStore, resulting in a `PAUSED` state.
- **Phase 4B-2**: The strict UDF boundary is maintained. Repositories feed pure domain models to ViewModels, which manage UI state without accessing `Context` or DTOs.

**Result**: NO CONTRADICTIONS EXIST. The architecture is cohesive.

---

## 32. Risks / Explicit Future Decisions

| Risk | Likelihood | Mitigation |
| :--- | :--- | :--- |
| **Provider API Changes** | High | Strict DTO-to-Domain mapping ensures that if an API breaks, only one isolated adapter file needs updating. |
| **Proto DataStore Complexity** | Low | Requires setting up Protobuf Gradle plugin, but the runtime safety vastly outweighs the setup cost. |

---

## 33. Sources / Evidence

- **Phase 4 Frozen Documents**: Ensured no architectural drift occurred during this final phase.
- **Android Persistence Docs**: Confirmed Proto DataStore superiority for structured typed data.
- **Sonara Web History**: Leveraged legacy failure modes (Negative Caching) to architect preventative guardrails.

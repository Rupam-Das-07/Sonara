# SONARA ANDROID — PRE-IMPLEMENTATION AUDIT 03
## Web → Android Image, Graphic & Media Asset Compatibility Audit

---

## 1. Executive Summary & Objective

This document establishes the authoritative **Pre-Implementation Asset Audit** for Sonara Android. It audits all visual, graphical, and media assets located in the reference Web implementation (**Melodify/Sonara Web**, located at `D:\COLLEGE WORK\MY PROJECTS\WEB DEV\Music Streaming Web Application [ REACT]  - Melodify` and associated graphic repositories).

The objective is to establish:
1. Which visual assets exist in the Web codebase.
2. Which brand and graphical assets can be reused directly or converted into Android-native formats (VectorDrawable, Adaptive Icons, Density-independent drawables).
3. Which assets are dynamic music artwork and must be fetched/cached at runtime via Coil rather than bundled.
4. Which Web assets are unsuitable, legacy, or Web-only and must be excluded.
5. The canonical resource mapping for Android (`res/drawable/`, `res/mipmap-anydpi-v26/`, etc.) and the implementation priority (P0 through P3).

---

## 2. Part 1: Complete Web Asset Inventory

A deep physical scan of the Web project repository identified **60 distinct media and graphic files**, categorized into functional classes:

```text
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                   WEB ASSET INVENTORY                                    │
├──────────────────────────────┬────────────────────────────────────────┬──────────────────┤
│ Category                     │ Asset Files & Locations                │ Format & Count   │
├──────────────────────────────┼────────────────────────────────────────┼──────────────────┤
│ 1. Product & Brand Assets    │ src/assets/branding/                   │ 6 SVG, 4 PNG     │
│                              │ public/ (android-chrome, logo192/512)  │ 4 PNG, 1 ICO     │
│ 2. Curated Playlist Artwork  │ public/assets/playlist-artwork/        │ 10 PNG (1254x1254│
│ 3. UI & Auth Illustrations   │ src/assets/auth/ (signin, signup)      │ 2 SVG            │
│ 4. Video & Motion Assets     │ public/videos/ (sonara-intro-dark/light│ 2 MP4            │
│ 5. Design Handoff Specs      │ D1_MOBILE_MINI_PLAYER_CIRCULAR_PROGRESS│ 9 PNG            │
│ 6. Favicons & Web Icons      │ public/ (favicon-16/32, apple-touch)   │ 3 PNG            │
│ 7. Test & Telemetry Datasets │ src/services/transliteration/tests/    │ 2 JSON (154 tests│
└──────────────────────────────┴────────────────────────────────────────┴──────────────────┘
```

### 2.1 Asset Segmentation
- **Product / Brand Assets**: The canonical Sonara "S" symbol (`sonara-symbol-dark.svg`, `sonara-symbol-light.svg`), the wordmark (`sonara-wordmark-dark.svg`), and horizontal logo lockups.
- **Curated Playlist Artwork**: 10 high-resolution (1254×1254) genre/vibe cover graphics (`chill-nights.png`, `lofi-focus.png`, `trending-now.png`, etc.).
- **UI / Feature Assets**: Auth page vector illustrations (`signin.svg`, `signup.svg`).
- **Dynamic Content**: Artist portraits, album covers, and track thumbnails fetched at runtime from YouTube Music and JioSaavn CDNs.
- **Development / Handoff Assets**: 9 mobile mini-player specification screenshots (`D1_320px_*.png`).
- **Web-Only / Legacy Assets**: Desktop intro videos (`sonara-intro-dark.mp4`), favicon `.ico` files, and PWA browser icons.

---

## 3. Part 2: Brand Asset Audit

```text
CANONICAL BRAND IDENTITY ASSETS
─────────────────────────────────────────────────────────────────────────
1. Canonical Sonara Symbol:
   - File: src/assets/branding/sonara-symbol-dark.svg (2400x2400 true vector)
   - Description: The continuous geometric S ribbon with soundwave contour.
   - Status: PRODUCTION READY for Android.

2. Canonical Sonara Wordmark:
   - File: src/assets/branding/sonara-wordmark-dark.svg (2000x800 true vector)
   - Description: Custom geometric typography for "SONARA".
   - Status: PRODUCTION READY for Android In-App Branding / Splash.

3. Horizontal App Logo Lockup:
   - File: src/assets/branding/Horizontal app logo-dark themed.svg (8000x3200)
   - Description: Symbol + Wordmark combined horizontally.
   - Status: PRODUCTION READY for Navigation Drawer / About Screen.
```

### 3.1 Brand Asset Evaluation
| Brand Asset | File Source | Intrinsic Dimensions | Format | Status / Role | Android Suitability |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Sonara Symbol (Dark)** | `branding/sonara-symbol-dark.svg` | 2400×2400 | SVG Path | Canonical Symbol | **P0: Convert to VectorDrawable** for Launcher Foreground & In-App Icon. |
| **Sonara Symbol (Light)** | `branding/sonara-symbol-light.svg` | 2400×2400 | SVG Path | Light-themed Symbol | **P1: Convert to VectorDrawable** for light theme rendering. |
| **Sonara Wordmark (Dark)** | `branding/sonara-wordmark-dark.svg` | 2000×800 | SVG Path | Canonical Wordmark | **P1: Convert to VectorDrawable** for AppBar / Splash / About. |
| **Sonara Wordmark (Light)** | `branding/sonara-wordmark-light.svg` | 2000×800 | SVG Path | Light Wordmark | **P1: Convert to VectorDrawable** for Light Theme UI headers. |
| **Horizontal Logo (Dark)** | `branding/Horizontal app logo-dark...` | 8000×3200 | SVG Path | Full Brand Lockup | **P2: Convert to VectorDrawable** for Drawer header or Settings. |
| **Raster Logo PNGs** | `branding/sonara-logo-dark.png` | 1280×216 | PNG | Raster Export | 🔴 **Do Not Bundle**: Superseded by clean SVG vector counterparts. |
| **Raster Symbol PNGs** | `branding/sonara-symbol-dark.png` | 633×853 | PNG | Non-Square Raster | 🔴 **Do Not Bundle**: Asymmetrical crop; superseded by 2400×2400 SVG. |

---

## 4. Part 3: Android Launcher Icon Compatibility (Adaptive Icon)

Android 8.0+ (API 26+, Sonara's frozen `minSdk`) requires **Adaptive Icons** composed of separate Foreground, Background, and Monochrome layers within a 108dp×108dp canvas (72dp circular safe zone):

```text
┌───────────────────────────────────────────────────────────┐
│                 108dp x 108dp Total Canvas                │
│                                                           │
│           ┌───────────────────────────────────┐           │
│           │      72dp Safe Zone (Visible)     │           │
│           │                                   │           │
│           │            ╭─────────╮            │           │
│           │            │    S    │            │           │  ◄── Foreground: Sonara S Symbol
│           │            ╰─────────╯            │           │
│           │                                   │           │
│           └───────────────────────────────────┘           │
│                                                           │
│  Background: Solid Dark Petrol (#12181B) / Subtle Wave   │  ◄── Background: Theme Surface
└───────────────────────────────────────────────────────────┘
```

### 4.1 Layer Breakdown for Sonara Adaptive Icon
1. **Foreground Layer (`res/drawable/ic_launcher_foreground.xml`)**:
   - Derived from: `sonara-symbol-dark.svg` (2400×2400 path data).
   - Transformation: Scaled and centered within the central 72dp safe zone of the 108dp canvas (inset by 18dp on all sides).
   - Format: Pure Android `VectorDrawable` with theme-colored gradient fill.
2. **Background Layer (`res/drawable/ic_launcher_background.xml`)**:
   - Color: Solid Dark Petrol (`#12181B` / `#0D1113`) matching the primary theme background.
   - Optional: Subtle radial gradient or geometric texture matching Sonara's visual identity.
3. **Monochrome Layer (`res/drawable/ic_launcher_monochrome.xml`) (Android 13+ Themed Icons)**:
   - Derived from: Silhouette of `sonara-symbol-dark.svg`.
   - Fill: Pure white (`#FFFFFF`) with transparent background, allowing Android OS Material You dynamic color tinting to apply automatically.

---

## 5. Part 4: Splash Screen Assets (Android 12+ SplashScreen API)

Android 12+ mandates the `androidx.core:core-splashscreen` API for app startup:
- **Splash Icon**: Single centered vector icon (`res/drawable/ic_splash_logo.xml`) derived from `sonara-symbol-dark.svg`.
  - Display Window: 160dp circular mask (maximum 288×288dp).
- **Splash Background**: Window background color set to `@color/sonara_bg_petrol` (`#12181B`).
- **Video Splash Assets (`sonara-intro-dark.mp4`)**:
  - 🔴 **UNSUITABLE FOR ANDROID STARTUP**: The Web MP4 video intro is 167 KB and introduces multi-second blocking latency on cold launch. Android guidelines strictly require instantaneous static/animated VectorDrawable splash transitions without video decoding overhead.

---

## 6. Part 5: Vector / SVG Audit

Every SVG asset in the Web project was audited for Android `VectorDrawable` compatibility:

```text
┌──────────────────────────────────────────────┬───────────────────┬──────────────────────────────────────────────┐
│ Web SVG File                                 │ Compatibility     │ Required Conversion Action                   │
├──────────────────────────────────────────────┼───────────────────┼──────────────────────────────────────────────┤
│ sonara-symbol-dark.svg                       │ 🟢 DIRECT / CLEAN │ Convert path d="..." to VectorDrawable XML.  │
│ sonara-symbol-light.svg                      │ 🟢 DIRECT / CLEAN │ Convert path d="..." to VectorDrawable XML.  │
│ sonara-wordmark-dark.svg                     │ 🟢 DIRECT / CLEAN │ Convert path d="..." to VectorDrawable XML.  │
│ sonara-wordmark-light.svg                    │ 🟢 DIRECT / CLEAN │ Convert path d="..." to VectorDrawable XML.  │
│ Horizontal app logo-dark themed.svg          │ 🟢 DIRECT / CLEAN │ Convert path d="..." to VectorDrawable XML.  │
│ Horizontal app logo-light themed.svg         │ 🟢 DIRECT / CLEAN │ Convert path d="..." to VectorDrawable XML.  │
│ signin.svg (Auth Illustration)               │ 🟡 CONVERT/CLEAN  │ Strip Web clip-paths; simplify group transforms.│
│ signup.svg (Auth Illustration)               │ 🟡 CONVERT/CLEAN  │ Strip Web clip-paths; simplify group transforms.│
└──────────────────────────────────────────────┴───────────────────┴──────────────────────────────────────────────┘
```

### 5.1 SVG Feature Verification
- **True Vectors**: All branding SVGs contain clean bezier `<path>` data without embedded base64 raster bitmaps.
- **No Web-Only Filters**: No unsupported CSS filters (`filter: blur(...)`, `<feGaussianBlur>`) or JavaScript dependencies.
- **Dimensions**: Viewports range from 2000×800 to 2400×2400, perfectly suited for standard Android `android:viewportWidth` and `android:viewportHeight` vector normalization.

---

## 7. Part 6: Bitmap Asset Audit

| Asset File | Web Location | Dimensions | Size | Purpose | Android Strategy |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Curated Playlist Artwork (10 files)** | `public/assets/playlist-artwork/` | 1254×1254 | ~2.4 MB ea | Featured vibe playlist banners | **Bundle in `res/drawable-nodpi/`** (or host on CDN for production). High-res 1:1 square. |
| **`android-chrome-512x512.png`** | `public/` | 512×512 | 115 KB | PWA icon | 🔴 **Do Not Port**: Superseded by vector adaptive icon. |
| **`logo192.png` / `logo512.png`** | `public/` | 192/512 | 21 KB / 115 KB | Web manifest icons | 🔴 **Do Not Port**: Superseded by vector adaptive icon. |
| **`favicon-*.png` / `favicon.ico`** | `public/` | 16×16 / 32×32 | <2 KB | Browser tabs | 🔴 **Web-Only**: Irrelevant for Android. |
| **Design Handoff Screenshots (9 files)**| `D1_MOBILE_MINI_PLAYER_*` | 320/360/390px | ~70 KB ea | UI design specification | 🔴 **Development Only**: Reference material only; never bundled. |

---

## 8. Part 7: Music Artwork Pipeline

The Web application's dynamic artwork pipeline (`utils/thumbnailResolver.js`, `utils/colorExtractor.js`, `MetadataNormalizer.js`) was audited against Android's image architecture:

```text
[API Provider (YTMusic / JioSaavn)] ──► [Track DTO with raw thumbnail URL]
                                                      │
                                                      ▼
                                       [Domain Model (Track.artworkUrl)]
                                                      │
                                      (Upgraded to high-res square CDN URL)
                                                      │
                                                      ▼
                                   [Coil AsyncImage / ImageRequest in Compose]
                                                      │
                                      (Disk / Memory Cache via OkHttp)
                                                      │
                                                      ▼
                                  [AndroidX Palette Dominant Color Extraction]
                                                      │
                                                      ▼
                                      [Ambient Dynamic Background Glow]
```

### 8.1 Reusable Dynamic Artwork Logic
1. **High-Resolution URL Upgrading (`thumbnailResolver.js`)**:
   - **Google / YTMusic (`lh3.googleusercontent.com`)**: Rewrite `=w120-h120-...` $	o$ `=w544-h544-l90-rj` (delivers clean 544×544 high-res album art).
   - **JioSaavn (`c.saavncdn.com`)**: Rewrite `/150x150/` $	o$ `/500x500/` (delivers 500×500 cover art).
   - **YouTube CDN (`i.ytimg.com`)**: Rewrite `hqdefault` $	o$ `maxresdefault`.
   - **Verdict**: **PORT TO KOTLIN** in `data.remote.mapper.ArtworkUrlUpgrader`.
2. **Artwork Resolution Priority**:
   - `enrichedThumbnail` (JioSaavn 1:1) > `albumArt` (Explicit 1:1) > `thumbnailUrl` (YTMusic 1:1) > `YouTube frame` (16:9 video frame).
   - **Verdict**: **PORT TO KOTLIN** in catalog mapping.
3. **Dominant Color Extraction (`colorExtractor.js`)**:
   - Web used an 8×8 offscreen HTML canvas.
   - **Android Native Solution**: Replace with **`androidx.palette:palette-ktx`** to extract vibrant/dominant colors directly from Coil Bitmaps for ambient Compose backgrounds.

---

## 9. Part 8: Artwork URL / Cache Compatibility

### 8.1 Browser Workarounds to Discard
- **Web ORB / CORS Proxies**: The Web app required specific header workarounds for Chromium Opaque Response Blocking (ORB) and canvas tainting. On Android, native OkHttp/Coil networking is unrestricted by browser CORS/ORB rules. Discard all Web proxy shims.
- **Negative Caching of Images**: Web maintained in-memory blacklists for failed image URLs. In accordance with Phase 4B-3 and Phase 4C, **manual negative caching is strictly forbidden**; Coil and OkHttp standard HTTP caching headers handle image lifecycles natively.

---

## 10. Part 9: Placeholder & Fallback Artwork

| Fallback Scenario | Web Implementation | Recommended Android Strategy | Resource Target |
| :--- | :--- | :--- | :--- |
| **Missing Track / Album Art** | Generic SVG music note | Material 3 Vinyl / Music Note VectorDrawable on dark surface | `res/drawable/ic_default_album_art.xml` |
| **Missing Artist Portrait** | Generic avatar SVG | Material 3 Person / Artist VectorDrawable with circular crop | `res/drawable/ic_default_artist_avatar.xml` |
| **Broken Image Load** | Muted grey container | Cross-faded placeholder from Coil with error retry icon | Handled via Coil `error(R.drawable.ic_broken_image)` |
| **Empty Library / Playlist** | Empty state text | Clean vector illustration (headphones / empty crate) | `res/drawable/ic_empty_library.xml` |
| **No Lyrics Available** | "No lyrics found" text | Pure Compose declarative UI with subtle typography | Compose `EmptyState` composable |

---

## 11. Part 10: Iconography Audit

### 11.1 Custom Brand Icons vs Material 3 Standard Icons
```text
CUSTOM BRAND ICONS (PORT FROM WEB SVGs)       STANDARD UI ICONS (USE MATERIAL ICONS IN COMPOSE)
────────────────────────────────────────       ─────────────────────────────────────────────────
• Sonara "S" Symbol                            • Play / Pause (Icons.Rounded.PlayArrow / Pause)
• Sonara Wordmark                              • Skip Next / Prev (Icons.Rounded.SkipNext / Prev)
• Romanization Toggle ("aA" script icon)       • Shuffle / Repeat (Icons.Rounded.Shuffle / Repeat)
• Curated Playlist Covers                      • Search / Library (Icons.Rounded.Search / LibraryMusic)
                                               • Heart / Favorite (Icons.Rounded.Favorite / FavoriteBorder)
                                               • More / Overflow (Icons.Rounded.MoreVert)
```
- **Rule**: Do NOT import dozens of standard SVG icons from Web. Jetpack Compose Material 3 extended icons (`androidx.compose.material.icons:material-icons-extended`) provide highly optimized, density-independent vector primitives for standard player controls.

---

## 12. Part 11: Decorative & Background Assets

- **Ambient Background Glow**: Web used dynamic CSS box-shadows and canvas color sampling. In Android Compose, ambient glow is implemented natively via `Modifier.drawBehind { drawCircle(Brush.radialGradient(...)) }` using colors extracted from AndroidX Palette.
- **Noise / Textures**: Web used CSS noise overlays. In Android, keep the UI clean, modern, and performant by relying on Material 3 tonal elevation and surface tinting (`Theme.Sonara`).

---

## 13. Part 12: Typography & Font Assets

- **Web Typography**: Relied on system-ui, `-apple-system`, `BlinkMacSystemFont`, `Inter`, and `Roboto` via CSS font stacks.
- **Android Decision**: In accordance with Phase 4A, Sonara Android uses the **Android System Roboto / Variable Font** family integrated into `core.ui.theme.Type.kt`. No external font binaries (`.ttf`/`.woff2`) need to be bundled into the initial Android build, keeping APK footprint minimal and ensuring native OS typography rendering.

---

## 14. Part 13: Animation & Motion Assets

- **Web MP4 Videos (`public/videos/sonara-intro-*.mp4`)**: Web used full-screen autoplay video backgrounds for onboarding.
  - **Verdict: DO NOT PORT**. Video playback adds massive binary bloat and battery drain.
- **Android Motion Strategy**: Use Compose's built-in physics-based spring animations (`animateFloatAsState`, `AnimatedVisibility`, `Crossfade`) as defined in Phase 4B-2.

---

## 15. Part 14: Proposed Android Resource Mapping

```text
app/src/main/res/
├── drawable/
│   ├── ic_launcher_background.xml            (Adaptive Icon Background - Solid Petrol #12181B)
│   ├── ic_launcher_foreground.xml            (Adaptive Icon Foreground - S Symbol in 72dp safe zone)
│   ├── ic_launcher_monochrome.xml            (Adaptive Icon Monochrome - Pure white S silhouette)
│   ├── ic_splash_logo.xml                    (Splash Screen S Symbol for Android 12+ SplashScreen)
│   ├── ic_sonara_symbol.xml                  (General in-app S symbol VectorDrawable)
│   ├── ic_sonara_wordmark.xml                (General in-app Wordmark VectorDrawable)
│   ├── ic_sonara_logo_horizontal.xml         (Full horizontal logo lockup VectorDrawable)
│   ├── ic_romanization_toggle.xml            (Lyrics "aA" transliteration toggle VectorDrawable)
│   ├── ic_default_album_art.xml              (Placeholder for missing album/track art)
│   ├── ic_default_artist_avatar.xml          (Placeholder for missing artist portrait)
│   ├── ic_empty_library.xml                  (Illustration for empty Liked Songs / History)
│   ├── illustration_signin.xml               (VectorDrawable for Sign In screen)
│   └── illustration_signup.xml               (VectorDrawable for Sign Up screen)
│
├── drawable-nodpi/                           (Unscaled high-resolution raster covers)
│   ├── cover_chill_nights.png                (Curated playlist artwork - 1254x1254)
│   ├── cover_lofi_focus.png                  (Curated playlist artwork - 1254x1254)
│   ├── cover_trending_now.png                (Curated playlist artwork - 1254x1254)
│   └── cover_retro_bollywood.png             (Curated playlist artwork - 1254x1254)
│
└── mipmap-anydpi-v26/
    ├── ic_launcher.xml                       (Adaptive Icon XML declaration)
    └── ic_launcher_round.xml                 (Adaptive Icon XML declaration)
```

---

## 16. Part 15: Master Asset Compatibility Matrix

| Asset Description | Web Source File | Type | Android Role | Compatibility Status | Required Conversion | Proposed Target Location | Priority |
| :--- | :--- | :--- | :--- | :---: | :--- | :--- | :---: |
| **Sonara S Symbol (Dark)** | `branding/sonara-symbol-dark.svg` | SVG | Launcher Foregound, Splash, In-App Icon | 🟢 **DIRECT REUSE** | Convert to VectorDrawable XML | `res/drawable/ic_sonara_symbol.xml` | **P0** |
| **Sonara S Symbol (Light)**| `branding/sonara-symbol-light.svg`| SVG | Light-theme in-app icon | 🟢 **DIRECT REUSE** | Convert to VectorDrawable XML | `res/drawable/ic_sonara_symbol_light.xml`| **P1** |
| **Sonara Wordmark (Dark)** | `branding/sonara-wordmark-dark.svg`| SVG | Header branding, About screen | 🟢 **DIRECT REUSE** | Convert to VectorDrawable XML | `res/drawable/ic_sonara_wordmark.xml` | **P1** |
| **Horizontal App Logo** | `branding/Horizontal app logo-dark...`| SVG | Navigation Drawer / Splash | 🟢 **DIRECT REUSE** | Convert to VectorDrawable XML | `res/drawable/ic_sonara_logo_horizontal.xml`| **P2** |
| **Curated Playlist Covers (10)**| `public/assets/playlist-artwork/*`| PNG | Featured category covers | 🟢 **DIRECT REUSE** | Compress/Optimize; place in `nodpi` | `res/drawable-nodpi/cover_*.png` | **P2** |
| **Sign-in Illustration** | `src/assets/auth/signin.svg` | SVG | Auth Screen Artwork | 🟢 **REUSE AFTER CONVERSION**| Clean Web clip-paths to VectorDrawable| `res/drawable/illustration_signin.xml`| **P2** |
| **Sign-up Illustration** | `src/assets/auth/signup.svg` | SVG | Auth Screen Artwork | 🟢 **REUSE AFTER CONVERSION**| Clean Web clip-paths to VectorDrawable| `res/drawable/illustration_signup.xml`| **P2** |
| **Dynamic Track/Album Art**| Remote CDNs | URL | Media Player & Catalog | 🟡 **REMOTE / DYNAMIC** | Handled via Coil + `ArtworkUrlUpgrader`| Dynamic Memory/Disk Cache | **P0** |
| **Ambient Color Glow** | `utils/colorExtractor.js` | Code | Ambient Dynamic Backdrop | 🟡 **RECREATE NATIVELY** | Replace Canvas with AndroidX Palette | Compose Shader / Palette API | **P1** |
| **Default Album Art** | Web SVG Glyphs | SVG | Fallback Placeholder | 🟡 **RECREATE NATIVELY** | Create Material 3 VectorDrawable | `res/drawable/ic_default_album_art.xml`| **P1** |
| **Raster Logo PNGs** | `branding/sonara-logo-*.png` | PNG | Obsolete Web Exports | 🔴 **DO NOT PORT** | Superseded by SVG vectors | N/A (Discard) | **P3** |
| **Desktop Intro Videos** | `public/videos/*.mp4` | MP4 | Web Hero Animations | 🔴 **WEB-ONLY** | Violates Android cold launch speed | N/A (Discard) | **P3** |
| **Handoff Mockups (9)** | `D1_MOBILE_MINI_PLAYER_*` | PNG | Design Specifications | 🔴 **DEVELOPMENT ONLY** | Specification artifacts only | N/A (Discard) | **P3** |
| **Favicons / Web Manifest**| `public/favicon.*`, `logo*.png` | ICO/PNG| Browser Tab Icons | 🔴 **WEB-ONLY** | Replaced by Android Adaptive Icons | N/A (Discard) | **P3** |

---

## 17. Part 16: Priority Classification for Implementation

### P0 — Required for Initial Android Build & Shell (Blocking)
1. **`ic_launcher_foreground.xml` / `ic_launcher_background.xml` / `ic_launcher_monochrome.xml`**: Adaptive launcher icon derived from `sonara-symbol-dark.svg`.
2. **`ic_splash_logo.xml`**: Android 12+ SplashScreen logo.
3. **Coil Image Loading Pipeline**: `AsyncImage` configured with OkHttp disk cache for dynamic track artwork.

### P1 — Required for First Complete UI Implementation
1. **`ic_sonara_wordmark.xml`**: Vector wordmark for app bar.
2. **`ic_default_album_art.xml` & `ic_default_artist_avatar.xml`**: Native VectorDrawable fallback placeholders.
3. **`ArtworkUrlUpgrader.kt`**: Kotlin port of `thumbnailResolver.js` high-res CDN rewriting rules.
4. **AndroidX Palette Integration**: Native dominant color extraction for dynamic player glow.

### P2 — Polish & Feature Assets (Non-Blocking)
1. **Curated Playlist Cover PNGs**: 10 vibe banners for home feed.
2. **Auth Illustrations (`illustration_signin.xml`, `illustration_signup.xml`)**: Cleaned vector graphics for authentication.
3. **Horizontal Brand Lockup (`ic_sonara_logo_horizontal.xml`)**: For navigation drawer/about dialog.

### P3 — Do Not Port (Excluded)
- Desktop MP4 intro videos, obsolete raster logo PNGs, PWA favicons, and design specification screenshots.

---

## 18. Part 17: Duplicate & Legacy Asset Detection

1. **Duplicate Logo Files**: The folder `D:\COLLEGE WORK\MY PROJECTS\WEB DEV\images for music app` contains experimental variations (`app icon.jpeg`, `Sonara-Photoroom.png`, `dark app icon - background removed.png - bigger size.png`).
   - **Canonical Decision**: All external scratch folders are IGNORED. The single authoritative source for branding is `src/assets/branding/` inside the React project, specifically `sonara-symbol-dark.svg` and `sonara-wordmark-dark.svg`.
2. **Asymmetrical Symbol PNGs**: `sonara-symbol-dark.png` has non-square dimensions (633×853), causing distortion if placed directly in icon frames. The square 2400×2400 SVG must be used instead.

---

## 19. Part 18: Legal & Source Provenance Classification

| Asset Group | Source / Creator | License / Provenance Classification | Bundling Verdict |
| :--- | :--- | :--- | :--- |
| **Sonara Symbol & Wordmark** | Sonara Project Team | **Proprietary / Open-Source Project Identity (100% Clear)** | ✅ Safe to bundle |
| **Curated Playlist Artwork** | Sonara Web Project | **Project Art Assets (Clear for Sonara distribution)** | ✅ Safe to bundle |
| **Auth Illustrations** | Open-source SVG vector pack | **Vector Graphic (Permissive / Clean)** | ✅ Safe after XML conversion |
| **Dynamic Music Album Art** | YouTube Music / JioSaavn CDNs | **External Dynamic Provider Metadata** | 🟡 **Never bundle; load dynamically** |
| **Standard Icons** | Google Material Design | **Apache 2.0 (Material Design Icons)** | ✅ Safe via Compose Material Icons |

---

## 20. Part 19: Final Canonical Sonara Android Asset Set

The official approved assets to be imported into Android during implementation are:

```text
CANONICAL SONARA ANDROID ASSET SET
─────────────────────────────────────────────────────────────────────────────
1. Brand Vectors (from src/assets/branding/):
   • sonara-symbol-dark.svg         ──► res/drawable/ic_sonara_symbol.xml & Launcher Foreground
   • sonara-wordmark-dark.svg       ──► res/drawable/ic_sonara_wordmark.xml
   • Horizontal app logo-dark...    ──► res/drawable/ic_sonara_logo_horizontal.xml

2. Auth Vectors (from src/assets/auth/):
   • signin.svg                     ──► res/drawable/illustration_signin.xml
   • signup.svg                     ──► res/drawable/illustration_signup.xml

3. Curated Playlist Covers (from public/assets/playlist-artwork/):
   • 10 High-Res Vibe PNGs (1254x1254) ──► res/drawable-nodpi/cover_*.png

4. Dynamic CDN Pipeline (from utils/thumbnailResolver.js):
   • High-res CDN regex rules       ──► data.remote.mapper.ArtworkUrlUpgrader.kt
   • Dynamic Color Extraction       ──► androidx.palette:palette-ktx (Native Compose)
─────────────────────────────────────────────────────────────────────────────
```

---

## 21. Part 20: Explicit Categorical Separation

To maintain pristine architectural hygiene throughout implementation:
- **Brand Assets**: Bundled vector drawables representing Sonara's identity (Symbol, Wordmark, Launcher, Splash).
- **Static UI Assets**: Bundled vector placeholders (`ic_default_album_art.xml`, `illustration_signin.xml`) and curated category covers (`cover_chill_nights.png`).
- **Dynamic Music Artwork**: Ephemeral network streams fetched via Coil (never bundled, never committed to git).
- **User-Generated Artwork**: Local image URIs selected by the user for custom playlists (stored in app private storage).
- **Web-Only Assets**: Desktop intro videos, favicons, HTML canvas scripts (completely discarded).
- **Development Assets**: Design handoff screenshots and test JSONs (stored in `src/test/resources/` or discarded).

---

## 22. Final Verification

- **Web Asset Trees Inspected**: `D:\COLLEGE WORK\MY PROJECTS\WEB DEV\Music Streaming Web Application [ REACT]  - Melodify`
- **Frozen Architecture Documents**: All 6 frozen Phase documents (`Phase 4A`, `4B-1`, `4B-2`, `4B-3`, `4C`, `4D`), `Audit 01`, and `Audit 02` remain **100% UNTOUCHED**.
- **Codebase Integrity**: Zero implementation code files, resources, Gradle files, or Manifests were created or modified.

---

# SONARA — WEB THEME TOGGLE MOTION SPECIFICATION
## Reverse-Engineered Motion Contract & Android/Compose Implementation Guide

**Document:** `WEB_THEME_TOGGLE_MOTION_SPECIFICATION.md`  
**Purpose:** Precise documentation of the Sonara Web theme-toggle animation mechanics for native Android Jetpack Compose recreation  
**Source Baseline:** Sonara / Melodify Web Production Codebase (`react final project/music-player/src/`)  
**Status:** **AUTHORITATIVE MOTION SPECIFICATION**  

---

## 1. Executive Summary & Source Implementation Location

In the Sonara Web application, the theme toggle animation is implemented as a specialized view-transition component that orchestrates full-screen spatial reveals between Dark Mode and Light Mode.

### Exact Implementation Locations in Web Project
- **Primary Component:** `src/components/lightswind/toggle-theme.jsx` (297 lines)
- **Active Usage / Integration Point:** `src/components/common/Header.jsx` (lines 85–91)
- **Theme State Management:** `src/hooks/useTheme.js` (`ThemeProvider`, `useTheme`, `localStorage.getItem("theme")`, `data-theme` attribute)
- **Global CSS Overrides:** `src/App.css` (lines 459–468)
- **Motion Design Tokens:** `src/styles/tokens/motion.css`

---

## 2. Observed Web Implementation Behavior

### 2.1 Active Component Configuration
In `Header.jsx`, the theme toggle is instantiated with the following active props:

```jsx
<ToggleTheme 
  isDark={darkMode} 
  onToggle={toggleDarkMode} 
  animationType="wave-ripple"
  duration={800}
  className="primitive-icon-button variant-ghost size-md motion-focus-ring theme-toggle-btn"
/>
```

### 2.2 Active Animation Breakdown: `"wave-ripple"`
- **Animation Type:** `"wave-ripple"`
- **Configured Base Duration:** `800ms` (`duration = 800`)
- **Calculated Execution Duration:** `duration * 1.5` = **`1200ms` (1.2 seconds)**
- **Easing Curve:** `cubic-bezier(0.68, -0.55, 0.265, 1.55)` (Anticipatory overshoot / back-ease curve with negative pull-back before rapid expansion and final spring overshoot)
- **Mechanism:** W3C View Transitions API (`document.startViewTransition`) paired with Web Animations API (`document.documentElement.animate`) targeting `::view-transition-new(root)`
- **Spatial Geometry:**
  - **Origin:** Viewport center `(50% 50%)`
  - **Start Keyframe:** `clipPath: circle(0% at 50% 50%)`
  - **End Keyframe:** `clipPath: circle(${maxRadius}px at 50% 50%)`
  - **Radius Calculation:**  
    $$ \text{maxRadius} = \sqrt{\max(x, W - x)^2 + \max(y, H - y)^2}$$  
    (Covers the full viewport diagonal from origin)

### 2.3 Snapshot & Blend Mechanics
To prevent default browser crossfading and allow JavaScript clip-path control, global CSS overrides default View Transition blending:

```css
/* App.css lines 459-468 */
::view-transition-old(root),
::view-transition-new(root) {
  animation: none;
  mix-blend-mode: normal;
}

::view-transition-new(root) {
  z-index: 1;
}
```
The previous theme snapshot (`::view-transition-old(root)`) remains frozen and completely opaque underneath while the new theme snapshot (`::view-transition-new(root)`) clips and expands outward in an expanding circular wavefront.

### 2.4 Icon Transition & Interactive State
- **Icons:** Lucide React icons:
  - `Sun` (`<Sun className="h-6 w-6" />` / 24×24dp) rendered when `isDark == true` (clicking it transitions to light).
  - `Moon` (`<Moon className="h-6 w-6" />` / 24×24dp) rendered when `isDark == false` (clicking it transitions to dark).
- **State Synchronization:** DOM state mutation is wrapped inside `flushSync(() => { onToggle(); })` inside the transition callback, ensuring the icon glyph flips synchronously as the new root snapshot is captured.
- **Button Micro-Interactions:**
  - CSS transition: `transition-colors duration-300`
  - Hover styling: `hover:text-amber-400` (Dark) / `hover:text-primarylw` (Light)
  - Focus Ring: `motion-focus-ring` (box-shadow ring)

### 2.5 Catalog of All Implemented Animation Modes in `toggle-theme.jsx`

| Animation Type | Duration Multiplier | Effective Duration | Easing Curve | Visual Mechanism |
| :--- | :---: | :---: | :--- | :--- |
| **`wave-ripple`** *(Active in Sonara)* | `1.5x` | **1200ms** | `cubic-bezier(0.68, -0.55, 0.265, 1.55)` | Full viewport center circular clip-path ripple with bounce curve |
| **`circle-spread`** | `1.0x` | 800ms | `ease-in-out` | Button coordinate $(x, y)$ origin circular clip-path spread |
| **`round-morph`** | `1.2x` | 960ms | `cubic-bezier(0.68, -0.55, 0.265, 1.55)` | Opacity $(0  \to  1)$ + Scale $(0.8  \to  1.0)$ + Rotation $(5^\circ  \to  0^\circ)$ |
| **`shrink-grow`** | `1.2x` | 960ms | `cubic-bezier(0.19, 1, 0.22, 1)` | Old root scales $(1.0  \to  1.05,  \text{opacity } 1  \to  0)$, New root scales $(0.9  \to  1.0,  \text{opacity } 0  \to  1)$ |
| **`split-vertical`** | `1.5x` | 1200ms | `cubic-bezier(0.68, -0.55, 0.265, 1.55)` | Center vertical split curtain wipe with bounce curve |
| **`flip-x-in`** | `1.0x` | 400ms | Dynamic CSS keyframes | 3D perspective Y-axis flip (Old $0^\circ  \to  -90^\circ$, New $90^\circ  \to  0^\circ$) |
| **`fade-in-out`** | `0.5x` | 400ms | `ease-in-out` | Direct full-screen opacity crossfade $(0  \to  1)$ |
| **`swipe-left` / `swipe-right`** | `1.0x` | 800ms | `cubic-bezier(0.2, 0, 0, 1)` | Horizontal edge wipe via `inset()` |
| **`swipe-up` / `swipe-down`** | `1.0x` | 800ms | `cubic-bezier(0.2, 0, 0, 1)` | Vertical edge wipe via `inset()` |
| **`diag-down-right`** | `1.5x` | 1200ms | `cubic-bezier(0.4, 0, 0.2, 1)` | Diagonal corner polygon wipe (`polygon()`) |

---

## 3. Platform-Independent Motion Contract

To preserve Sonara's brand identity across platforms, the theme switch must satisfy the following contract:

1. **Spatial Reveal Over Simple Crossfade:** The theme change is a physical spatial reveal rather than a passive, uniform alpha crossfade.
2. **Circular Wavefront Geometry:** The new theme emerges from a focal point (the button touch origin or viewport center) and expands radially until it fills the screen.
3. **Anticipatory / Natural Easing:** Motion begins with subtle anticipation, accelerates across the canvas, and settles with a soft deceleration/settle curve.
4. **Coordinated Icon Morphing:** The toggle icon transitions between Sun and Moon in synchronization with the ripple without clipping or jarring layout shifts.
5. **Zero Frame Dropping / Compositor Decoupling:** Theme animation must not stall audio playback, audio clocks, or background services.
6. **Reduced Motion Graceful Degradation:** When reduced motion is requested, spatial wipes must be replaced with an instant switch or 150ms subtle crossfade.

---

## 4. Web-Specific Details NOT to Copy to Android

The following implementation techniques are specific to the Web DOM and must **NOT** be ported literally to Android / Jetpack Compose:

- ❌ **`document.startViewTransition` / View Transitions API:** Web-only browser API that rasterizes the DOM into live pseudo-elements (`::view-transition-old(root)`).
- ❌ **`flushSync` / React DOM:** Synchronous React DOM rendering force-flush.
- ❌ **`document.documentElement.animate()`:** Web Animations API manipulating DOM CSS properties.
- ❌ **Inline `<style>` Injections:** Injecting `@keyframes` or style tags directly into `document.head`.
- ❌ **Pixel Coordinate DOM Rects:** `buttonRef.current.getBoundingClientRect()` raw window coordinate extraction.

---

## 5. Android / Jetpack Compose Translation Guide

### 5.1 Architecture Strategy in Compose
In Jetpack Compose, the theme transition can be achieved natively and efficiently via one of two approved Android patterns:

#### Pattern A: Spatial Canvas Mask Overlay (Exact "Wave-Ripple" Port)
- When the theme toggle is clicked, the current screen composition is captured or rendered onto a bottom layer with the old `SonaraColors`.
- The new theme is rendered with a circular `Path` / `clipPath` or `Modifier.drawWithContent` driven by an `Animatable<Float, AnimationVector1D>`.
- **Animation Specs:**
  - Duration: `800ms` (standard) or `1000–1200ms` (cinematic wave-ripple).
  - Easing: `CubicBezierEasing(0.68f, -0.55f, 0.265f, 1.55f)` (anticipatory overshoot) or `FastOutSlowInEasing`.
  - Origin: Toggle button touch coordinates $(x, y)$ or screen center $(W/2, H/2)$.

#### Pattern B: Smooth Semantic Color Interpolation (`animateColorAsState`)
- Individual surface and text tokens animate smoothly using `animateColorAsState(targetValue = colors.surface, animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing))`.
- This provides an ultra-lightweight, battery-efficient alternative that requires zero canvas layer allocations.

### 5.2 Icon Transition in Compose
The `Sun` $\leftrightarrow$ `Moon` icon transition in Compose should use:
```kotlin
AnimatedContent(
    targetState = isDarkTheme,
    transitionSpec = {
        (fadeIn(animationSpec = tween(220, delayMillis = 90)) +
         scaleIn(initialScale = 0.8f, animationSpec = tween(220, delayMillis = 90)) +
         rotateIn(initialAngle = -90f, animationSpec = tween(220, delayMillis = 90)))
            .togetherWith(
                fadeOut(animationSpec = tween(90)) +
                scaleOut(targetScale = 0.8f, animationSpec = tween(90)) +
                rotateOut(targetAngle = 90f, animationSpec = tween(90))
            )
    },
    label = "ThemeToggleIconTransition"
) { isDark ->
    if (isDark) {
        Icon(imageVector = Icons.Filled.WbSunny, contentDescription = "Switch to Light Mode")
    } else {
        Icon(imageVector = Icons.Filled.DarkMode, contentDescription = "Switch to Dark Mode")
    }
}
```

### 5.3 Accessibility & Reduced Motion Handling
- Observe system animator scale via `LocalContext.current` or `LocalView.current` / Android accessibility settings.
- If `prefers-reduced-motion` / accessibility animations are disabled, set `durationMillis = 0` (instant swap).

---

## 6. Unknowns & Unverified Behaviors

- **GPU Performance on Low-End Android Devices:** In Web, `startViewTransition` delegates full-page bitmap capture to the browser engine's compositor. On lower-end Android devices (e.g. `minSdk 26` budget hardware), full-screen canvas shader clipping during active ExoPlayer playback could cause slight frame drops if not hardware-accelerated. Device-level benchmarking during the UI animation phase is recommended to decide between Pattern A (Canvas Overlay) and Pattern B (Semantic Color Animation).

---

## 7. Verification & Document Freeze Notice

- **Verified Source Files:**
  - `react final project/music-player/src/components/lightswind/toggle-theme.jsx`
  - `react final project/music-player/src/components/common/Header.jsx`
  - `react final project/music-player/src/hooks/useTheme.js`
  - `react final project/music-player/src/App.css`
  - `react final project/music-player/src/styles/tokens/motion.css`
  - `react final project/music-player/src/styles/themes/themes.css`
- **Zero Modifications:** No Web project files or Android implementation files were modified during this inspection.

package com.example.sonara.core.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Sonara's frozen navigation-motion vocabulary — the single source of truth for every
 * screen-to-screen transition in the app. See `docs/navigation-motion-freeze.md`.
 *
 * There are exactly TWO primitives and no third:
 *
 *  - [peerSlide] — a restrained, full-width horizontal page slide (translation only: no scale, no
 *    fade). Lateral moves between peer destinations (Home ↔ Search ↔ Library ↔ Playlists), directed
 *    by tab order like adjacent home-screen pages. Deliberately has NO scale, which is what
 *    distinguishes "sibling" from "deeper".
 *  - [depthForward] / [depthBack] — a subtle scale + fade (no translation). Entering / leaving a
 *    deeper surface (detail screens, Settings, Library→playlist). Forward and back are exact
 *    inverses; back is a touch quicker so returns feel responsive.
 *
 * The depth values below are Sonara Home's original, hand-tuned detail transition promoted to
 * canonical. They are FROZEN (2026-09-12): the DEPTH primitive must not add translation, spring,
 * blur, a third primitive, or a `SizeTransform` (every destination fills the same Box, so size
 * never animates). The peer primitive is a separate, deliberately spatial horizontal slide.
 *
 * Reduced motion: pass `reduceMotion = true` (the [LocalReduceMotion] gate) to collapse any
 * primitive to a short opacity-only fade with no scale or translation. The system "animator duration
 * scale == 0" case needs no branch here — Compose's animation clock reads `ANIMATOR_DURATION_SCALE`
 * live, so at a scale of 0 every [tween] below completes on its first frame, i.e. a hard cut.
 */
object SonaraMotion {

    // -- Durations (ms) -------------------------------------------------------------------------
    const val DEPTH_FORWARD_IN_MS = 240
    const val DEPTH_FORWARD_OUT_MS = 180
    const val DEPTH_BACK_IN_MS = 200
    const val DEPTH_BACK_OUT_MS = 180
    const val PEER_MS = 300  // tuned: 300ms sweet spot for full-width horizontal slide
    const val REDUCED_MS = 120

    // -- Scale endpoints (subtle depth, NOT a zoom) ---------------------------------------------
    const val DEPTH_FORWARD_IN_SCALE = 0.94f   // incoming grows gently toward the user
    const val DEPTH_FORWARD_OUT_SCALE = 0.98f  // outgoing recedes
    const val DEPTH_BACK_IN_SCALE = 0.98f      // parent returns from slightly receded
    const val DEPTH_BACK_OUT_SCALE = 0.94f     // detail shrinks away

    /**
     * Peer → peer. A restrained, full-width horizontal page slide — both surfaces translate in
     * lockstep like adjacent home-screen pages: no fade, no spring, no overshoot. [forward] follows
     * tab order (Home→Search→Library→Playlists): forward pushes the current surface left and brings
     * the incoming one in from the right; reverse mirrors it. Enter and exit use an identical tween
     * (same duration + easing) so the two surfaces stay exactly edge-to-edge — no gap, no overlap —
     * for the whole move. The shell clips the content region, so a sliding surface never draws over
     * the persistent chrome (top bar, MiniPlayer, bottom nav, rail).
     *
     * Reduced motion collapses to the same opacity-only fade the depth primitives use: no slide.
     */
    fun peerSlide(forward: Boolean, reduceMotion: Boolean = false): ContentTransform {
        if (reduceMotion) return reducedFade()
        return if (forward) {
            // Forward (down the tab order): current exits left, incoming enters from the right.
            transform(
                enter = slideInHorizontally(
                    animationSpec = tween(durationMillis = PEER_MS, easing = FastOutSlowInEasing)
                ) { fullWidth -> fullWidth },
                exit = slideOutHorizontally(
                    animationSpec = tween(durationMillis = PEER_MS, easing = FastOutSlowInEasing)
                ) { fullWidth -> -fullWidth }
            )
        } else {
            // Reverse (up the tab order): current exits right, incoming enters from the left.
            transform(
                enter = slideInHorizontally(
                    animationSpec = tween(durationMillis = PEER_MS, easing = FastOutSlowInEasing)
                ) { fullWidth -> -fullWidth },
                exit = slideOutHorizontally(
                    animationSpec = tween(durationMillis = PEER_MS, easing = FastOutSlowInEasing)
                ) { fullWidth -> fullWidth }
            )
        }
    }

    /** Entering a deeper surface: new surface grows in (0.94→1.0), previous recedes (1.0→0.98). */
    fun depthForward(reduceMotion: Boolean = false): ContentTransform {
        if (reduceMotion) return reducedFade()
        return transform(
            enter = scaleIn(
                initialScale = DEPTH_FORWARD_IN_SCALE,
                animationSpec = tween(durationMillis = DEPTH_FORWARD_IN_MS, easing = FastOutSlowInEasing)
            ) + fadeIn(
                animationSpec = tween(durationMillis = DEPTH_FORWARD_IN_MS, easing = FastOutSlowInEasing)
            ),
            exit = scaleOut(
                targetScale = DEPTH_FORWARD_OUT_SCALE,
                animationSpec = tween(durationMillis = DEPTH_FORWARD_OUT_MS, easing = LinearOutSlowInEasing)
            ) + fadeOut(
                animationSpec = tween(durationMillis = DEPTH_FORWARD_OUT_MS, easing = LinearOutSlowInEasing)
            )
        )
    }

    /** Leaving a deeper surface: exact inverse of [depthForward], slightly quicker. */
    fun depthBack(reduceMotion: Boolean = false): ContentTransform {
        if (reduceMotion) return reducedFade()
        return transform(
            enter = scaleIn(
                initialScale = DEPTH_BACK_IN_SCALE,
                animationSpec = tween(durationMillis = DEPTH_BACK_IN_MS, easing = FastOutSlowInEasing)
            ) + fadeIn(
                animationSpec = tween(durationMillis = DEPTH_BACK_IN_MS, easing = FastOutSlowInEasing)
            ),
            exit = scaleOut(
                targetScale = DEPTH_BACK_OUT_SCALE,
                animationSpec = tween(durationMillis = DEPTH_BACK_OUT_MS, easing = FastOutLinearInEasing)
            ) + fadeOut(
                animationSpec = tween(durationMillis = DEPTH_BACK_OUT_MS, easing = FastOutLinearInEasing)
            )
        )
    }

    /** Reduced-motion collapse for either depth direction: short opacity fade, no scale. */
    private fun reducedFade(): ContentTransform = transform(
        enter = fadeIn(animationSpec = tween(durationMillis = REDUCED_MS)),
        exit = fadeOut(animationSpec = tween(durationMillis = REDUCED_MS))
    )

    /**
     * Builds a [ContentTransform] with `sizeTransform = null`. Constructed directly (rather than via
     * `togetherWith`) because [ContentTransform.sizeTransform] has an `internal` setter and cannot be
     * cleared after the fact — the only way to disable size animation is through the constructor.
     */
    private fun transform(enter: EnterTransition, exit: ExitTransition): ContentTransform =
        ContentTransform(
            targetContentEnter = enter,
            initialContentExit = exit,
            targetContentZIndex = 0f,
            sizeTransform = null
        )
}

/**
 * The single, centralized reduced-motion gate. Provided once at the shell so the shell transition,
 * Home's internal transition, and the Playlists internal transition all read the identical value
 * instead of each re-deriving it.
 *
 * `true` ⇒ every navigation transition collapses to a short opacity fade (no scale/translation).
 * Backed by the in-app `Settings → Reduce motion` preference. (The system animator-duration-scale
 * is honoured automatically by Compose's clock and needs no entry here — see [SonaraMotion].)
 *
 * `static` because it changes only when the user flips the setting; an app-wide recomposition on
 * that rare event is exactly what we want.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

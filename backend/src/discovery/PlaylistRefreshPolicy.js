'use strict';

/**
 * PlaylistRefreshPolicy.js — decides WHEN a curated playlist may be regenerated.
 *
 * WHY THIS EXISTS
 * ---------------
 * Every playlist definition has always declared `updateIntervalDays` (documented in
 * PlaylistDefinitions.js as "update cadence in days"), but until now NOTHING read it:
 * a grep found only a type assertion in tests. Because `generateCuratedPlaylist()`
 * was otherwise a pure function of `(definition, upstream results)`, a playlist was
 * regenerated — byte-identically — every time the 1-hour `_playlistCache` entry
 * expired. So the declared cadence was inert, and the only thing that could ever
 * change a playlist was upstream search ranking drifting on its own.
 *
 * Measured consequence (controlled A→B catalogue experiment): with a STABLE upstream
 * catalogue, regeneration is bit-for-bit identical forever; when the catalogue gains
 * new releases the playlists absorb them immediately and uncontrolled (27.3% of all
 * slots became brand-new releases). Both are wrong for a CURATED product: `Retro
 * Bollywood` (declared 14 days) must not chase releases at the same rate as
 * `Trending Now` (declared 2 days).
 *
 * This module turns the declared cadence into the real, single source of truth for
 * refresh eligibility. It is deliberately a PURE decision function — no timers, no
 * I/O, no state — so it stays trivially testable and cannot itself cause request
 * amplification.
 *
 * WHAT IT DOES NOT DO
 * -------------------
 * It does not change WHAT is selected. Candidate discovery, eligibility, canonical
 * dedup, artist diversity caps, de-clustering and QA metrics are untouched.
 */

/** Used only when a definition omits or malforms `updateIntervalDays`. */
const DEFAULT_INTERVAL_DAYS = 7;

/** A definition may not declare a cadence faster than this, whatever it says. */
const MIN_INTERVAL_DAYS = 1;

/** Largest cadence whose duration still fits in a JavaScript safe integer. */
const MS_PER_DAY = 24 * 60 * 60 * 1000;
const MAX_INTERVAL_DAYS = Math.floor(Number.MAX_SAFE_INTEGER / MS_PER_DAY);

/**
 * Resolves a definition's declared refresh interval in days.
 *
 * Falls back to [DEFAULT_INTERVAL_DAYS] for a missing, non-numeric or non-positive
 * value, so a malformed definition can never make a playlist refresh on every request.
 *
 * @param {object} definition
 * @returns {number} Interval in whole days, >= [MIN_INTERVAL_DAYS].
 */
function resolveIntervalDays(definition) {
  let raw;
  try {
    raw = Number(definition && definition.updateIntervalDays);
  } catch (_error) {
    return DEFAULT_INTERVAL_DAYS;
  }
  if (!Number.isFinite(raw) || raw < MIN_INTERVAL_DAYS || raw > MAX_INTERVAL_DAYS) {
    return DEFAULT_INTERVAL_DAYS;
  }
  return Math.floor(raw);
}

/**
 * The cadence "era" a playlist is currently in.
 *
 * Dividing wall-clock time by the declared interval yields a number that increments
 * exactly once per interval. This gives a deterministic, restart-safe, shared notion
 * of "which refresh generation are we in" without storing a schedule anywhere: every
 * process computes the same value from the same clock. It is exposed for tests and
 * diagnostics; refresh eligibility does not depend on it.
 *
 * @param {number} nowMs
 * @param {number} intervalDays
 * @returns {number}
 */
function epochFor(nowMs, intervalDays) {
  const intervalMs = Math.max(1, intervalDays) * MS_PER_DAY;
  return Math.floor(nowMs / intervalMs);
}

/**
 * Decides whether a playlist's cached content has reached the end of its declared
 * cadence and may therefore be regenerated.
 *
 * Semantics:
 *  - no recorded generation time → due (first ever generation, and self-healing for
 *    a cache entry that predates this policy);
 *  - an unparseable or future timestamp → due, so a clock change can never wedge a
 *    playlist out of refreshing permanently;
 *  - otherwise due once `now - generatedAtMs >= intervalDays`.
 *
 * @param {object} args
 * @param {object} args.definition
 * @param {number|undefined|null} args.generatedAtMs - when the cached content was built.
 * @param {number} args.nowMs
 * @returns {{ due: boolean, intervalDays: number, ageMs: number, reason: string }}
 */
function shouldRefresh({ definition, generatedAtMs, nowMs }) {
  const intervalDays = resolveIntervalDays(definition);
  const intervalMs = intervalDays * MS_PER_DAY;

  if (!Number.isFinite(generatedAtMs)) {
    return { due: true, intervalDays, ageMs: 0, reason: 'never_generated' };
  }

  const ageMs = nowMs - generatedAtMs;
  if (!Number.isFinite(ageMs) || ageMs < 0) {
    return { due: true, intervalDays, ageMs: 0, reason: 'clock_moved_backwards' };
  }
  if (ageMs >= intervalMs) {
    return { due: true, intervalDays, ageMs, reason: 'cadence_elapsed' };
  }
  return { due: false, intervalDays, ageMs, reason: 'within_cadence' };
}

module.exports = {
  shouldRefresh,
  resolveIntervalDays,
  epochFor,
  DEFAULT_INTERVAL_DAYS,
  MIN_INTERVAL_DAYS,
  MAX_INTERVAL_DAYS,
};

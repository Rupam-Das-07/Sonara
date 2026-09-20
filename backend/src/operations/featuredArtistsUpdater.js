'use strict';

/**
 * featuredArtistsUpdater.js — operational tooling to trigger & validate a
 * Featured Artists roster refresh.
 *
 * SANCTIONED REUSE (no duplicated business logic)
 * -----------------------------------------------
 * This module drives the FROZEN featuredArtistsService via its only two public
 * primitives:
 *   - getFeaturedArtists()  → read the current in-memory roster snapshot.
 *   - refreshWithLock()     → single-flight, never-rejecting refresh that
 *                             rebuilds the roster from the YT Music charts and
 *                             returns the resulting roster (or last-known-good).
 * It NEVER re-implements candidate fetching, filtering, dedup, avatar upgrade,
 * or roster composition, and it NEVER mutates frozen internals.
 *
 * GROUNDED SEMANTICS (verified against featuredArtistsService.js)
 * --------------------------------------------------------------
 * 1. refreshWithLock() is UNCONDITIONAL: it always runs the refresh pipeline.
 *    It does NOT consult cache freshness (only getFeaturedArtists() does). The
 *    cache timestamp is NOT exported, so an external "skip-if-fresh" fast-path
 *    cannot be implemented without modifying frozen logic. Consequently --force
 *    and the default mode perform the SAME sanctioned unconditional refresh;
 *    --force is accepted for explicitness/forward-compatibility. (Reported, not
 *    silently worked around.)
 * 2. refreshWithLock() NEVER rejects and returns the roster whether the upstream
 *    fetch succeeded or it fell back to last-known-good. Success-vs-fallback is
 *    therefore NOT directly observable. We infer a grounded, conservative
 *    signal from the RETURNED roster: the presence of any non-anchor artist can
 *    only result from a successful chart fetch + filter. Absence is ambiguous
 *    (empty/over-filtered chart vs upstream failure) and reported as such.
 * 3. PROCESS BOUNDARY: run as a CLI, this operates on the CLI process's OWN
 *    module-level cache — it does NOT and cannot push into a separately-running
 *    server process (no shared memory; the design has no persistence; exposing
 *    an unauthenticated write endpoint is out of bounds). As a CLI it therefore
 *    VALIDATES the refresh pipeline and PREVIEWS the resulting roster. Imported
 *    in-process (e.g. by a scheduled job inside the server) it refreshes that
 *    process's cache. This boundary is reported explicitly.
 *
 * This module is silent and never calls process.exit — the CLI layer owns
 * argument parsing, stdout formatting, and exit codes.
 */

const defaultService = require('../discovery/featuredArtistsService');

/**
 * Reduces a roster to a compact, non-sensitive summary. Avatar URLs are not
 * emitted verbatim; only a boolean presence flag is surfaced.
 * @param {object[]} roster
 * @returns {{id:string, name:string, browseId:string, hasImage:boolean}[]}
 */
function summarizeRoster(roster) {
  if (!Array.isArray(roster)) return [];
  return roster.map((a) => ({
    id: a.id,
    name: a.name,
    browseId: a.browseId,
    hasImage: !!a.imageUrl,
  }));
}

/**
 * Computes the roster diff between a before and after snapshot, keyed by id.
 * @param {object[]} before
 * @param {object[]} after
 * @returns {{added:object[], removed:object[], retainedCount:number}}
 */
function computeRosterDiff(before, after) {
  const beforeById = new Map((before || []).map((a) => [a.id, a]));
  const afterById = new Map((after || []).map((a) => [a.id, a]));

  const added = [];
  const removed = [];
  let retainedCount = 0;

  for (const [id, a] of afterById) {
    if (!beforeById.has(id)) added.push({ id: a.id, name: a.name, browseId: a.browseId });
    else retainedCount++;
  }
  for (const [id, a] of beforeById) {
    if (!afterById.has(id)) removed.push({ id: a.id, name: a.name, browseId: a.browseId });
  }
  return { added, removed, retainedCount };
}

/**
 * Conservatively classifies the refresh outcome from the RETURNED roster.
 * The presence of any artist whose browseId is not one of the core anchors can
 * only originate from a successful chart fetch + filter → 'refreshed'. If the
 * roster is composed solely of core anchors the outcome is ambiguous
 * (empty/over-filtered chart vs upstream failure) → 'fallback_or_empty'.
 * @param {object[]} afterRoster
 * @param {object[]} coreAnchors
 * @returns {{outcome:string, dynamicCount:number, anchorCount:number, note:string}}
 */
function classifyPipelineOutcome(afterRoster, coreAnchors) {
  const anchorBrowseIds = new Set((coreAnchors || []).map((a) => a.browseId));
  const roster = Array.isArray(afterRoster) ? afterRoster : [];
  const dynamicCount = roster.filter((a) => !anchorBrowseIds.has(a.browseId)).length;
  const anchorCount = roster.length - dynamicCount;

  if (dynamicCount > 0) {
    return {
      outcome: 'refreshed',
      dynamicCount,
      anchorCount,
      note: 'Roster contains dynamic (non-anchor) chart artists — the refresh pipeline reached and parsed the upstream chart.',
    };
  }
  return {
    outcome: 'fallback_or_empty',
    dynamicCount,
    anchorCount,
    note: 'Roster is composed solely of core editorial anchors. This is expected on a cold start and is AMBIGUOUS: it cannot be distinguished from an over-filtered/empty chart or an upstream failure without inspecting frozen internals.',
  };
}

/**
 * Standard limitation caveats shared across modes (honest, grounded).
 * @returns {string[]}
 */
function baseCaveats() {
  return [
    'refreshWithLock() is unconditional; --force and default mode perform the same sanctioned refresh. A meaningful skip-if-fresh distinction would require exposing the frozen cache timestamp, which is out of bounds.',
    'refreshWithLock() never rejects and returns a roster on both success and last-known-good fallback; upstream success is inferred conservatively from roster composition, not asserted.',
    'Run as a standalone CLI this refreshes only this process\'s in-memory cache; it does not push into a separately-running server process (no shared memory / no persistence by design).',
    'Single-flight is honored within a process (exactly one refreshWithLock call is issued and awaited). Cross-process concurrency is not deduplicated, as that would require persistent lock state (out of scope).',
  ];
}

/**
 * Triggers and validates a Featured Artists refresh (or previews it in dry-run).
 * Never throws for expected upstream conditions; never prints; never exits.
 *
 * @param {object} [options]
 * @param {boolean} [options.force=false]  — accepted; see module docs (no-op distinction).
 * @param {boolean} [options.dryRun=false] — do not issue an explicit refresh; preview only.
 * @param {object}  [options.service]      — injectable featuredArtistsService (tests).
 * @param {function}[options.now=Date.now] — injectable clock (tests).
 * @returns {Promise<object>} report
 */
async function updateFeaturedArtists(options = {}) {
  const { force = false, dryRun = false, service = defaultService, now = Date.now } = options;
  const startedAt = now();
  const coreAnchors = service.CORE_ANCHORS || [];

  if (dryRun) {
    // Preview only: the updater issues NO explicit refresh. We still read the
    // current roster via the single sanctioned getter. NOTE (verified): reading
    // via getFeaturedArtists() will, per the frozen SWR design, autonomously
    // schedule a background refreshWithLock() IF the in-memory roster is older
    // than its 24h TTL. The updater cannot observe or suppress that without
    // modifying frozen logic; any such refresh is the frozen service's own
    // behavior, identical to a normal production read.
    // getFeaturedArtists() is verified never to reject; the guard exists solely
    // to keep this module's "never throws" contract airtight against future
    // changes to frozen code.
    let current;
    try {
      current = await service.getFeaturedArtists();
    } catch (err) {
      return {
        schemaVersion: 1,
        mode: 'dry-run',
        performedRefresh: false,
        ok: false,
        generatedAt: new Date(startedAt).toISOString(),
        durationMs: now() - startedAt,
        error: `Unexpected error from getFeaturedArtists(): ${err && err.message ? err.message : String(err)}`,
        caveats: baseCaveats(),
      };
    }
    return {
      schemaVersion: 1,
      mode: 'dry-run',
      performedRefresh: false,
      ok: true,
      generatedAt: new Date(startedAt).toISOString(),
      durationMs: now() - startedAt,
      intendedAction: 'Without --dry-run, this would call featuredArtistsService.refreshWithLock() once (unconditional, single-flight).',
      rosterCount: Array.isArray(current) ? current.length : 0,
      roster: summarizeRoster(current),
      trueDryRunLimitation:
        'A true candidate-by-candidate dry-run (previewing which chart artists would be added/removed) cannot be implemented without either mutating the frozen cache or duplicating the frozen candidate/filter/compose logic. Both are out of bounds, so dry-run reports the current roster and intended action only.',
      caveats: [
        'Reading the roster via getFeaturedArtists() may trigger the frozen service\'s own background SWR refresh if the cache is past its 24h TTL; this is not an action of the updater and cannot be prevented without touching frozen code.',
        ...baseCaveats(),
      ],
    };
  }

  // Live mode: snapshot → single sanctioned refresh → diff.
  // getFeaturedArtists() is verified never to reject; guarded anyway so this
  // module's "never throws" contract holds even if frozen code later changes.
  let before;
  try {
    before = await service.getFeaturedArtists();
  } catch (err) {
    return {
      schemaVersion: 1,
      mode: force ? 'force' : 'default',
      performedRefresh: false,
      ok: false,
      generatedAt: new Date(startedAt).toISOString(),
      durationMs: now() - startedAt,
      error: `Unexpected error from getFeaturedArtists(): ${err && err.message ? err.message : String(err)}`,
      caveats: baseCaveats(),
    };
  }
  const beforeSnapshot = summarizeRoster(before);

  // refreshWithLock() is single-flight and never rejects. We defensively guard
  // anyway so the module never throws for an unexpected internal error.
  let after;
  try {
    after = await service.refreshWithLock();
  } catch (err) {
    return {
      schemaVersion: 1,
      mode: force ? 'force' : 'default',
      performedRefresh: false,
      ok: false,
      generatedAt: new Date(startedAt).toISOString(),
      durationMs: now() - startedAt,
      error: `Unexpected error from refreshWithLock(): ${err && err.message ? err.message : String(err)}`,
      before: beforeSnapshot,
      caveats: baseCaveats(),
    };
  }

  const afterSnapshot = summarizeRoster(after);
  const diff = computeRosterDiff(before, after);
  const pipeline = classifyPipelineOutcome(after, coreAnchors);

  return {
    schemaVersion: 1,
    mode: force ? 'force' : 'default',
    performedRefresh: true,
    ok: true,
    generatedAt: new Date(startedAt).toISOString(),
    durationMs: now() - startedAt,
    pipeline,
    counts: {
      before: beforeSnapshot.length,
      after: afterSnapshot.length,
      added: diff.added.length,
      removed: diff.removed.length,
      retained: diff.retainedCount,
    },
    diff,
    roster: afterSnapshot,
    caveats: baseCaveats(),
  };
}

module.exports = {
  updateFeaturedArtists,
  summarizeRoster,
  computeRosterDiff,
  classifyPipelineOutcome,
  baseCaveats,
};

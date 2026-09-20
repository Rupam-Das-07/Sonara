#!/usr/bin/env node
'use strict';

/**
 * update-curated-playlists.js — CLI wrapper for the Curated Playlists updater.
 *
 * This is the ONLY layer permitted to parse arguments, format stdout, and call
 * process.exit. All logic lives in src/operations/curatedPlaylistsUpdater.js.
 *
 * USAGE
 *   node scripts/update-curated-playlists.js [--playlist=<id>] [--concurrency=<N>]
 *                                            [--dry-run] [--json] [--verbose]
 *
 * FLAGS
 *   --playlist=<id>    Regenerate a single playlist (validated against the frozen
 *                      catalog via getDefinitionById). Omit for the full catalog.
 *   --concurrency=<N>  Parallel generations per batch (default 2, bounded 1..8).
 *   --dry-run          Plan only. Performs NO generation because
 *                      generateCuratedPlaylist() mutates the in-memory cache.
 *   --json             Emit ONLY machine-readable JSON on stdout.
 *   --verbose          Human mode: also print per-playlist QA detail and caveats.
 *   --help             Print usage and exit 0.
 *
 * EXIT CODES
 *   0  completed (all targeted playlists generated, or dry-run plan produced)
 *   1  one or more playlists failed, unknown --playlist id, or bad arguments
 */

const { updateCuratedPlaylists } = require('../src/operations/curatedPlaylistsUpdater');

const USAGE = `Sonara Curated Playlists Updater

Usage:
  node scripts/update-curated-playlists.js [--playlist=<id>] [--concurrency=<N>] [--dry-run] [--json] [--verbose]

Flags:
  --playlist=<id>    Regenerate a single playlist (id validated against the frozen catalog).
  --concurrency=<N>  Parallel generations per batch (default 2, bounded 1..8).
  --dry-run          Plan only; performs NO generation (generateCuratedPlaylist mutates the cache).
  --json             Emit only machine-readable JSON on stdout.
  --verbose          Human mode: also print per-playlist QA detail and caveats.
  --help             Show this help and exit.

Exit codes: 0=completed, 1=failure/unknown id/bad arguments.`;

/**
 * Parses argv into options. Supports --flag and --flag=value forms.
 * @param {string[]} argv
 * @returns {{options?:object, error?:string, help?:boolean}}
 */
function parseArgs(argv) {
  const options = { playlistId: null, concurrency: 2, dryRun: false, json: false, verbose: false };

  for (const arg of argv) {
    if (arg === '--help' || arg === '-h') return { help: true };
    else if (arg === '--dry-run') options.dryRun = true;
    else if (arg === '--json') options.json = true;
    else if (arg === '--verbose') options.verbose = true;
    else if (arg.startsWith('--playlist=')) {
      const val = arg.slice('--playlist='.length);
      if (!val) return { error: 'Missing value for --playlist=<id>' };
      options.playlistId = val;
    } else if (arg.startsWith('--concurrency=')) {
      const raw = arg.slice('--concurrency='.length);
      if (!/^\d+$/.test(raw)) return { error: `Invalid --concurrency value: ${raw} (expected a positive integer)` };
      const n = Number.parseInt(raw, 10);
      if (n < 1 || n > 8) return { error: `--concurrency out of range: ${n} (allowed 1..8)` };
      options.concurrency = n;
    } else {
      return { error: `Unknown argument: ${arg}` };
    }
  }
  return { options };
}

/**
 * Runs `fn` with all console.* output redirected to stderr, reserving stdout for
 * the JSON payload. The frozen PlaylistService (and its QA logger) write to
 * console during generation; this keeps --json stdout strictly machine-readable.
 * @param {function} fn
 */
async function withStdoutReservedForJson(fn) {
  const original = { log: console.log, info: console.info, warn: console.warn, debug: console.debug, error: console.error };
  const toStderr = (...args) => process.stderr.write(args.map(String).join(' ') + '\n');
  console.log = toStderr; console.info = toStderr; console.warn = toStderr; console.debug = toStderr; console.error = toStderr;
  try {
    return await fn();
  } finally {
    Object.assign(console, original);
  }
}

/**
 * @param {object} report
 * @param {boolean} verbose
 */
function printHuman(report, verbose) {
  const lines = [];

  if (report.error) {
    lines.push(`Curated Playlists updater — ERROR: ${report.error}`);
    if (Array.isArray(report.validIds) && report.validIds.length) {
      lines.push(`  valid ids: ${report.validIds.join(', ')}`);
    }
    process.stdout.write(lines.join('\n') + '\n');
    return;
  }

  lines.push(`Curated Playlists updater — mode=${report.mode}`);
  lines.push(`  generated ${report.generatedAt} in ${report.durationMs}ms (concurrency=${report.concurrency})`);

  if (report.mode === 'dry-run') {
    lines.push(`  PLAN ONLY — no generation performed (${report.targetCount} playlists would be generated):`);
    for (const t of report.targets) lines.push(`    - ${t.id}  "${t.name}"  target=${t.targetSize}`);
    lines.push(`  ${report.mutationFinding}`);
  } else {
    const t = report.totals;
    lines.push(`  totals: playlists=${t.playlists}, succeeded=${t.succeeded}, failed=${t.failed}, underfilled=${t.underfilled}`);
    for (const r of report.results) {
      if (r.ok) {
        const uf = r.underfilled ? ' [UNDERFILLED]' : '';
        const src = r.qaSource === 'service' ? '' : ` (${r.qaSource})`;
        lines.push(`    ✓ ${r.id}  ${r.resolvedCount}/${r.targetSize}${uf}${src}`);
        if (verbose && r.qaMetrics) {
          lines.push(`        fill=${r.qaMetrics.fillPercentage}, uniqueArtists=${r.qaMetrics.uniquePrimaryArtists}, candidates=${r.qaMetrics.candidatesEvaluated}`);
        }
      } else {
        lines.push(`    ✗ ${r.id}  FAILED: ${r.error}`);
      }
    }
  }

  if (verbose && Array.isArray(report.caveats)) {
    lines.push('  caveats:');
    for (const c of report.caveats) lines.push(`    - ${c}`);
  }
  process.stdout.write(lines.join('\n') + '\n');
}

async function main() {
  const parsed = parseArgs(process.argv.slice(2));

  if (parsed.help) { process.stdout.write(USAGE + '\n'); process.exit(0); }
  if (parsed.error) { process.stderr.write(parsed.error + '\n\n' + USAGE + '\n'); process.exit(1); }

  const { options } = parsed;

  try {
    let report;
    if (options.json) {
      report = await withStdoutReservedForJson(() =>
        updateCuratedPlaylists({
          playlistId: options.playlistId,
          concurrency: options.concurrency,
          dryRun: options.dryRun,
        })
      );
      process.stdout.write(JSON.stringify(report) + '\n');
    } else {
      report = await updateCuratedPlaylists({
        playlistId: options.playlistId,
        concurrency: options.concurrency,
        dryRun: options.dryRun,
      });
      printHuman(report, options.verbose);
    }
    process.exit(report.ok === false ? 1 : 0);
  } catch (err) {
    const msg = `Curated Playlists updater failed: ${err && err.message ? err.message : String(err)}`;
    if (options.json) process.stdout.write(JSON.stringify({ schemaVersion: 1, ok: false, error: msg }) + '\n');
    else process.stderr.write(msg + '\n');
    process.exit(1);
  }
}

main();

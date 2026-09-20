#!/usr/bin/env node
'use strict';

/**
 * update-featured-artists.js — CLI wrapper for the Featured Artists updater.
 *
 * This is the ONLY layer permitted to parse arguments, format stdout, and call
 * process.exit. All logic lives in src/operations/featuredArtistsUpdater.js.
 *
 * USAGE
 *   node scripts/update-featured-artists.js [--force] [--dry-run] [--json] [--verbose]
 *
 * FLAGS
 *   --force     Accepted for explicitness. NOTE: the frozen refreshWithLock() is
 *               unconditional, so --force and default perform the same sanctioned
 *               refresh (see module docs / report caveats).
 *   --dry-run   Do not issue an explicit refresh; preview current roster + intent.
 *   --json      Emit ONLY machine-readable JSON on stdout.
 *   --verbose   Human mode: also print the diff detail and caveats.
 *   --help      Print usage and exit 0.
 *
 * EXIT CODES
 *   0  completed (refresh performed or dry-run preview)
 *   1  the updater hit an unexpected error, or bad arguments
 */

const { updateFeaturedArtists } = require('../src/operations/featuredArtistsUpdater');

const USAGE = `Sonara Featured Artists Updater

Usage:
  node scripts/update-featured-artists.js [--force] [--dry-run] [--json] [--verbose]

Flags:
  --force     Accepted; frozen refreshWithLock() is unconditional so this equals
              default behavior (reported as a caveat, not silently changed).
  --dry-run   Preview only: no explicit refresh is issued.
  --json      Emit only machine-readable JSON on stdout.
  --verbose   Human mode: also print diff detail and caveats.
  --help      Show this help and exit.

Exit codes: 0=completed, 1=error or bad arguments.`;

/**
 * @param {string[]} argv
 * @returns {{options?:object, error?:string, help?:boolean}}
 */
function parseArgs(argv) {
  const options = { force: false, dryRun: false, json: false, verbose: false };
  for (const arg of argv) {
    if (arg === '--force') options.force = true;
    else if (arg === '--dry-run') options.dryRun = true;
    else if (arg === '--json') options.json = true;
    else if (arg === '--verbose') options.verbose = true;
    else if (arg === '--help' || arg === '-h') return { help: true };
    else return { error: `Unknown argument: ${arg}` };
  }
  return { options };
}

/**
 * Runs `fn` with all console.* output redirected to stderr, reserving stdout
 * for the JSON payload (the frozen service logs via console during refresh).
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
  lines.push(`Featured Artists updater — mode=${report.mode}, refresh=${report.performedRefresh ? 'performed' : 'skipped'}`);
  lines.push(`  generated ${report.generatedAt} in ${report.durationMs}ms`);

  if (report.error) {
    lines.push(`  ERROR: ${report.error}`);
  }

  if (report.mode === 'dry-run') {
    lines.push(`  current roster: ${report.rosterCount} artists`);
    lines.push(`  intended action: ${report.intendedAction}`);
    for (const a of report.roster) lines.push(`    - ${a.name} (${a.id})${a.hasImage ? '' : ' [no image]'}`);
  } else if (report.performedRefresh) {
    lines.push(`  pipeline: ${report.pipeline.outcome} (dynamic=${report.pipeline.dynamicCount}, anchors=${report.pipeline.anchorCount})`);
    lines.push(`  counts: before=${report.counts.before}, after=${report.counts.after}, added=${report.counts.added}, removed=${report.counts.removed}, retained=${report.counts.retained}`);
    if (verbose) {
      if (report.diff.added.length) lines.push('  added:   ' + report.diff.added.map((a) => a.name).join(', '));
      if (report.diff.removed.length) lines.push('  removed: ' + report.diff.removed.map((a) => a.name).join(', '));
      lines.push('  roster:  ' + report.roster.map((a) => a.name).join(', '));
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
        updateFeaturedArtists({ force: options.force, dryRun: options.dryRun })
      );
      process.stdout.write(JSON.stringify(report) + '\n');
    } else {
      report = await updateFeaturedArtists({ force: options.force, dryRun: options.dryRun });
      printHuman(report, options.verbose);
    }
    process.exit(report.ok === false ? 1 : 0);
  } catch (err) {
    const msg = `Featured Artists updater failed: ${err && err.message ? err.message : String(err)}`;
    if (options.json) process.stdout.write(JSON.stringify({ schemaVersion: 1, ok: false, error: msg }) + '\n');
    else process.stderr.write(msg + '\n');
    process.exit(1);
  }
}

main();

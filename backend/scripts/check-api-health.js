#!/usr/bin/env node
'use strict';

/**
 * check-api-health.js — CLI wrapper for the Sonara API Health & Status Checker.
 *
 * This is the ONLY layer permitted to parse arguments, format stdout, and call
 * process.exit. All health logic lives in src/operations/healthChecker.js.
 *
 * USAGE
 *   node scripts/check-api-health.js [--external] [--timeout=<ms>] [--json] [--verbose]
 *
 * FLAGS
 *   --external        Also probe opt-in third-party upstreams (Tier 3).
 *   --timeout=<ms>    Per-probe timeout in milliseconds (default 5000, 1..600000).
 *   --json            Emit ONLY machine-readable JSON on stdout (no decorative logs).
 *   --verbose         In human mode, print per-check targets and notes.
 *   --help            Print usage and exit 0.
 *
 * EXIT CODES
 *   0  HEALTHY
 *   1  DEGRADED or NOT_CONFIGURED
 *   2  DOWN (or a fatal error running the checker / bad arguments)
 */

const {
  checkHealth,
  statusToExitCode,
  STATUS,
} = require('../src/operations/healthChecker');

const USAGE = `Sonara API Health Checker

Usage:
  node scripts/check-api-health.js [--external] [--timeout=<ms>] [--json] [--verbose]

Flags:
  --external       Probe opt-in third-party upstreams (Tier 3).
  --timeout=<ms>   Per-probe timeout in ms (default 5000, range 1..600000).
  --json           Emit only machine-readable JSON on stdout.
  --verbose        Human mode: also print targets and notes.
  --help           Show this help and exit.

Exit codes: 0=HEALTHY, 1=DEGRADED/NOT_CONFIGURED, 2=DOWN or error.`;

/**
 * Parses argv into options. Returns { options } or { error } (never throws).
 * @param {string[]} argv
 * @returns {{options?:object, error?:string, help?:boolean}}
 */
function parseArgs(argv) {
  const options = { includeExternal: false, timeoutMs: 5000, json: false, verbose: false };
  for (const arg of argv) {
    if (arg === '--external') options.includeExternal = true;
    else if (arg === '--json') options.json = true;
    else if (arg === '--verbose') options.verbose = true;
    else if (arg === '--help' || arg === '-h') return { help: true };
    else if (arg.startsWith('--timeout=')) {
      const raw = arg.slice('--timeout='.length);
      // Strict integer validation — argument is used only as a numeric timeout,
      // never interpolated into a shell/path, so there is no injection surface.
      if (!/^\d+$/.test(raw)) return { error: `Invalid --timeout value: "${raw}" (expected a positive integer).` };
      const ms = parseInt(raw, 10);
      if (!Number.isInteger(ms) || ms < 1 || ms > 600000) {
        return { error: `Invalid --timeout value: ${raw} (must be 1..600000).` };
      }
      options.timeoutMs = ms;
    } else {
      return { error: `Unknown argument: ${arg}` };
    }
  }
  return { options };
}

/**
 * Runs `fn` with all console.* output redirected to stderr, so that stdout can
 * be reserved exclusively for the JSON payload. Restores console afterward.
 * @param {function} fn
 * @returns {Promise<any>}
 */
async function withStdoutReservedForJson(fn) {
  const original = {
    log: console.log,
    info: console.info,
    warn: console.warn,
    debug: console.debug,
    error: console.error,
  };
  const toStderr = (...args) => process.stderr.write(args.map(String).join(' ') + '\n');
  console.log = toStderr;
  console.info = toStderr;
  console.warn = toStderr;
  console.debug = toStderr;
  console.error = toStderr;
  try {
    return await fn();
  } finally {
    console.log = original.log;
    console.info = original.info;
    console.warn = original.warn;
    console.debug = original.debug;
    console.error = original.error;
  }
}

const ICON = {
  [STATUS.HEALTHY]: 'OK  ',
  [STATUS.DEGRADED]: 'WARN',
  [STATUS.DOWN]: 'DOWN',
  [STATUS.NOT_CONFIGURED]: 'N/C ',
};

/**
 * Renders a human-readable report to stdout.
 * @param {object} report
 * @param {boolean} verbose
 */
function printHuman(report, verbose) {
  const lines = [];
  lines.push(`Sonara backend health: ${report.status}`);
  lines.push(`  generated ${report.generatedAt} in ${report.durationMs}ms` +
    (report.includeExternal ? ' (with external probes)' : ''));
  lines.push('');
  for (const c of report.checks) {
    let line = `  [${ICON[c.status] || c.status}] T${c.tier} ${c.label}: ${c.detail}`;
    lines.push(line);
    if (verbose && c.target) lines.push(`         target: ${c.target}`);
    if (verbose && c.note) lines.push(`         note:   ${c.note}`);
  }
  lines.push('');
  lines.push(
    `  summary: ${report.counts.healthy} healthy, ${report.counts.degraded} degraded, ` +
    `${report.counts.down} down, ${report.counts.notConfigured} not-configured`
  );
  process.stdout.write(lines.join('\n') + '\n');
}

async function main() {
  const parsed = parseArgs(process.argv.slice(2));

  if (parsed.help) {
    process.stdout.write(USAGE + '\n');
    process.exit(0);
  }
  if (parsed.error) {
    process.stderr.write(parsed.error + '\n\n' + USAGE + '\n');
    process.exit(2);
  }

  const { options } = parsed;

  try {
    let report;
    if (options.json) {
      // Reserve stdout strictly for the JSON payload.
      report = await withStdoutReservedForJson(() =>
        checkHealth({
          includeExternal: options.includeExternal,
          timeoutMs: options.timeoutMs,
        })
      );
      process.stdout.write(JSON.stringify(report) + '\n');
    } else {
      report = await checkHealth({
        includeExternal: options.includeExternal,
        timeoutMs: options.timeoutMs,
      });
      printHuman(report, options.verbose);
    }
    process.exit(statusToExitCode(report.status));
  } catch (err) {
    // A failure to even run the checker is treated as DOWN (exit 2).
    const msg = `Health checker failed to run: ${err && err.message ? err.message : String(err)}`;
    if (options.json) {
      process.stdout.write(JSON.stringify({ schemaVersion: 1, status: 'DOWN', error: msg }) + '\n');
    } else {
      process.stderr.write(msg + '\n');
    }
    process.exit(2);
  }
}

main();

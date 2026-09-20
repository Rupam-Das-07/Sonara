'use strict';

/**
 * httpJson.js — Minimal JSON-over-HTTP GET helper built on native fetch.
 *
 * WHY THIS EXISTS
 * ---------------
 * MusicBrainzService and ListenBrainzService both called `axios.get(...)` without
 * ever requiring axios, and axios is listed in neither package.json nor
 * node_modules. Every call therefore threw `ReferenceError: axios is not defined`.
 * Because both call sites sit inside try/catch blocks in the recommendation
 * engine, the failure surfaced as HTTP 200 with `{ success: false, tracks: [] }`
 * rather than as an obvious crash — which is why it went unnoticed.
 *
 * Rather than add a dependency, this uses native fetch, matching ytmusicProvider,
 * which already states "No axios dependency" and talks to the Python services
 * this way.
 *
 * Behaviours this normalizes, where fetch differs from axios:
 *  - fetch resolves on 4xx/5xx, so a non-OK status is converted into an Error.
 *  - fetch has no `params` option, so query strings are built via URLSearchParams.
 *  - fetch has no `timeout` option, so AbortSignal.timeout is used.
 *  - fetch requires an explicit .json() call to parse the body.
 */

/**
 * Performs a GET request and parses the JSON response body.
 *
 * @param {string} baseUrl - Origin plus any path prefix, without a trailing slash.
 * @param {string} path - Path beginning with '/'.
 * @param {object} [options]
 * @param {Record<string, string|number|boolean>} [options.params] - Query parameters. Null and undefined values are omitted.
 * @param {Record<string, string>} [options.headers] - Request headers.
 * @param {number} [options.timeoutMs] - Abort the request after this many milliseconds.
 * @returns {Promise<any>} The parsed JSON body.
 * @throws {Error} On timeout, network failure, non-2xx status, or unparseable JSON.
 *                 A `status` property carries the HTTP status when there was one.
 */
async function getJson(baseUrl, path, { params = {}, headers = {}, timeoutMs = 8000 } = {}) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) {
      search.append(key, String(value));
    }
  }

  const query = search.toString();
  const url = `${baseUrl}${path}${query ? `?${query}` : ''}`;

  const res = await fetch(url, {
    method: 'GET',
    headers,
    signal: AbortSignal.timeout(timeoutMs),
  });

  if (!res.ok) {
    // Deliberately reference the path rather than the full URL so query
    // parameters never reach logs or error messages.
    const err = new Error(`HTTP ${res.status} for ${path}`);
    err.status = res.status;
    throw err;
  }

  return res.json();
}

module.exports = { getJson };

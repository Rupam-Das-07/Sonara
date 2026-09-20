'use strict';

/**
 * importConcurrency.js — Bounded concurrency runner for batch operations.
 *
 * Runs an asynchronous worker function over an array of items with a fixed
 * concurrency limit, preserving input order in the results array.
 * Replaces Promise.all() over unbounded collections without requiring external npm packages.
 */

/**
 * Maps items through an async worker function with bounded concurrency.
 *
 * @template T, R
 * @param {T[]} items
 * @param {number} concurrencyLimit - Maximum concurrent promises (e.g. 6)
 * @param {function(T, number): Promise<R>} workerFn
 * @returns {Promise<R[]>}
 */
async function mapConcurrent(items, concurrencyLimit, workerFn) {
  if (!Array.isArray(items) || items.length === 0) {
    return [];
  }

  const limit = Math.max(1, Math.min(concurrencyLimit || 6, items.length));
  const results = new Array(items.length);
  let nextIndex = 0;

  async function worker() {
    while (nextIndex < items.length) {
      const currentIndex = nextIndex++;
      results[currentIndex] = await workerFn(items[currentIndex], currentIndex);
    }
  }

  const workers = [];
  for (let i = 0; i < limit; i++) {
    workers.push(worker());
  }

  await Promise.all(workers);
  return results;
}

module.exports = { mapConcurrent };

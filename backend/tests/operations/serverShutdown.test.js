'use strict';

/**
 * serverShutdown.test.js — Unit tests for F-04 graceful server shutdown.
 *
 * Tests the graceful shutdown lifecycle handler exported by src/index.js:
 *   1. SIGTERM triggers graceful shutdown (server.close + store flush + exit 0).
 *   2. SIGINT triggers graceful shutdown.
 *   3. Idempotency: duplicate/concurrent signals do not re-run shutdown.
 *   4. HTTP server.close error is safely absorbed without unhandled rejection.
 *   5. identityStore.forceFlush rejection is safely absorbed without unhandled rejection.
 *   6. Forced exit with code 1 occurs if server.close hangs beyond timeoutMs.
 *   7. Module export integrity: app and createShutdownHandler are exported cleanly.
 */

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');
const { createShutdownHandler } = require('../../src/index');

// Minimal mock logger that captures output without polluting test logs
function createMockLogger() {
  const logs = [];
  return {
    info:  (...args) => logs.push({ level: 'info', args }),
    warn:  (...args) => logs.push({ level: 'warn', args }),
    error: (...args) => logs.push({ level: 'error', args }),
    logs,
  };
}

describe('F-04: Graceful Server Shutdown Lifecycle', () => {

  test('1. SIGTERM invokes server.close, identityStore.forceFlush, and exits 0', async () => {
    let serverClosed = false;
    let idleClosed = false;
    let flushCalled = false;
    let exitCode = null;

    const mockServer = {
      close(cb) {
        serverClosed = true;
        cb(null);
      },
      closeIdleConnections() {
        idleClosed = true;
      },
    };

    const mockStore = {
      async forceFlush() {
        flushCalled = true;
      },
    };

    const mockLogger = createMockLogger();
    const shutdown = createShutdownHandler({
      server: mockServer,
      store: mockStore,
      log: mockLogger,
      exitFn: (code) => { exitCode = code; },
      timeoutMs: 5000,
    });

    await shutdown('SIGTERM');

    assert.equal(serverClosed, true, 'server.close() must be invoked');
    assert.equal(idleClosed, true, 'server.closeIdleConnections() must be invoked');
    assert.equal(flushCalled, true, 'identityStore.forceFlush() must be invoked');
    assert.equal(exitCode, 0, 'process must exit with status code 0');
  });

  test('2. SIGINT invokes server.close, identityStore.forceFlush, and exits 0', async () => {
    let serverClosed = false;
    let flushCalled = false;
    let exitCode = null;

    const mockServer = {
      close(cb) {
        serverClosed = true;
        cb(null);
      },
    };

    const mockStore = {
      async forceFlush() {
        flushCalled = true;
      },
    };

    const mockLogger = createMockLogger();
    const shutdown = createShutdownHandler({
      server: mockServer,
      store: mockStore,
      log: mockLogger,
      exitFn: (code) => { exitCode = code; },
      timeoutMs: 5000,
    });

    await shutdown('SIGINT');

    assert.equal(serverClosed, true, 'server.close() must be invoked on SIGINT');
    assert.equal(flushCalled, true, 'identityStore.forceFlush() must be invoked on SIGINT');
    assert.equal(exitCode, 0, 'process must exit with status code 0 on SIGINT');
  });

  test('3. Idempotency: duplicate signals do not run shutdown sequence twice', async () => {
    let serverCloseCount = 0;
    let flushCount = 0;
    let exitCount = 0;

    const mockServer = {
      close(cb) {
        serverCloseCount++;
        setTimeout(cb, 10);
      },
    };

    const mockStore = {
      async forceFlush() {
        flushCount++;
      },
    };

    const mockLogger = createMockLogger();
    const shutdown = createShutdownHandler({
      server: mockServer,
      store: mockStore,
      log: mockLogger,
      exitFn: () => { exitCount++; },
      timeoutMs: 5000,
    });

    // Fire both SIGTERM and SIGINT concurrently
    const p1 = shutdown('SIGTERM');
    const p2 = shutdown('SIGINT');
    await Promise.all([p1, p2]);

    assert.equal(serverCloseCount, 1, 'server.close() must only be called once');
    assert.equal(flushCount, 1, 'identityStore.forceFlush() must only be called once');
    assert.equal(exitCount, 1, 'exitFn must only be called once');

    // Confirm duplicate signal warning was logged
    const warnLogs = mockLogger.logs.filter((l) => l.level === 'warn');
    assert.ok(warnLogs.length >= 1, 'warning must be logged for duplicate signal');
  });

  test('4. Server.close error is handled gracefully without crashing or throwing', async () => {
    let flushCalled = false;
    let exitCode = null;

    const mockServer = {
      close(cb) {
        cb(new Error('Simulated socket close failure'));
      },
    };

    const mockStore = {
      async forceFlush() {
        flushCalled = true;
      },
    };

    const mockLogger = createMockLogger();
    const shutdown = createShutdownHandler({
      server: mockServer,
      store: mockStore,
      log: mockLogger,
      exitFn: (code) => { exitCode = code; },
      timeoutMs: 5000,
    });

    await shutdown('SIGTERM');

    assert.equal(flushCalled, true, 'store flush must still proceed even if server.close has error');
    assert.equal(exitCode, 0, 'exit code must still be 0 after non-fatal close error');
    const errorLogs = mockLogger.logs.filter((l) => l.level === 'error');
    assert.ok(errorLogs.some((l) => l.args[0].includes('closing HTTP server')), 'close error must be logged');
  });

  test('5. identityStore.forceFlush rejection is caught without unhandled rejection', async () => {
    let serverClosed = false;
    let exitCode = null;

    const mockServer = {
      close(cb) {
        serverClosed = true;
        cb(null);
      },
    };

    const mockStore = {
      async forceFlush() {
        throw new Error('Disk I/O failure during flush');
      },
    };

    const mockLogger = createMockLogger();
    const shutdown = createShutdownHandler({
      server: mockServer,
      store: mockStore,
      log: mockLogger,
      exitFn: (code) => { exitCode = code; },
      timeoutMs: 5000,
    });

    // Should complete cleanly without rejecting
    await shutdown('SIGTERM');

    assert.equal(serverClosed, true);
    assert.equal(exitCode, 0);
    const errorLogs = mockLogger.logs.filter((l) => l.level === 'error');
    assert.ok(errorLogs.some((l) => l.args[0].includes('IdentityStore')), 'flush error must be logged');
  });

  test('6. Shutdown timeout forces exit with code 1 if server hangs', async () => {
    let exitCode = null;

    // Server that never completes its close callback (simulating hanging connection)
    const hangingServer = {
      close(cb) {
        // cb is deliberately never called
      },
    };

    const mockStore = {
      async forceFlush() {},
    };

    const mockLogger = createMockLogger();
    const shutdown = createShutdownHandler({
      server: hangingServer,
      store: mockStore,
      log: mockLogger,
      exitFn: (code) => { exitCode = code; },
      timeoutMs: 50, // Short timeout for test
    });

    // Initiate shutdown (will block waiting for server.close)
    shutdown('SIGTERM');

    // Wait for timeoutMs to fire
    await new Promise((resolve) => setTimeout(resolve, 100));

    assert.equal(exitCode, 1, 'must forcefully exit with code 1 on timeout');
    const errorLogs = mockLogger.logs.filter((l) => l.level === 'error');
    assert.ok(errorLogs.some((l) => l.args[0].includes('timed out')), 'timeout error must be logged');
  });

  test('7. Works safely when server or store are not supplied (null-safe)', async () => {
    let exitCode = null;
    const mockLogger = createMockLogger();

    const shutdown = createShutdownHandler({
      server: null,
      store: null,
      log: mockLogger,
      exitFn: (code) => { exitCode = code; },
      timeoutMs: 1000,
    });

    await shutdown('SIGTERM');
    assert.equal(exitCode, 0, 'must exit cleanly even if server or store are null');
  });
});

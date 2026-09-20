'use strict';

/**
 * asyncHandler.js — Wraps async Express route handlers so their rejections
 * reach errorMiddleware instead of killing the process.
 *
 * WHY THIS EXISTS
 * ---------------
 * Express 4 does not understand promises. When an `async` route handler
 * rejects — or throws synchronously inside its body, which an async function
 * converts into a rejection — Express never sees the error. The rejection goes
 * unhandled, and since Node 15 the default `--unhandled-rejections=throw`
 * behaviour terminates the process. This backend declares `engines: node >= 20`,
 * so a single throwing async route can take the whole server down.
 *
 * That was not hypothetical: five route handlers called `Errors.badRequest(...)`,
 * which does not exist on the `Errors` factory, producing a synchronous
 * TypeError inside an async handler. Any request with a malformed videoId or a
 * missing title/artist query parameter would kill the backend.
 *
 * Wrapping a handler funnels every failure mode into `next(err)`, which reaches
 * errorMiddleware and produces a sanitized JSON response.
 *
 * Usage:
 *   router.get('/thing/:id', asyncHandler(async (req, res) => { ... }));
 *
 * @param {(req: import('express').Request, res: import('express').Response, next: import('express').NextFunction) => any} handler
 * @returns {(req: import('express').Request, res: import('express').Response, next: import('express').NextFunction) => void}
 */
function asyncHandler(handler) {
  return function wrappedHandler(req, res, next) {
    // Promise.resolve also captures synchronous throws from non-async handlers.
    Promise.resolve()
      .then(() => handler(req, res, next))
      .catch(next);
  };
}

module.exports = asyncHandler;

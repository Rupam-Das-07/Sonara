const test = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const root = path.resolve(__dirname, '..', '..');
const launcher = path.join(root, 'docker', 'start.sh');
const bashCandidates = [
  'C:/Program Files/Git/bin/bash.exe',
  'C:/Program Files (x86)/Git/bin/bash.exe',
];
const bash = bashCandidates.find((candidate) => fs.existsSync(candidate));

function run(script, env = {}) {
  return execFileSync(bash, ['-c', script], {
    cwd: root,
    env: { ...process.env, ...env },
    encoding: 'utf8',
    timeout: 15000,
  });
}

test('launcher rejects invalid startup timeout before launching children', { skip: !bash }, () => {
  for (const value of ['0', '-1', '1.5', '99999', 'abc']) {
    assert.throws(() => execFileSync(bash, [launcher], {
      cwd: root, env: { ...process.env, STARTUP_TIMEOUT_SECONDS: value },
      encoding: 'utf8', timeout: 3000,
    }), new RegExp('STARTUP_TIMEOUT_SECONDS'));
  }
});

test('readiness harness covers crash, recovery and deadline expiry', { skip: !bash }, () => {
  const script = String.raw`
set -u
STARTUP_TIMEOUT_SECONDS=2
wait_for_http() {
  local pid="$1" label="$2" url="$3" deadline=$((SECONDS + STARTUP_TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    kill -0 "$pid" 2>/dev/null || return 1
    curl --fail --silent "$url" >/dev/null 2>&1 && return 0
    sleep .1
  done
  return 1
}
curl() { [ "$READY" = yes ]; }
(sleep 5) & p=$!; READY=no; wait_for_http "$p" x x && exit 10 || :; kill "$p" 2>/dev/null || :; wait "$p" 2>/dev/null || :
(exit 7) & p=$!; wait_for_http "$p" x x && exit 11 || :; wait "$p" 2>/dev/null || :
calls=0; curl() { calls=$((calls + 1)); [ "$calls" -ge 2 ]; }; (sleep 5) & p=$!; wait_for_http "$p" x x || exit 12; kill "$p" 2>/dev/null || :; wait "$p" 2>/dev/null || :
`;
  run(script);
});

test('shutdown bounds SIGTERM-resistant children and only tracks owned PIDs', { skip: !bash }, () => {
  const script = String.raw`
set -u
PID_YTMUSIC= PID_AUDIO= PID_NODE=""
shutdown() {
  local pid alive deadline=$((SECONDS + 1)); trap '' TERM INT
  for pid in "$PID_YTMUSIC" "$PID_AUDIO" "$PID_NODE"; do [ -n "$pid" ] && kill -TERM "$pid" 2>/dev/null || :; done
  while (( SECONDS < deadline )); do
    alive=0; for pid in "$PID_YTMUSIC" "$PID_AUDIO" "$PID_NODE"; do [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && alive=1 || :; done
    [ "$alive" = 0 ] && return 0; sleep .1
  done
  for pid in "$PID_YTMUSIC" "$PID_AUDIO" "$PID_NODE"; do [ -n "$pid" ] && kill -KILL "$pid" 2>/dev/null || :; done
}
(trap '' TERM; sleep 10) & PID_YTMUSIC=$!; started=$SECONDS; shutdown SIGTERM; elapsed=$((SECONDS-started)); [ "$elapsed" -le 2 ]
`;
  run(script);
});

'use strict';

// Offline boundary stubs are installed per test and restored only after all work drains.
// The actual definitions, eligibility engine, refresh policy and cache stay in use.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { setImmediate: nextTurn } = require('node:timers/promises');
const { getDefinitionById } = require('../../src/discovery/PlaylistDefinitions');
const realEligibility = require('../../src/discovery/EligibilityEngine');

const paths = Object.fromEntries(Object.entries({
  service: '../../src/discovery/PlaylistService',
  provider: '../../src/search/ytmusicProvider',
  feed: '../../src/discovery/discoveryFeedService',
  config: '../../src/config/env',
  logger: '../../src/utils/logger',
  eligibility: '../../src/discovery/EligibilityEngine',
}).map(([key, path]) => [key, require.resolve(path)]));
const BUDGET = 80;
const DAY = 86400000;
const track = (id, overrides = {}) => ({
  videoId: id, title: `Song ${id}`, artist: `Artist ${id}`,
  duration: 200, year: 2026, thumbnails: [], ...overrides,
});
const tracks = (prefix, count) => Array.from({ length: count }, (_, i) => track(`${prefix}${i}`));
const ids = (items) => items.map((item) => item.videoId);

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

async function fixture(t, body) {
  const saved = new Map(Object.values(paths).map((path) => [path, require.cache[path]]));
  const state = {
    searches: [], nativeCalls: 0, checks: 0, logs: [],
    search: async () => [], native: async () => [],
    pending: [], gates: [],
  };
  const install = (path, exports) => {
    require.cache[path] = { id: path, filename: path, loaded: true, exports };
  };
  install(paths.provider, { searchSongs: async (q) => {
    state.searches.push(q);
    return state.search(q);
  } });
  install(paths.feed, { DiscoveryFeedService: {
    getNewReleaseTracks: async () => {
      state.nativeCalls++;
      return state.native();
    },
    getChartTracks: async () => [],
  } });
  install(paths.config, { ytmusicTimeoutMs: BUDGET });
  install(paths.logger, Object.fromEntries(['warn', 'info', 'error'].map((level) => [
    level, (...args) => state.logs.push({ level, args }),
  ])));
  install(paths.eligibility, { ...realEligibility, isEligible: (...args) => {
    state.checks++;
    return realEligibility.isEligible(...args);
  } });
  delete require.cache[paths.service];
  const service = require(paths.service);
  state.run = (id = 'fresh_releases') => {
    const result = service.generateCuratedPlaylist(id);
    // Immediately observe rejection; assertions still receive the original promise.
    state.pending.push(result.catch(() => undefined));
    return result;
  };
  state.gate = () => {
    const gate = deferred();
    state.gates.push(gate);
    return gate;
  };
  try {
    await body(state, service);
  } finally {
    for (const gate of state.gates) gate.resolve([]);
    await Promise.all(state.pending);
    await nextTurn();
    t.mock.timers.reset();
    for (const [path, entry] of saved) {
      if (entry) require.cache[path] = entry;
      else delete require.cache[path];
    }
  }
}

const inflatedSearchCases = {
  'duplicate video IDs across queries': () => tracks('s', 7),
  'packaging duplicates': () => Array.from({ length: 24 }, (_, i) => track(`s${i}`, {
    title: `Song ${i % 4} (${i % 2 ? 'Official Audio' : 'Lyrical Video'})`,
    artist: `Singer ${i % 4}`,
  })),
  'hard eligibility rejects': () => tracks('s', 24).map((item) => ({ ...item, duration: 12 })),
  'primary artist cap': () => tracks('s', 24).map((item) => ({ ...item, artist: 'Same Singer' })),
};

test('F1 three overlapping searches plus ten late native tracks fill exactly twenty', async (t) => {
  await fixture(t, async (s) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const native = s.gate();
    s.native = () => native.promise;
    s.search = async () => tracks('s', 10);
    let settled = false;
    const result = s.run().then((out) => { settled = true; return out; });
    await nextTurn();
    assert.equal(s.searches.length, 3, 'thirty raw candidates across three queries');
    t.mock.timers.tick(BUDGET * 2);
    await nextTurn();
    assert.equal(settled, false, 'ten selectable search tracks still need enrichment');
    native.resolve(tracks('n', 10));
    const out = await result;
    assert.equal(out.length, 20);
    assert.equal(out.filter((item) => item.videoId.startsWith('n')).length, 10);
    assert.equal(out.filter((item) => item.videoId.startsWith('s')).length, 10);
    assert.equal(new Set(ids(out)).size, 20);
    assert.deepEqual(ids(await s.run()), ids(out), 'the complete hybrid is the retained snapshot');
  });
});

for (const [label, searchPool] of Object.entries(inflatedSearchCases)) {
  test(`F1 selected search shortage waits past enrichment budget: ${label}`, async (t) => {
    await fixture(t, async (s) => {
      t.mock.timers.enable({ apis: ['setTimeout'] });
      const native = s.gate();
      s.native = () => native.promise;
      s.search = async () => searchPool();
      let settled = false;
      const result = s.run().then((value) => { settled = true; return value; });
      await nextTurn();
      t.mock.timers.tick(BUDGET + 1);
      await nextTurn();
      assert.equal(settled, false, 'raw count must not abandon native while final selectable search count is short');
      native.resolve(tracks('n', 20));
      const out = await result;
      assert.equal(out.length, 20);
      assert.equal(out[0].videoId, 'n0', 'native-first round robin remains authoritative');
    });
  });
}

for (const lateOutcome of ['success', 'rejection']) {
  test(`F1 full search returns on budget and late native ${lateOutcome} cannot mutate the snapshot`, async (t) => {
    await fixture(t, async (s) => {
      t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 1800000000000 });
      const native = s.gate();
      s.native = () => native.promise;
      s.search = async () => tracks('s', 20);
      const result = s.run();
      await nextTurn();
      t.mock.timers.tick(BUDGET);
      const out = await result;
      assert.equal(out.length, 20);
      assert.ok(out.every((item) => item.videoId.startsWith('s')));
      assert.equal(s.checks, 20, 'reuse selected search rather than running eligibility again');
      if (lateOutcome === 'success') native.resolve(tracks('n', 20));
      else native.reject(new Error('late native failure'));
      await nextTurn();
      assert.deepEqual(ids(await s.run()), ids(out));
      assert.equal(s.logs.filter((log) => log.args[0].startsWith('[PlaylistService:QA]')).length, 1);
      t.mock.timers.tick(2 * DAY);
      s.native = async () => [];
      s.search = async () => tracks('retry', 20);
      assert.equal((await s.run())[0].videoId, 'retry0', 'success flight released after budget return');
      assert.equal(s.nativeCalls, 2);
    });
  });
}

test('F1 healthy native preserves native-first competition even when search is full', async (t) => {
  await fixture(t, async (s) => {
    const native = track('n0', { artist: 'Artist s0' });
    s.search = async () => tracks('s', 20);
    s.native = async () => [native, ...tracks('n', 3).slice(1)];
    const out = await s.run();
    assert.equal(out.length, 20);
    // Each of the three queries returns s0 first, so search round robin presents
    // three s0 candidates while the three native artists enter the hybrid.
    assert.deepEqual(ids(out).slice(0, 5), ['n0', 'n1', 'n2', 's1', 's2']);
    assert.ok(!ids(out).includes('s0'), 'native wins the same-primary cap by source order');
    assert.equal(s.logs.filter((log) => log.args[0].startsWith('[PlaylistService:QA]')).length, 1);
  });
});

test('F1 contributor fallback counts as selectable search and is reused when native is empty', async (t) => {
  await fixture(t, async (s) => {
    s.search = async () => tracks('s', 20).map((item) => ({
      ...item, artists: [{ name: item.artist }, { name: 'Shared Guest' }],
      artist: `${item.artist} feat. Shared Guest`,
    }));
    const out = await s.run();
    assert.equal(out.length, 20, 'soft-deferred contributors fill in pass two');
    assert.equal(new Set(ids(out)).size, 20);
    assert.ok(s.logs.some((log) => log.args[1]?.rejections?.contributor_deferred_accepted === 19));
  });
});

test('F1 soft-contributor fallback can fill search and therefore activates the budget', async (t) => {
  await fixture(t, async (s) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const native = s.gate();
    s.native = () => native.promise;
    s.search = async () => tracks('s', 20).map((item) => ({
      ...item, artists: [{ name: item.artist }, { name: 'Shared Guest' }],
      artist: `${item.artist} feat. Shared Guest`,
    }));
    let out;
    const result = s.run().then((value) => { out = value; });
    await nextTurn();
    t.mock.timers.tick(BUDGET);
    await nextTurn();
    assert.equal(out?.length, 20, 'pass-two fill must not unnecessarily wait for native');
    await result;
    assert.equal(s.checks, 58, 'one pass: first artist once plus 19 deferred tracks from each query');
  });
});

test('F2 concurrent calls join one generation, while other playlist IDs progress independently', async (t) => {
  await fixture(t, async (s) => {
    const search = s.gate();
    const queries = getDefinitionById('fresh_releases').candidateQueries.length;
    s.search = () => search.promise;
    const a = s.run();
    const b = s.run();
    await nextTurn();
    assert.equal(s.searches.length, queries, 'same-ID callers must issue one query batch');
    s.search = async () => tracks('other', 30);
    assert.ok((await s.run('romantic_hits')).length > 0, 'unrelated ID must not wait for first flight');
    search.resolve(tracks('first', 20));
    assert.deepEqual(ids(await a), ids(await b));
    assert.equal(s.nativeCalls, 1);
  });
});

test('F2 original A-empty-search/slow-native versus B-recovered-search race has one consistent commit', async (t) => {
  await fixture(t, async (s) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const native = s.gate();
    s.native = () => native.promise;
    const a = s.run(); // A gets zero search candidates and waits for slow native.
    await nextTurn();
    s.search = async () => tracks('recovered', 20);
    const b = s.run(); // Without single-flight B fills 20 on the enrichment budget.
    await nextTurn();
    t.mock.timers.tick(BUDGET);
    await nextTurn();
    native.resolve(tracks('native', 10)); // Old A then overwrites B's 20-track snapshot with 10.
    const [first, second] = await Promise.all([a, b]);
    assert.deepEqual(ids(second), ids(first), 'B must join A; a consistent 10-track generation is valid');
    assert.equal(first.length, 10);
    assert.deepEqual(ids(await s.run()), ids(first));
    assert.equal(s.searches.length, getDefinitionById('fresh_releases').candidateQueries.length);
  });
});

for (const warm of [false, true]) {
  test(`F2 empty completion releases flight and backoff expires (${warm ? 'expired healthy snapshot' : 'cold start'})`, async (t) => {
    await fixture(t, async (s) => {
      t.mock.timers.enable({ apis: ['Date'], now: 1800000000000 });
      let healthy = [];
      if (warm) {
        s.search = async () => tracks('healthy', 20);
        healthy = await s.run();
        t.mock.timers.tick(2 * DAY);
      }
      const gate = s.gate();
      s.search = () => gate.promise;
      const before = s.searches.length;
      const a = s.run(), b = s.run();
      await nextTurn();
      gate.resolve([]);
      const [first, second] = await Promise.all([a, b]);
      assert.deepEqual(ids(first), ids(healthy));
      assert.deepEqual(ids(second), ids(healthy));
      assert.equal(s.searches.length - before, 3, 'empty requests share one attempt');
      s.search = async () => tracks('recovered', 20);
      t.mock.timers.tick(2 * DAY - 1);
      assert.deepEqual(ids(await s.run()), ids(healthy), 'backoff remains unchanged');
      assert.equal(s.searches.length - before, 3);
      t.mock.timers.tick(1);
      assert.equal((await s.run())[0].videoId, 'recovered0', 'retry at exact expiry');
      assert.equal(s.searches.length - before, 6);
    });
  });

  test(`F2 exception releases flight without cadence/backoff changes (${warm ? 'expired healthy snapshot' : 'cold start'})`, async (t) => {
    await fixture(t, async (s) => {
      t.mock.timers.enable({ apis: ['Date'], now: 1800000000000 });
      let healthy = [];
      if (warm) {
        s.search = async () => tracks('healthy', 20);
        healthy = await s.run();
        t.mock.timers.tick(2 * DAY);
      }
      const gate = s.gate();
      s.search = () => gate.promise;
      const before = s.searches.length;
      const a = s.run(), b = s.run();
      const assertions = [assert.rejects(a, /selection failure/), assert.rejects(b, /selection failure/)];
      await nextTurn();
      const broken = track('broken');
      Object.defineProperty(broken, 'title', { get() { throw new Error('selection failure'); } });
      gate.resolve([broken]);
      await Promise.all(assertions);
      assert.equal(s.searches.length - before, 3, 'exception belongs to one shared attempt');
      s.search = async () => [];
      assert.deepEqual(ids(await s.run()), ids(healthy), 'snapshot survives exception; immediate empty retry serves it');
      assert.equal(s.searches.length - before, 6, 'exception did not advance cadence or install backoff');
      t.mock.timers.tick(2 * DAY);
      s.search = async () => tracks('recovered', 20);
      assert.equal((await s.run())[0].videoId, 'recovered0');
    });
  });
}

test('F2 synchronously failing query setup can retry without a stuck flight', async (t) => {
  await fixture(t, async (s) => {
    // A synchronous metadata exception after registration must not leak its entry.
    const originalNow = Date.now;
    t.mock.method(Date, 'now', () => { throw new Error('clock failed'); });
    await assert.rejects(s.run(), /clock failed/);
    Date.now = originalNow;
    s.search = async () => tracks('retry', 20);
    assert.equal((await s.run())[0].videoId, 'retry0');
  });
});

test('F3 Unicode normalization preserves letters, marks, numbers, underscore and artist spacing/hyphens', async (t) => {
  await fixture(t, async (_s, svc) => {
    assert.equal(svc.cleanArtistString('  श्रेया-घोषाल_१२  & Café  '), 'श्रेया-घोषाल_१२ café');
    assert.equal(svc.cleanArtistString('CAFÉ'), svc.cleanArtistString('CAFE\u0301'));
    assert.equal(svc.cleanArtistString('क़'), svc.cleanArtistString('क\u093c'));
    assert.notEqual(svc.cleanArtistString('क'), svc.cleanArtistString('कि'), 'vowel marks remain meaningful');
    assert.equal(svc.normalizePrimaryArtist({ artists: [{ name: 'श्रेया घोषाल & Arijit Singh' }] }), 'श्रेया घोषाल');
    assert.deepEqual(svc.extractAllContributors({ artist: 'श्रेया घोषाल feat. Arijit Singh' }), ['श्रेया घोषाल', 'arijit singh']);
  });
});

test('F3 distinct Devanagari and mixed Hindi/Latin canonical titles do not collapse', async (t) => {
  await fixture(t, async (_s, svc) => {
    const key = (title, artist = 'श्रेया घोषाल') => svc.getCanonicalTrackKey({ title, artist });
    assert.notEqual(key('तुम ही हो'), key('केसरिया'));
    assert.notEqual(key('दिल Love'), key('मन Love'));
    assert.notEqual(key('गीत', 'श्रेया घोषाल'), key('गीत', 'अरिजीत सिंह'));
    assert.equal(key('केसरिया (Official Audio)'), key('केसरिया [Lyrical Video]'));
    assert.equal(key('Café क़'), key('Cafe\u0301 क\u093c'));
  });
});

test('F3 playlist canonical dedup and Latin/non-Latin artist caps remain separate rules', async (t) => {
  await fixture(t, async (s) => {
    // romantic_hits cap=2: distinct Hindi songs by one artist must both survive,
    // while the packaging duplicate must not consume the second artist slot.
    s.search = async () => [
      track('h1', { title: 'तुम ही हो', artist: 'अरिजीत सिंह' }),
      track('hdup', { title: 'तुम ही हो (Official Audio)', artist: 'अरिजीत सिंह' }),
      track('h2', { title: 'केसरिया', artist: 'अरिजीत सिंह' }),
      track('h3', { title: 'चन्ना मेरेया', artist: 'अरिजीत सिंह' }),
      track('other', { title: 'गीत', artist: 'श्रेया घोषाल' }),
      ...tracks('latin', 3).map((item) => ({ ...item, artist: 'Latin Singer' })),
    ];
    assert.deepEqual(new Set(ids(await s.run('romantic_hits'))), new Set(['h1', 'h2', 'other', 'latin0', 'latin1']));
  });
});

test('F3 English version/package/delimiter rules are preserved', async (t) => {
  await fixture(t, async (_s, svc) => {
    const key = (title, remix = false, acoustic = false) => svc.getCanonicalTrackKey({ title, artist: 'Singer' }, remix, acoustic);
    assert.equal(key('Song (Official Audio) | Channel'), key('Song [Lyrical Video]'));
    assert.equal(key('Song (Remix)'), key('Song'));
    assert.notEqual(key('Song (Remix)', true), key('Song', true));
    assert.notEqual(key('Song (Acoustic)', false, true), key('Song', false, true));
    assert.notEqual(key('Song (Live)'), key('Song'));
    assert.notEqual(key('Song (Tamil)'), key('Song (Telugu)'));
    assert.equal(key('Song (From "Hindi Medium")'), key('Song'));
    assert.equal(svc.normalizePrimaryArtist({ artist: 'Vishal & Shekhar' }), 'vishal-shekhar');
    assert.equal(svc.normalizePrimaryArtist({ artist: 'Singer feat. Guest' }), 'singer');
  });
});

test('F3 Fresh Releases keeps distinct Devanagari artists and caps each script at one track', async (t) => {
  await fixture(t, async (s) => {
    s.search = async () => [
      track('h1', { title: 'तुम ही हो', artist: 'अरिजीत सिंह' }),
      track('h2', { title: 'केसरिया', artist: 'अरिजीत सिंह' }),
      track('h3', { title: 'गीत', artist: 'श्रेया घोषाल' }),
      track('h4', { title: 'दिल', artist: 'श्रेया घोषाल' }),
      ...tracks('latin', 2).map((item) => ({ ...item, artist: 'Latin Singer' })),
    ];
    const out = await s.run();
    assert.deepEqual(ids(out), ['h1', 'h3', 'latin0']);
    assert.equal(new Set(out.map((item) => item.artist)).size, 3);
  });
});

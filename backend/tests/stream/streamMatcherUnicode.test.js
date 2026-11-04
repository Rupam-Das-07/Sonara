'use strict';

/**
 * streamMatcherUnicode.test.js — Unicode-safe artist/title matching contract.
 *
 * Added as a dedicated file in the existing tests/stream suite rather than edited into
 * streamMatcher.test.js, because another agent is running integration checks in this
 * tree concurrently; the existing file's expectations are left untouched and still run.
 *
 * ROOT CAUSE BEING REGRESSED
 * extractArtistTokens() tokenized with /[^a-z0-9]+/, under which every character of a
 * non-Latin name is a delimiter. The token set came out EMPTY, and matchesArtist()
 * returns false whenever the target has no tokens, so an exact Devanagari/Bengali
 * artist match was rejected as `artist_mismatch`. The title gate had the same flaw via
 * discovery's normalizeTitle(), which ends in /[^a-z0-9]/ and collapses any non-Latin
 * title to '' — matchesTitle() then returns false for `!wanted`.
 *
 * Case coverage (numbers follow the task's list):
 *   M1  Latin match still accepted                              (1, 13)
 *   M2  Devanagari artist exact match accepted                  (2, 5)
 *   M3  Bengali artist exact match accepted                     (3, 5)
 *   M4  Tamil artist exact match accepted                       (4, 5)
 *   M5  Japanese (no word spaces) artist accepted               (4)
 *   M6  Devanagari artist mismatch still rejected               (6)
 *   M7  Bengali artist mismatch still rejected                  (6)
 *   M8  Mixed-script artist names                               (7)
 *   M9  Composed vs decomposed forms are equivalent             (8)
 *   M10 Combining marks that distinguish names are not stripped (9)
 *   M11 Punctuation and whitespace variants                     (10)
 *   M12 Empty / whitespace / punctuation-only / missing artist  (11)
 *   M13 Both token sets empty cannot be a match                 (12)
 *   M14 Duration and title gates unchanged under native script  (13)
 *   M15 Native-script titles compare instead of collapsing      (13)
 *   M16 Provider resolves a genuine native-script match         (14, 15)
 *   M17 Provider returns null on native-script artist mismatch  (14, 15)
 *   M18 Tokenization is deterministic and well-formed (property)
 */

const { test, describe } = require('node:test');
const assert = require('node:assert/strict');

const {
  evaluateMatch,
  extractArtistTokens,
  matchesArtist,
  matchesTitle,
  normalizeTitleForMatch,
  titleSegments,
  isLeadingOrTrailingRun,
  MIN_ARTIST_TOKEN_LENGTH,
} = require('../../src/stream/streamMatcher');
const { JioSaavnStreamProvider } = require('../../src/stream/providers/jiosaavnStreamProvider');

// Same fixture the existing provider suite uses: decrypts to a real saavncdn URL.
const VALID_ENC_URL = 'ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyryhkSYK5IH2E7FCO52VR6yhNbcEbes5iCcja4+W8xhE0SwtCJToN4Bw7tS9a8Gtq';
const EXPECTED_STREAM_URL = 'https://aac.saavncdn.com/871/c2febd353f3a076a406fa37510f31f9f_320.mp4';

/** A candidate that passes every gate except the ones a test deliberately breaks. */
function candidateFor({ song, singers, duration = '268' }) {
  return {
    song,
    singers,
    duration,
    '320kbps': 'true',
    encrypted_media_url: VALID_ENC_URL,
  };
}

function targetFor({ title, artist, duration = 268 }) {
  return { videoId: 'dQw4w9WgXcQ', title, artist, duration };
}

/** Full-gate evaluation for a same-title, same-duration pair. */
const verdict = (targetArtist, candidateArtist, title = 'Kesariya') => evaluateMatch(
  targetFor({ title, artist: targetArtist }),
  candidateFor({ song: title, singers: candidateArtist })
);

describe('streamMatcher — Unicode-safe artist and title matching', () => {

  // ------------------------------------------------------------------ M1 basic

  test('M1 Latin artist and title still match exactly as before', () => {
    const res = verdict('Arijit Singh', 'Arijit Singh, Pritam');
    assert.equal(res.ok, true, 'the pre-existing Latin happy path must be untouched');
    assert.equal(res.deltaSeconds, 0);
    assert.deepEqual([...extractArtistTokens('Arijit Singh')], ['arijit', 'singh'],
      'Latin tokenization output must be unchanged');
  });

  // ------------------------------------------------------- M2/M3/M4/M5 accept

  test('M2 an exact Devanagari artist match is accepted', () => {
    assert.deepEqual([...extractArtistTokens('अरिजीत सिंह')], ['अरिजीत', 'सिंह'],
      'Devanagari must tokenize into words, not vanish');
    const res = verdict('अरिजीत सिंह', 'अरिजीत सिंह');
    assert.equal(res.ok, true, `exact Devanagari artists must match, got ${res.reason}`);
  });

  test('M3 an exact Bengali artist match is accepted', () => {
    assert.ok(extractArtistTokens('শ্রেয়া ঘোষাল').size > 0, 'Bengali must produce tokens');
    const res = verdict('শ্রেয়া ঘোষাল', 'শ্রেয়া ঘোষাল');
    assert.equal(res.ok, true, `exact Bengali artists must match, got ${res.reason}`);
  });

  test('M4 Tamil tokenization retains only tokens meeting the shared length floor', () => {
    const tamil = 'ஏ. ஆர். ரஹ்மான்';
    assert.deepEqual([...extractArtistTokens(tamil)], ['ஆர்', 'ரஹ்மான்'],
      'the one-code-point initial "ஏ" is intentionally filtered; the remaining tokens are exact');
    assert.deepEqual([...extractArtistTokens(tamil.normalize('NFC'))], ['ஆர்', 'ரஹ்மான்'],
      'NFC normalization must not alter the Tamil token sequence');
    const res = verdict(tamil, tamil);
    assert.equal(res.ok, true, `exact Tamil artists must match, got ${res.reason}`);
  });

  test('M4a a discarded short Tamil initial cannot match an unrelated artist by itself', () => {
    assert.deepEqual([...extractArtistTokens('ஏ')], [],
      'the one-code-point Tamil initial remains below MIN_ARTIST_TOKEN_LENGTH');
    assert.equal(matchesArtist('ஏ. ஆர். ரஹ்மான்', { singers: 'ஏ' }), false,
      'a discarded short token alone must not establish artist identity');
  });

  test('M5 a Japanese artist with no word spaces is accepted', () => {
    const tokens = [...extractArtistTokens('宇多田ヒカル')];
    assert.deepEqual(tokens, ['宇多田ヒカル'], 'a space-less CJK name is a single token');
    const res = verdict('宇多田ヒカル', '宇多田ヒカル', 'First Love');
    assert.equal(res.ok, true, `exact Japanese artists must match, got ${res.reason}`);
  });

  test('M5a exact short Japanese and Korean artist names use whole-name equality', () => {
    for (const [artist, title] of [['嵐', 'Love'], ['絢香', '三日月'], ['미', 'Song']]) {
      const res = verdict(artist, artist, title);
      assert.equal(res.ok, true, `${artist} must match itself, got ${res.reason}`);
    }
    assert.equal(verdict('絢 香', '絢香', '三日月').ok, true,
      'CJK display whitespace should not change the exact short-name identity');
  });

  test('M5b short Chinese artist names are accepted without lowering the global token floor', () => {
    const res = verdict('周杰倫', '周杰倫', '稻香');
    assert.equal(res.ok, true, `周杰倫 must match itself, got ${res.reason}`);
    assert.equal(MIN_ARTIST_TOKEN_LENGTH, 3);
  });

  test('M5c short CJK matching rejects overlap, extra credits, and Latin short tokens', () => {
    assert.equal(verdict('嵐', '嵐山', 'Love').reason, 'artist_mismatch');
    assert.equal(verdict('嵐', '嵐, 甲', 'Love').reason, 'artist_mismatch');
    assert.equal(verdict('絢香', '絢', '三日月').reason, 'artist_mismatch');
    assert.equal(verdict('미', '미나', 'Song').reason, 'artist_mismatch');
    assert.equal(verdict('AB', 'AB', 'Song').reason, 'artist_mismatch',
      'Latin tokens below the existing minimum remain rejected');
  });

  // -------------------------------------------------------------- M6/M7 reject

  test('M6 a Devanagari artist mismatch is still rejected', () => {
    const res = verdict('अरिजीत सिंह', 'बादशाह नेहा');
    assert.equal(res.ok, false, 'different Devanagari artists must not match');
    assert.equal(res.reason, 'artist_mismatch');
  });

  test('M7 a Bengali artist mismatch is still rejected', () => {
    const res = verdict('শ্রেয়া ঘোষাল', 'কিশোর কুমার');
    assert.equal(res.ok, false, 'different Bengali artists must not match');
    assert.equal(res.reason, 'artist_mismatch');
  });

  test('M7b a native-script candidate does not match a Latin target', () => {
    const res = verdict('Adele', 'অরিজিৎ সিং');
    assert.equal(res.ok, false, 'unrelated scripts must not match');
    assert.equal(res.reason, 'artist_mismatch');
  });

  // ---------------------------------------------------------------- M8 mixed

  test('M8 mixed-script artist names match on their shared token', () => {
    // The candidate credits the Latin spelling alongside the native one.
    const res = verdict('अरिजीत सिंह', 'Arijit Singh, अरिजीत सिंह');
    assert.equal(res.ok, true, `a shared native token must match, got ${res.reason}`);

    const latinSide = verdict('Arijit Singh', 'Arijit Singh, अरिजीत सिंह');
    assert.equal(latinSide.ok, true, 'and the Latin token must match too');

    assert.deepEqual([...extractArtistTokens('Arijit अरिजीत')], ['arijit', 'अरिजीत'],
      'a mixed-script string yields one token per script run');
  });

  // ------------------------------------------------------------ M9 composed

  test('M9 composed and decomposed spellings of the same name are equivalent', () => {
    const composed = 'Beyoncé';          // é as one code point
    const decomposed = 'Beyoncé';       // e + combining acute
    assert.notEqual(composed, decomposed, 'precondition: the raw strings differ');

    assert.deepEqual([...extractArtistTokens(composed)], [...extractArtistTokens(decomposed)],
      'NFC must make the two spellings tokenize identically');
    assert.equal(matchesArtist(composed, { singers: decomposed }), true,
      'a decomposed candidate must match a composed target');
    assert.equal(matchesArtist(decomposed, { singers: composed }), true, 'and vice versa');

    assert.equal(normalizeTitleForMatch('Créole'), normalizeTitleForMatch('Créole'),
      'titles must normalize the same way');
  });

  // --------------------------------------------------------------- M10 marks

  /**
   * M10 — marks are NOT stripped, so an accented name stays distinct from its
   * unaccented spelling. This is also what the previous ASCII rule did (it produced
   * 'jos'/'gonz'/'lez' vs 'jose'/'gonzalez', which likewise did not overlap), so the
   * conservative outcome is preserved rather than newly introduced.
   */
  test('M10 combining marks that distinguish names are not indiscriminately stripped', () => {
    const accented = [...extractArtistTokens('José González')];
    const plain = [...extractArtistTokens('Jose Gonzalez')];
    assert.deepEqual(accented, ['josé', 'gonzález'], 'marks are preserved inside tokens');
    assert.deepEqual(plain, ['jose', 'gonzalez']);
    assert.equal(accented.some((t) => plain.includes(t)), false, 'no token is shared');

    const res = verdict('José González', 'Jose Gonzalez');
    assert.equal(res.ok, false, 'an accent-stripped spelling must not silently match');
    assert.equal(res.reason, 'artist_mismatch');
  });

  // --------------------------------------------------- M11 punctuation rules

  test('M11 punctuation and whitespace only delimit tokens', () => {
    const cases = [
      ['A.R. Rahman', ['rahman']],                       // 'a' and 'r' are below MIN length
      ['Arijit  Singh', ['arijit', 'singh']],            // repeated spaces collapse
      ['\tArijit\nSingh ', ['arijit', 'singh']],         // tabs/newlines delimit
      ['Arijit-Singh', ['arijit', 'singh']],             // hyphen delimits
      ['Arijit & Singh', ['arijit', 'singh']],           // ampersand delimits, 'and' n/a
      ['अरिजीत, सिंह', ['अरिजीत', 'सिंह']],                 // native + ASCII comma
      ['अरिजीत।सिंह', ['अरिजीत', 'सिंह']],                  // Devanagari danda delimits
    ];
    for (const [input, expected] of cases) {
      assert.deepEqual([...extractArtistTokens(input)], expected, `tokens for ${JSON.stringify(input)}`);
    }
    assert.equal(MIN_ARTIST_TOKEN_LENGTH, 3, 'spec anchor: the minimum token length is unchanged');
  });

  // ------------------------------------------------------------- M12 degenerate

  test('M12 empty, whitespace-only, punctuation-only and missing artists yield no tokens', () => {
    for (const input of ['', '   ', '\t\n', '---', '...', '!!! ???', '--- ... ---', null, undefined, 42, {}]) {
      assert.equal(extractArtistTokens(input).size, 0, `no tokens for ${JSON.stringify(input)}`);
    }
    // Mark-only input carries no identity and must not become a token.
    assert.equal(extractArtistTokens('́́́').size, 0, 'a run of bare combining marks is not a token');

    for (const bad of ['', '   ', '---', null, undefined]) {
      const res = evaluateMatch(
        targetFor({ title: 'Kesariya', artist: bad }),
        candidateFor({ song: 'Kesariya', singers: 'Arijit Singh' })
      );
      assert.equal(res.ok, false, `a target with no artist identity must not match (${JSON.stringify(bad)})`);
      assert.equal(res.reason, 'artist_mismatch');
    }
  });

  test('M13 two empty token sets must never count as a match', () => {
    const res = verdict('---', '...');
    assert.equal(res.ok, false, 'both sides unusable must be a rejection, not a vacuous match');
    assert.equal(res.reason, 'artist_mismatch');

    assert.equal(matchesArtist('', { singers: '' }), false);
    assert.equal(matchesArtist('   ', { singers: '!!!' }), false);
    assert.equal(matchesArtist('́', { singers: '́' }), false,
      'identical mark-only strings must not match each other');
  });

  // ------------------------------------------------- M14/M15 other gates hold

  test('M14 duration and junk gates behave unchanged for native-script tracks', () => {
    const base = { title: 'तुम ही हो', artist: 'अरिजीत सिंह' };

    const ok3 = evaluateMatch(
      targetFor({ ...base, duration: 268 }),
      candidateFor({ song: 'तुम ही हो', singers: 'अरिजीत सिंह', duration: '271' })
    );
    assert.equal(ok3.ok, true, '3s delta is still accepted');
    assert.equal(ok3.deltaSeconds, 3);

    const bad4 = evaluateMatch(
      targetFor({ ...base, duration: 268 }),
      candidateFor({ song: 'तुम ही हो', singers: 'अरिजीत सिंह', duration: '272' })
    );
    assert.equal(bad4.ok, false);
    assert.equal(bad4.reason, 'duration_mismatch');
    assert.equal(bad4.deltaSeconds, 4);

    const junk = evaluateMatch(
      targetFor(base),
      candidateFor({ song: 'तुम ही हो (Karaoke Version)', singers: 'अरिजीत सिंह' })
    );
    assert.equal(junk.ok, false);
    assert.equal(junk.reason, 'junk_variant', 'the junk gate still fires on a native-script title');
  });

  test('M15 native-script titles are compared instead of collapsing to empty', () => {
    assert.equal(normalizeTitleForMatch('तुम ही हो'), 'तुमहीहो', 'a Devanagari title must survive normalization');
    assert.equal(normalizeTitleForMatch('Tum Hi Ho'), 'tumhiho', 'Latin normalization is unchanged');
    assert.equal(normalizeTitleForMatch('Kesariya (From "Brahmastra")'), 'kesariya',
      'bracket stripping is unchanged');

    assert.equal(matchesTitle('तुम ही हो', 'तुम ही हो'), true, 'identical native titles match');
    assert.equal(matchesTitle('তুমি রবে নীরবে', 'তুমি রবে নীরবে'), true, 'identical Bengali titles match');

    const mismatch = evaluateMatch(
      targetFor({ title: 'तुम ही हो', artist: 'अरिजीत सिंह' }),
      candidateFor({ song: 'कुछ और गाना', singers: 'अरिजीत सिंह' })
    );
    assert.equal(mismatch.ok, false, 'a genuinely different native title must still be rejected');
    assert.equal(mismatch.reason, 'title_mismatch',
      'and it must fail on the TITLE gate, proving the artist gate passed');
  });

  // ------------------------------------------------------------- M16/M17 provider

  function mockFetch(candidates) {
    return async (url, init = {}) => {
      if (init.method === 'HEAD') {
        return {
          ok: true,
          status: 200,
          headers: new Map([
            ['content-type', 'audio/mp4'],
            ['content-length', '8000000'],
            ['accept-ranges', 'bytes'],
          ]),
        };
      }
      return { ok: true, status: 200, text: async () => JSON.stringify({ results: candidates }) };
    };
  }

  test('M16 the provider resolves a genuine native-script match end to end', async () => {
    const provider = new JioSaavnStreamProvider({
      fetchFn: mockFetch([candidateFor({ song: 'तुम ही हो', singers: 'अरिजीत सिंह' })]),
    });
    const res = await provider.resolve(targetFor({ title: 'तुम ही हो', artist: 'अरिजीत सिंह' }));

    assert.ok(res, 'a valid Devanagari candidate must resolve rather than be rejected');
    assert.equal(res.provider, 'jiosaavn');
    assert.equal(res.bitrate, 320);
    assert.equal(res.streamUrl, EXPECTED_STREAM_URL, 'the existing response shape is unchanged');
  });

  test('M17 the provider still returns null when the native-script artist mismatches', async () => {
    const provider = new JioSaavnStreamProvider({
      fetchFn: mockFetch([candidateFor({ song: 'तुम ही हो', singers: 'बादशाह नेहा' })]),
    });
    const res = await provider.resolve(targetFor({ title: 'तुम ही हो', artist: 'अरिजीत सिंह' }));

    assert.equal(res, null, 'a wrong-artist candidate must still be rejected by the provider');
  });

  // --------------------------------------------------------------- M18 property

  test('M18 property: tokenization is deterministic, idempotent and well-formed', () => {
    const corpus = [
      'Arijit Singh', 'अरिजीत सिंह', 'শ্রেয়া ঘোষাল', 'ஏ. ஆர். ரஹ்மான்', '宇多田ヒカル',
      'José González', 'Beyoncé', 'A.R. Rahman', 'Arijit & श्रेया',
      'лата мангешкар', '  spaced   out  ', '---', '', 'The Band feat. Someone',
      'नुसरत फ़तेह अली ख़ान', 'ਗੁਰਦਾਸ ਮਾਨ', 'Σωκράτης Μάλαμας',
    ];

    let nonEmptySets = 0;
    for (const name of corpus) {
      const first = [...extractArtistTokens(name)];
      const second = [...extractArtistTokens(name)];
      assert.deepEqual(second, first, `deterministic for ${JSON.stringify(name)}`);

      // Idempotent under NFC: pre-normalizing the input changes nothing.
      assert.deepEqual([...extractArtistTokens(name.normalize('NFC'))], first,
        `NFC-idempotent for ${JSON.stringify(name)}`);

      for (const token of first) {
        assert.ok(token.length >= MIN_ARTIST_TOKEN_LENGTH, `token "${token}" respects the minimum length`);
        assert.equal(token, token.trim(), `token "${token}" has no surrounding whitespace`);
        assert.match(token, /[\p{L}\p{N}]/u, `token "${token}" contains a letter or digit`);
        assert.doesNotMatch(token, /[\s,.&|/\\()[\]-]/u, `token "${token}" contains no delimiter`);
        assert.equal(token, token.toLowerCase(), `token "${token}" is lowercased`);
      }
      if (first.length > 0) nonEmptySets++;
    }

    // Guard against a vacuous pass: the corpus must exercise both outcomes.
    assert.ok(nonEmptySets > 0, 'the corpus must produce tokens for real names');
    assert.ok(nonEmptySets < corpus.length, 'and must include degenerate names that produce none');
  });
});

/**
 * Title-containment follow-up: an unrelated longer provider title must not be accepted
 * just because it contains the target title as a substring.
 *
 * Reproduced defect (quality review): target {title:'प्यार'} accepted candidate
 * {song:'प्यार का दर्द'} with the same artist and a 1s duration delta, returning
 * {ok:true, deltaSeconds:1}; identically for 'Pyaar' vs 'Pyaar Ka Dard'.
 *
 * CONTRACT DECISION (the callers leave it ambiguous, so the least permissive safe
 * reading was taken): containment exists only so the canonical YouTube target can
 * carry an unbracketed annotation the provider title lacks — bracketed annotations are
 * stripped and compare exactly, and no pre-existing test depended on containment at
 * all. It is therefore allowed in ONE direction (target longer) and only on whole
 * segment boundaries. A false negative is safe because the resolver falls back to the
 * YouTube stream; a false positive plays the wrong recording.
 */
describe('streamMatcher — title containment must not accept unrelated longer titles', () => {

  test('T1 the reported Hindi false positive is now rejected as title_mismatch', () => {
    const res = evaluateMatch(
      targetFor({ title: 'प्यार', artist: 'अरिजीत सिंह', duration: 200 }),
      candidateFor({ song: 'प्यार का दर्द', singers: 'अरिजीत सिंह', duration: '201' })
    );
    assert.equal(res.ok, false, 'an unrelated longer provider title must not be accepted');
    assert.equal(res.reason, 'title_mismatch',
      'and it must fail on the TITLE gate, proving the artist and duration gates still passed');
  });

  test('T2 the equivalent Latin containment case is rejected the same way', () => {
    const res = evaluateMatch(
      targetFor({ title: 'Pyaar', artist: 'Arijit Singh', duration: 200 }),
      candidateFor({ song: 'Pyaar Ka Dard', singers: 'Arijit Singh', duration: '201' })
    );
    assert.equal(res.ok, false);
    assert.equal(res.reason, 'title_mismatch');
  });

  test('T3 a legitimate longer TARGET title still matches a clean provider title', () => {
    // The one real case containment serves: unbracketed annotation on the canonical side.
    assert.equal(matchesTitle('Kesariya - Official Video', 'Kesariya'), true);
    assert.equal(matchesTitle('Kesariya | Full Video Song', 'Kesariya'), true);
    assert.equal(matchesTitle('तुम ही हो - आशिकी 2', 'तुम ही हो'), true,
      'the same allowance applies to native script');

    // And end to end, so the gate order and return shape are exercised.
    const res = evaluateMatch(
      targetFor({ title: 'Kesariya - Official Video', artist: 'Arijit Singh', duration: 268 }),
      candidateFor({ song: 'Kesariya', singers: 'Arijit Singh, Pritam', duration: '268' })
    );
    assert.equal(res.ok, true, `a legitimate annotated target must still match, got ${res.reason}`);
    assert.equal(res.deltaSeconds, 0);
  });

  test('T4 exact native-script titles still match', () => {
    assert.equal(matchesTitle('तुम ही हो', 'तुम ही हो'), true);
    assert.equal(matchesTitle('তুমি রবে নীরবে', 'তুমি রবে নীরবে'), true);
    assert.equal(matchesTitle('ஏ ஆர் ரஹ்மான்', 'ஏ ஆர் ரஹ்மான்'), true);
    // Bracketed annotation is stripped, so it is an EXACT match, not containment.
    assert.equal(normalizeTitleForMatch('तुम ही हो (Lyrical)'), normalizeTitleForMatch('तुम ही हो'));
  });

  test('T5 exact Latin titles still match, including bracketed annotations', () => {
    assert.equal(matchesTitle('Kesariya', 'Kesariya'), true);
    assert.equal(matchesTitle('Naatu Naatu (From "RRR")', 'Naatu Naatu'), true);
    assert.equal(matchesTitle('Hello', 'Hello'), true);
  });

  test('T6 punctuation and whitespace variants are still equivalent', () => {
    const equivalent = [
      ['Kesariya!', 'Kesariya'],
      ['Tum Hi Ho', 'TumHiHo'],
      ['  Tum   Hi  Ho  ', 'Tum Hi Ho'],
      ['Tum-Hi-Ho', 'Tum Hi Ho'],
      ['तुम ही हो', 'तुम  ही  हो'],
      ['तुम।ही।हो', 'तुम ही हो'],
    ];
    for (const [a, b] of equivalent) {
      assert.equal(matchesTitle(a, b), true, `${JSON.stringify(a)} should match ${JSON.stringify(b)}`);
      assert.equal(matchesTitle(b, a), true, 'and symmetrically');
    }
  });

  test('T7 mixed-script titles match exactly and allow only annotation on the target', () => {
    assert.equal(matchesTitle('Tum Hi Ho तुम', 'Tum Hi Ho तुम'), true, 'exact mixed-script match');
    assert.equal(matchesTitle('Tum Hi Ho तुम', 'Tum Hi Ho'), true,
      'a leading segment run of the target is allowed');
    assert.equal(matchesTitle('Tum Hi Ho', 'Tum Hi Ho तुम'), false,
      'but the provider side may NOT be the longer one');
  });

  test('T8 a title sharing only an internal substring is rejected', () => {
    // Mid-word hits: the old rule accepted these because both sides cleared the
    // 4-character floor and one string contained the other.
    const unrelated = [
      ['Bekhayali', 'Khaya'],
      ['प्यारी', 'प्यार'],
      ['Hello World', 'Hell'],
      ['Chaleya', 'Chale'],
      ['Dilbar Dilbar', 'Ilba'],
    ];
    for (const [targetTitle, candidateTitle] of unrelated) {
      assert.equal(matchesTitle(targetTitle, candidateTitle), false,
        `${JSON.stringify(candidateTitle)} must not match ${JSON.stringify(targetTitle)} on a substring`);
    }

    // Guard against a vacuous test: these really are raw-substring containments, i.e.
    // exactly what the previous rule accepted.
    for (const [targetTitle, candidateTitle] of unrelated) {
      const w = normalizeTitleForMatch(targetTitle);
      const g = normalizeTitleForMatch(candidateTitle);
      assert.ok(w.includes(g) || g.includes(w),
        `precondition: ${JSON.stringify(candidateTitle)} is a substring of ${JSON.stringify(targetTitle)}`);
    }
  });

  test('T9 the artist and duration gates are unaffected by the title change', () => {
    // Artist gate still fires first, with its own reason.
    const artistBad = evaluateMatch(
      targetFor({ title: 'Kesariya', artist: 'Arijit Singh', duration: 268 }),
      candidateFor({ song: 'Kesariya', singers: 'Alda Rikson', duration: '268' })
    );
    assert.equal(artistBad.reason, 'artist_mismatch', 'artist_mismatch still precedes the title gate');

    // Duration tolerance unchanged: 3s accepted, 4s rejected.
    const ok3 = evaluateMatch(
      targetFor({ title: 'Kesariya', artist: 'Arijit Singh', duration: 268 }),
      candidateFor({ song: 'Kesariya', singers: 'Arijit Singh', duration: '271' })
    );
    assert.equal(ok3.ok, true);
    assert.equal(ok3.deltaSeconds, 3);

    const bad4 = evaluateMatch(
      targetFor({ title: 'Kesariya', artist: 'Arijit Singh', duration: 268 }),
      candidateFor({ song: 'Kesariya', singers: 'Arijit Singh', duration: '272' })
    );
    assert.equal(bad4.reason, 'duration_mismatch');
    assert.equal(bad4.deltaSeconds, 4);

    // Junk gate still precedes the title gate.
    const junk = evaluateMatch(
      targetFor({ title: 'Kesariya', artist: 'Arijit Singh', duration: 268 }),
      candidateFor({ song: 'Kesariya (Karaoke Version)', singers: 'Arijit Singh', duration: '268' })
    );
    assert.equal(junk.reason, 'junk_variant');
  });

  test('T10 empty, whitespace-only and punctuation-only titles never match', () => {
    for (const bad of ['', '   ', '---', '...', '()', '[]', null, undefined]) {
      assert.equal(matchesTitle(bad, 'Kesariya'), false, `target ${JSON.stringify(bad)} must not match`);
      assert.equal(matchesTitle('Kesariya', bad), false, `candidate ${JSON.stringify(bad)} must not match`);
    }
    assert.equal(matchesTitle('(Official Video)', 'Kesariya'), false,
      'a title that is entirely a bracketed annotation normalizes to empty and cannot match');
    assert.deepEqual(titleSegments('  --- ...  '), [], 'separator-only input yields no segments');
  });

  test('T11 the length floor and single-segment behaviour are unchanged', () => {
    // Below the 4-character floor containment is not attempted, only exact match.
    assert.equal(matchesTitle('Abcd Efg', 'Abc'), false, 'a 3-char candidate cannot use containment');
    assert.equal(matchesTitle('Abc', 'Abc'), true, 'but an exact match below the floor still works');
    // Equal segment counts that differ in content are rejected outright.
    assert.equal(matchesTitle('Tum Hi Ho', 'Tum Hi Na'), false);
    assert.equal(isLeadingOrTrailingRun(['a'], ['a']), false, 'an equal-length run is not containment');
    assert.equal(isLeadingOrTrailingRun([], ['a', 'b']), false, 'an empty run is never containment');
    assert.equal(isLeadingOrTrailingRun(['b'], ['a', 'b']), true, 'a trailing run counts');
    assert.equal(isLeadingOrTrailingRun(['a'], ['a', 'b']), true, 'a leading run counts');
    assert.equal(isLeadingOrTrailingRun(['b'], ['a', 'b', 'c']), false, 'an interior run does not count');
  });
});

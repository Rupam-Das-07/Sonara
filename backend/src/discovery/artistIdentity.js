'use strict';

// Unicode White_Space, deliberately independent of JS \s (which includes BOM).
const WHITE_SPACE = /[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]/u;
const WHITE_SPACE_RUN = /[\u0009-\u000d\u0020\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+/gu;
const RAHMAN_ALIASES = new Set(['a.r.-rahman', 'a-r-rahman', 'ar-rahman', 'a.-r.-rahman']);

function isValidName(value) {
  if (typeof value !== 'string' || !/[\p{L}\p{N}]/u.test(value)) return false;
  for (const char of value) {
    // Iteration combines valid surrogate pairs; any remaining surrogate is invalid.
    if (/\p{Cs}/u.test(char)) return false;
    if (/\p{Cc}/u.test(char) && !WHITE_SPACE.test(char)) return false;
    if (/\p{Cf}/u.test(char) && char !== '\u200c' && char !== '\u200d') return false;
  }
  return true;
}

/** Strict identity key: canonical equivalence without lossy punctuation/accent folding. */
function nameKey(value) {
  if (!isValidName(value)) return null;
  const key = value.normalize('NFC').toLowerCase().normalize('NFC')
    .replace(WHITE_SPACE_RUN, ' ').trim().replace(/ /g, '-');
  return RAHMAN_ALIASES.has(key) ? 'ar-rahman' : key;
}

/** Public identifier; native marks survive, while historical Latin slugs stay stable. */
function slugify(value) {
  if (!isValidName(value)) return '';
  let latinBase = false;
  let folded = '';
  for (const char of value.normalize('NFD')) {
    if (/\p{M}/u.test(char)) {
      if (latinBase && /[\u0300-\u036f]/u.test(char)) continue;
    } else {
      latinBase = /\p{Script=Latin}/u.test(char);
    }
    folded += char;
  }
  return folded.normalize('NFC').toLowerCase().normalize('NFC')
    .replace(/[^\p{L}\p{N}\p{M}\u200c\u200d]+/gu, '-')
    .replace(/^-+|-+$/g, '');
}

module.exports = { nameKey, slugify };

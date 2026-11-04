'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { nameKey, slugify } = require('../../src/discovery/artistIdentity');

test('Hindi and Bengali preserve native letters and marks in identity and public slugs', () => {
  for (const [name, expected] of [
    ['अरिजीत सिंह', 'अरिजीत-सिंह'],
    ['অরিজিৎ সিং', 'অরিজিৎ-সিং'],
  ]) {
    assert.equal(nameKey(name), expected);
    assert.equal(slugify(name), expected);
  }
});

test('Latin public slugs remain compatible without weakening strict name identity', () => {
  for (const [name, expected] of [
    ['Arijit Singh', 'arijit-singh'], ['A.R. Rahman', 'a-r-rahman'],
    ['Beyoncé', 'beyonce'], ['Café Del Mar', 'cafe-del-mar'],
    ['Sigur Rós', 'sigur-ros'], ['The Weeknd', 'the-weeknd'],
  ]) assert.equal(slugify(name), expected);
  assert.notEqual(nameKey('Beyonce'), nameKey('Beyoncé'));
  assert.notEqual(nameKey('AB'), nameKey('A-B'));
  assert.notEqual(nameKey('AC/DC'), nameKey('AC-DC'));
  assert.notEqual(nameKey('Artist.'), nameKey('Artist'));
  assert.equal(nameKey('A B'), nameKey('A-B'));
});

test('only the explicit Rahman aliases share a strict key', () => {
  for (const alias of ['A.R. Rahman', 'A R Rahman', 'AR Rahman', 'A. R. Rahman']) {
    assert.equal(nameKey(alias), 'ar-rahman');
  }
  assert.notEqual(nameKey('A/R Rahman'), nameKey('AR Rahman'));
  assert.notEqual(nameKey('AR. Rahman'), nameKey('AR Rahman'));
});

test('canonical equivalence preserves native distinctions and avoids compatibility folding', () => {
  for (const normalize of [nameKey, slugify]) {
    assert.equal(normalize('क़'), normalize('क़'));
    assert.equal(normalize('Beyonce\u0301'), normalize('Beyoncé'));
    assert.equal(new Set(['क', 'कि', 'क़'].map(normalize)).size, 3);
    assert.equal(normalize('क\u0301'), 'क\u0301');
    assert.equal(normalize('ＡＢ'), 'ａｂ');
    assert.notEqual(normalize('ＡＢ'), normalize('AB'));
    assert.equal(normalize('ক\u200cক\u200dক'), 'ক\u200cক\u200dক');
  }
  assert.equal(slugify('éक\u0301'), 'eक\u0301');
});

test('case conversion and the complete Unicode White_Space set match the shared contract', () => {
  const spaces = '\u0009\u000a\u000b\u000c\u000d\u0020\u0085\u00a0\u1680'
    + '\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a'
    + '\u2028\u2029\u202f\u205f\u3000';
  for (const normalize of [nameKey, slugify]) {
    assert.equal(normalize(`${spaces}ArTIST${spaces}NAME${spaces}`), 'artist-name');
    assert.equal(normalize('𐐀 Artist'), '𐐨-artist');
  }
  assert.equal(nameKey('İ'), 'i\u0307');
});

test('invalid inputs never coerce into an identity or throw', () => {
  for (const input of [null, undefined, 42, false, {}, [], new String('Artist'),
    '', ' \t\u0085', '\u0301', '़', '🎶', '---', '\ud800', '\udc00',
    'Artist\ud800', '\udc00Artist', 'Artist\u0000', 'Artist\u0008',
    'Artist\u007f', 'Artist\u0080', 'Artist\u200b', 'Artist\u202e',
    'Artist\ufeff', 'Artist\u00ad', 'Artist\u2060',
  ]) {
    assert.equal(nameKey(input), null);
    assert.equal(slugify(input), '');
  }
});

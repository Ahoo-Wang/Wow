/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { describe, expect, it } from 'vitest';
import {
  marksNothing,
  parseJson,
  stringifyJson,
  textNumbersBy,
  type TextNumbers,
} from '../src/exactJson.js';

/** A seeded random generator, so a failing case can be reproduced. */
function random(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state * 1664525 + 1013904223) >>> 0;
    return state / 2 ** 32;
  };
}

const STRINGS = [
  '',
  'plain',
  'quote " and backslash \\',
  'controls \b\f\n\r\t',
  'unicode 中文 é',
  'surrogates 😀 😀',
  'lone \uD800',
  '__proto__',
  '\u0000nul',
];
const NUMBERS = [
  '0',
  '-0',
  '1',
  '-12',
  '3.25',
  '1e3',
  '1E-7',
  '2.5e+10',
  '9007199254740993',
  '-9007199254740993',
  '123456789012345678901234567890.123456789',
  '0.1',
  '1.0',
  '1.50',
];

/** A random JSON text, written with random whitespace and escapes. */
function jsonText(next: () => number, depth: number): string {
  const pick = <T>(items: readonly T[]): T =>
    items[Math.floor(next() * items.length)]!;
  const space = () => pick(['', ' ', '\n', '\t', '\r\n  ']);
  const string = () => {
    const raw = pick(STRINGS);
    // Escape some characters as \u, the others as JSON.stringify does.
    return next() < 0.3
      ? `"${[...raw].map(c => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`).join('')}"`
      : JSON.stringify(raw);
  };
  const roll = depth > 6 ? next() * 0.6 : next();
  if (roll < 0.15) return pick(NUMBERS);
  if (roll < 0.3) return string();
  if (roll < 0.4) return pick(['true', 'false', 'null']);
  if (roll < 0.6) return pick(NUMBERS);
  if (roll < 0.8) {
    const items = Array.from({ length: Math.floor(next() * 5) }, () =>
      jsonText(next, depth + 1),
    );
    return `[${space()}${items.join(`${space()},${space()}`)}${space()}]`;
  }
  const members = Array.from({ length: Math.floor(next() * 5) }, () => {
    const key =
      next() < 0.5
        ? string()
        : JSON.stringify(pick(['a', 'b', 'price', 'body', 'name']));
    return `${key}${space()}:${space()}${jsonText(next, depth + 1)}`;
  });
  return `{${space()}${members.join(`${space()},${space()}`)}${space()}}`;
}

describe('exact JSON (review R4-Q, Q-9)', () => {
  it('reads every unmarked value exactly as JSON.parse does', () => {
    const next = random(20261021);
    // A mark on a member no generated text names: the parser runs, marks nothing.
    const marks: TextNumbers = { never: true };
    for (let index = 0; index < 3000; index++) {
      const text = jsonText(next, 0);
      expect(parseJson(text, marks), text).toStrictEqual(JSON.parse(text));
    }
  });

  it('keeps -0, __proto__ as an own member and the last of a repeated member', () => {
    const marks: TextNumbers = { never: true };
    expect(Object.is(parseJson('-0', marks), -0)).toBe(true);
    const parsed = parseJson(
      '{"__proto__": {"x": 1}, "a": 1, "a": 2}',
      marks,
    ) as Record<string, unknown>;
    expect(Object.prototype.hasOwnProperty.call(parsed, '__proto__')).toBe(
      true,
    );
    expect(Object.getPrototypeOf(parsed)).toBe(Object.prototype);
    expect(parsed).toStrictEqual(
      JSON.parse('{"__proto__": {"x": 1}, "a": 1, "a": 2}'),
    );
  });

  it('reads deep nesting and large arrays', () => {
    const marks: TextNumbers = { never: true };
    const deep = `${'['.repeat(2000)}1${']'.repeat(2000)}`;
    expect(parseJson(deep, marks)).toStrictEqual(JSON.parse(deep));
    const large = JSON.stringify(
      Array.from({ length: 100_000 }, (_, i) => ({ i, s: `v${i}` })),
    );
    expect(parseJson(large, marks)).toStrictEqual(JSON.parse(large));
  });

  it('throws a SyntaxError wherever JSON.parse does', () => {
    const marks: TextNumbers = { never: true };
    for (const text of [
      '',
      ' ',
      '{',
      '[1,]',
      '{"a":1,}',
      '01',
      '1.',
      '.5',
      '-',
      '1e',
      '+1',
      'NaN',
      'tru',
      'nul',
      '{"a":1 "b":2}',
      '"\\x"',
      '"\\',
      '"\\u12G4"',
      '"a\nb"',
      '{"a" 1}',
      '{a:1}',
      '[1 2]',
      '1 2',
      '"unterminated',
      "'a'",
    ]) {
      expect(() => JSON.parse(text), text).toThrow(SyntaxError);
      expect(() => parseJson(text, marks), text).toThrow(SyntaxError);
    }
  });

  it('never rounds a marked int64 or decimal past 2^53', () => {
    const text =
      '{"id": 9007199254740993, "price": 12345678901234567.891, "count": 9007199254740993}';
    const marks: TextNumbers = { id: true, price: true };
    expect(parseJson(text, marks)).toStrictEqual({
      id: '9007199254740993',
      price: '12345678901234567.891',
      count: JSON.parse(text).count,
    });
    // Unmarked, the same number reads as JSON.parse reads it, rounded.
    expect((parseJson(text, marks) as { count: number }).count).toBe(
      9007199254740992,
    );
    expect(
      stringifyJson(
        { id: '9007199254740993', price: '12345678901234567.891' },
        marks,
      ),
    ).toBe('{"id":9007199254740993,"price":12345678901234567.891}');
  });

  it('marks items, map values and an event body chosen by its name', () => {
    const marks: TextNumbers = {
      list: {
        '*': {
          state: { lines: { '*': { amount: true } }, totals: { '*': true } },
          events: {
            '*': textNumbersBy('name', 'body', {
              'shop.cart.Paid': { amount: true },
            }),
          },
        },
      },
    };
    const text = JSON.stringify({ total: 1, list: [] }).replace(
      '[]',
      '[{"state":{"lines":[{"amount":1.10,"qty":2}],"totals":{"eur":0.30}},' +
        '"events":[{"body":{"amount":2.50,"n":1},"name":"shop.cart.Paid"},{"name":"shop.cart.Other","body":{"amount":2.50}}]}]',
    );
    expect(parseJson(text, marks)).toStrictEqual({
      total: 1,
      list: [
        {
          state: {
            lines: [{ amount: '1.10', qty: 2 }],
            totals: { eur: '0.30' },
          },
          events: [
            { body: { amount: '2.50', n: 1 }, name: 'shop.cart.Paid' },
            { name: 'shop.cart.Other', body: { amount: 2.5 } },
          ],
        },
      ],
    });
  });

  it('writes marked strings as raw JSON numbers and everything else as JSON.stringify does', () => {
    const marks: TextNumbers = {
      price: true,
      lines: { '*': { amount: true } },
    };
    const at = new Date(0);
    const body = {
      price: '19.990',
      lines: [{ amount: '-0.5', sku: 'a' }],
      at,
      note: undefined,
      n: 1,
    };
    expect(stringifyJson(body, marks)).toBe(
      '{"price":19.990,"lines":[{"amount":-0.5,"sku":"a"}],"at":"1970-01-01T00:00:00.000Z","n":1}',
    );
    expect(stringifyJson({ price: 19.99 }, marks)).toBe('{"price":19.99}');
    expect(
      stringifyJson({ price: '\u0000wow-number-x:0' }, { other: true }),
    ).toBe(JSON.stringify({ price: '\u0000wow-number-x:0' }));
    expect(() => stringifyJson({ price: '19,99' }, marks)).toThrow(TypeError);
    // A marked path through a value that is no object, or one written by its toJSON, is left as it is.
    expect(
      stringifyJson(
        { lines: 3, at },
        { lines: { '*': { amount: true } }, at: { x: true } },
      ),
    ).toBe(JSON.stringify({ lines: 3, at }));
    expect(stringifyJson(body)).toBe(JSON.stringify(body));
  });

  it('reads and writes the marks of a member chosen by its sibling, however deep', () => {
    const lines: TextNumbers = { lines: { '*': { amount: true } } };
    const marks: TextNumbers = {
      '*': textNumbersBy('name', 'body', {
        Placed: lines,
        Nested: { inner: textNumbersBy('kind', 'value', { exact: true }) },
      }),
    };
    const text =
      '[{"body":{"lines":[{"amount":1.10},{"amount":null}],"n":[1]},"name":"Placed"},' +
      '{"body":{"inner":{"value":2.20,"kind":"exact"}},"name":"Nested"},' +
      '{"body":{"inner":{"kind":"other","value":2.20}},"name":"Nested"},' +
      '{"body":{"amount":3.30},"name":7},{"name":"Placed"},{"body":null,"name":"Placed"}]';
    const read = parseJson(text, marks);
    expect(read).toStrictEqual([
      {
        body: { lines: [{ amount: '1.10' }, { amount: null }], n: [1] },
        name: 'Placed',
      },
      { body: { inner: { value: '2.20', kind: 'exact' } }, name: 'Nested' },
      { body: { inner: { kind: 'other', value: 2.2 } }, name: 'Nested' },
      { body: { amount: 3.3 }, name: 7 },
      { name: 'Placed' },
      { body: null, name: 'Placed' },
    ]);
    expect(stringifyJson(read, marks)).toBe(
      '[{"body":{"lines":[{"amount":1.10},{"amount":null}],"n":[1]},"name":"Placed"},' +
        '{"body":{"inner":{"value":2.20,"kind":"exact"}},"name":"Nested"},' +
        '{"body":{"inner":{"kind":"other","value":2.2}},"name":"Nested"},' +
        '{"body":{"amount":3.3},"name":7},{"name":"Placed"},{"body":null,"name":"Placed"}]',
    );
  });

  it('uses plain JSON.parse when nothing is marked', () => {
    expect(marksNothing(undefined)).toBe(true);
    expect(marksNothing({})).toBe(true);
    expect(marksNothing({ a: {} })).toBe(true);
    expect(marksNothing(textNumbersBy('name', 'body', { x: {} }))).toBe(true);
    expect(marksNothing({ a: { b: true } })).toBe(false);
    expect(marksNothing(textNumbersBy('name', 'body', { x: true }))).toBe(
      false,
    );
    expect(parseJson('9007199254740993')).toBe(9007199254740992);
  });

  it('reads a large paged list within a few times JSON.parse', () => {
    const page = JSON.stringify({
      total: 10_000,
      list: Array.from({ length: 10_000 }, (_, i) => ({
        aggregateId: `cart-${i}`,
        version: i,
        state: {
          name: `Cart ${i}`,
          price: 19.99 + i,
          lines: [{ sku: 'a', amount: 1.5 }],
        },
      })),
    });
    const marks: TextNumbers = {
      list: {
        '*': { state: { price: true, lines: { '*': { amount: true } } } },
      },
    };
    const time = (read: () => unknown) => {
      read();
      const start = performance.now();
      for (let i = 0; i < 5; i++) read();
      return (performance.now() - start) / 5;
    };
    const plain = time(() => JSON.parse(page));
    const exact = time(() => parseJson(page, marks));
    console.info(
      `10k-row page (${(page.length / 1024).toFixed(0)} KiB): JSON.parse ${plain.toFixed(1)} ms, parseJson ${exact.toFixed(1)} ms (${(exact / plain).toFixed(1)}x)`,
    );
    // A generous bound: not a benchmark gate, only a guard against a pathological parser.
    expect(exact).toBeLessThan(plain * 20 + 50);
  });
});

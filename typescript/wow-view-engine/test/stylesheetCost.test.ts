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

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import postcss from 'postcss';
import { describe, expect, it } from 'vitest';

/**
 * What a selector costs the browser, read off the stylesheet.
 *
 * A browser matches a selector right to left: it takes each element, tests
 * the last compound, then walks up. A `:has()` in a compound left of a
 * combinator therefore runs once per element that got that far — and when
 * that compound has nothing cheap to fail on first (no type, class, id or
 * attribute of its own), that is once per child of almost every parent on
 * the page. #3439 wrote one such rule; a long stacked bar chart's two
 * thousand bars then took seconds to style, and its story timed out in CI.
 * So a `:has()` left of a combinator stands next to a key of its own.
 */
const STYLESHEET = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../src/styles.css'),
  'utf8',
);

/** A selector's compounds, split at the combinators outside any parentheses. */
function compounds(selector: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let current = '';
  for (const char of selector) {
    if (char === '(') depth++;
    if (char === ')') depth--;
    if (depth === 0 && /[\s>+~]/.test(char)) {
      if (current) parts.push(current);
      current = '';
      continue;
    }
    current += char;
  }
  if (current) parts.push(current);
  return parts;
}

/** A compound with every pseudo-class taken out: what it can fail on cheaply. */
function keyOf(compound: string): string {
  let key = '';
  let depth = 0;
  let pseudo = false;
  for (const char of compound) {
    if (char === '(') depth++;
    else if (char === ')') depth--;
    else if (depth === 0 && char === ':') pseudo = true;
    else if (depth === 0 && /[.#[]/.test(char)) pseudo = false;
    if (depth === 0 && !pseudo && char !== ')') key += char;
  }
  return key.replace(/^\*$/, '');
}

/** The selectors whose `:has()` runs left of a combinator with no key beside it. */
function unkeyedHas(selector: string): string[] {
  return compounds(selector)
    .slice(0, -1)
    .filter(compound => compound.includes(':has(') && keyOf(compound) === '');
}

describe('the stylesheet', () => {
  it('reads the rule #3439 wrote as unkeyed, and its replacement as keyed', () => {
    expect(
      unkeyedHas(
        ".fve-root [data-slot='dashboard-panel'] > [data-slot='card-content'] > :not([data-slot]):has(> [data-slot='record-table']) > :not([data-slot='record-table'])",
      ),
    ).toEqual([":not([data-slot]):has(> [data-slot='record-table'])"]);
    expect(
      unkeyedHas(
        ".fve-root [data-slot='dashboard-panel'] > [data-slot='card-content']:has(> :not([data-slot]) > [data-slot='record-table']) > :not([data-slot]) > :not([data-slot='record-table'])",
      ),
    ).toEqual([]);
    // In the last compound a `:has()` runs once per element that matches
    // the rest of it, which the browser caches; that is not this rule's.
    expect(unkeyedHas('.fve-root :has(> main)')).toEqual([]);
  });

  it('puts no `:has()` left of a combinator without a key beside it', () => {
    const found: string[] = [];
    postcss.parse(STYLESHEET).walkRules(rule => {
      for (const selector of rule.selectors)
        if (unkeyedHas(selector).length > 0) found.push(selector);
    });
    expect(found).toEqual([]);
  });
});

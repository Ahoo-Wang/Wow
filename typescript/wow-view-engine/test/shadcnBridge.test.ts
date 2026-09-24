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
import postcss, { type Rule } from 'postcss';
import { describe, expect, it } from 'vitest';

/**
 * The shadcn bridge (phase 5, 5D, D30 Q46), read from its source.
 *
 * `scripts/verify-package.mjs` holds the built file to the same shape; this
 * says the decision itself in names — which tokens a host's shadcn theme
 * reaches and which four kinds it never does — so a change to either list is
 * a change someone made on purpose. What the bridged tokens resolve to is a
 * browser's to measure (`ShadcnBridge.test.stories.tsx`): jsdom substitutes
 * no `var()`.
 */
const source = (file: string) =>
  postcss.parse(
    readFileSync(
      resolve(dirname(fileURLToPath(import.meta.url)), '../src', file),
      'utf8',
    ),
  );

function rulesOf(file: string): Rule[] {
  const rules: Rule[] = [];
  source(file).walkRules(rule => {
    rules.push(rule);
  });
  return rules;
}

function declarations(rule: Rule): Map<string, string> {
  const found = new Map<string, string>();
  rule.walkDecls(decl => {
    found.set(decl.prop, decl.value);
  });
  return found;
}

const bridge = rulesOf('shadcn-bridge.css');
const bridged = declarations(bridge[0]);
const neutral = rulesOf('themes.css').find(
  ({ selector }) => selector === ":where([data-fve-preset='neutral'])",
)!;

/** What Q46 keeps out, both halves: a host sets these one by one. */
const NOT_BRIDGED = [
  'input',
  'ring',
  'destructive',
  'destructive-foreground',
  'success',
  'warning',
];

/** Derived from bridged tokens rather than set. */
const DERIVED = ['row-hover', 'quiet-foreground'];

describe('the shadcn bridge', () => {
  it('is one weightless rule on the root', () => {
    expect(bridge.map(({ selector }) => selector)).toEqual([':where(:root)']);
  });

  it('points each host variable at the shadcn token of the same name, in both modes', () => {
    for (const [variable, value] of bridged) {
      const token = variable.replace(/^--fve-(dark-)?/, '');
      expect(value, variable).toBe(`var(--${token})`);
    }
    const light = [...bridged.keys()].filter(
      name => !name.startsWith('--fve-dark-'),
    );
    const dark = [...bridged.keys()].filter(name =>
      name.startsWith('--fve-dark-'),
    );
    // A length is a length in either mode: `radius` has no dark half.
    expect(dark.map(name => name.replace('--fve-dark-', '--fve-'))).toEqual(
      light.filter(name => name !== '--fve-radius'),
    );
  });

  it('keeps input, ring, the status colours and the chart colours out', () => {
    for (const token of NOT_BRIDGED) {
      expect(bridged.has(`--fve-${token}`), token).toBe(false);
      expect(bridged.has(`--fve-dark-${token}`), token).toBe(false);
    }
    expect([...bridged.keys()].filter(name => /chart/.test(name))).toEqual([]);
  });

  it('bridges everything else a preset owns', () => {
    const owned = [...declarations(neutral).keys()];
    const expected = owned.filter(
      name =>
        ![...NOT_BRIDGED, ...DERIVED].includes(
          name.replace(/^--fve-(dark-)?/, ''),
        ),
    );
    expect([...bridged.keys()].sort()).toEqual(expected.sort());
  });
});

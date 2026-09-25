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
import {
  presetVariables,
  type TokenEntry,
  TOKENS,
} from '../src/ui/theme/tokens';

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
/** Every variable a preset may write: the registry's preset layer. */
const PRESET_LAYER = (TOKENS as readonly TokenEntry[]).flatMap(presetVariables);

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
const DERIVED = ['quiet-foreground'];

/**
 * A preset's optional groups the bridge leaves alone: shadcn's chart colours
 * are five and start on red, it has no standard name for a shadow, and
 * the chart patterns' pin and a preset's recommended density are not
 * colours a theme has at all. The font stack is bridged (`--font-sans`,
 * themes.md 2.8).
 */
const UNBRIDGED_GROUPS =
  /^(chart-\d+|shadow-(sm|md|lg)|chart-patterns|preset-density)$/;

/**
 * Nor has shadcn a word for one of the engine's own surfaces — a role
 * (theme-architecture.md 4): the grouped ground, a card's lift, a header
 * band, a selected row, a filled control, focus, a part's corner, a weight
 * (D43, S3). Each falls back to the shadcn token the bridge does set, so a
 * host's shadcn theme reaches them through it, and they stay the surface's
 * own unless the host names them.
 */
const ROLES = new Set(
  (TOKENS as readonly TokenEntry[])
    .filter(entry => entry.tier === 'role')
    .map(entry => entry.name),
);

describe('the shadcn bridge', () => {
  it('is one weightless rule on the root, only while no preset is named', () => {
    // Both sit on `<html>` weighing nothing; the bridge used to win or lose
    // by import order. A host naming a preset asks for the preset.
    expect(bridge.map(({ selector }) => selector)).toEqual([
      ':where(:root:not([data-fve-preset]))',
    ]);
  });

  it('points each preset variable at the shadcn token of the same name, in both modes', () => {
    // It writes the preset layer (theme-architecture.md 3, S2), so a host's
    // own `--fve-*` are still read first.
    for (const [variable, value] of bridged) {
      const token = variable.replace(/^--fvp-(dark-)?/, '');
      expect(value, variable).toBe(`var(--${token})`);
    }
    const light = [...bridged.keys()].filter(
      name => !name.startsWith('--fvp-dark-'),
    );
    const dark = [...bridged.keys()].filter(name =>
      name.startsWith('--fvp-dark-'),
    );
    // A length is a length in either mode, and a font stack has no mode:
    // `radius` and `font-sans` have no dark half.
    expect(dark.map(name => name.replace('--fvp-dark-', '--fvp-'))).toEqual(
      light.filter(name => !['--fvp-radius', '--fvp-font-sans'].includes(name)),
    );
  });

  it('keeps input, ring, the status colours, the chart colours and the shadows out', () => {
    for (const token of NOT_BRIDGED) {
      expect(bridged.has(`--fvp-${token}`), token).toBe(false);
      expect(bridged.has(`--fvp-dark-${token}`), token).toBe(false);
    }
    expect(
      [...bridged.keys()].filter(name => /chart|shadow/.test(name)),
    ).toEqual([]);
  });

  it('bridges everything else a preset owns', () => {
    const expected = PRESET_LAYER.filter(name => {
      const token = name.replace(/^--fvp-(dark-)?/, '');
      return (
        ![...NOT_BRIDGED, ...DERIVED].includes(token) &&
        !UNBRIDGED_GROUPS.test(token) &&
        !ROLES.has(token)
      );
    });
    expect([...bridged.keys()].sort()).toEqual(expected.sort());
  });
});

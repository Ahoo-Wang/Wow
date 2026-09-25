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

/**
 * The three layers (theme-architecture.md 3, S2), as the shipped stylesheets
 * resolve them: the host's `--fve-*` read first, a preset's `--fvp-*` next,
 * the built-in value last; and a preset named on an element replacing the
 * one around it whole, because the reset rule empties the preset layer there
 * first.
 *
 * The cascade is the fixture's (`test/fixtures/themeTokens.ts`), which reads
 * the order off each token's `var()` and what the reset empties off the rule
 * itself — so a token that read the preset first, or a preset variable the
 * reset forgot, fails here. `stories/view-engine/ThemeLayers.test.stories.tsx`
 * holds the same promises in a browser.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  CONVENTIONS,
  declared,
  type HostVariables,
  type Mode,
  PRESET_NAMES,
  presetBlocks,
  resetVariables,
  tokenVariable,
} from './fixtures/themeTokens';
import {
  declaredVariable,
  presetVariables,
  type TokenEntry,
  TOKENS,
} from '../src/ui/theme/tokens';

const MODES: readonly Mode[] = ['light', 'dark'];

const ENTRIES: readonly TokenEntry[] = TOKENS;

/** Every token the blocks declare in colour, and the host variable of each half. */
const COLORS = ENTRIES.filter(
  entry => entry.block && entry.kind === 'color' && entry.preset,
);

/** A colour no preset uses, for the host to set. */
const HOST_COLOR = 'oklch(0.5 0.1 123deg)';

/** The host setting every colour token, both halves, on its `:root`. */
const HOST_EVERYTHING: HostVariables = Object.fromEntries(
  COLORS.flatMap(entry =>
    (entry.modes === 2
      ? [`--fve-${entry.name}`, `--fve-dark-${entry.name}`]
      : [`--fve-${entry.name}`]
    ).map(variable => [variable, HOST_COLOR]),
  ),
);

/**
 * A host's own preset as a host writes it after S2: only what it changes,
 * no `initial` anywhere, no group given whole.
 */
const HOST_PRESET = presetBlocks(`
  :where([data-fve-preset='acme']) {
    --fvp-primary: oklch(0.45 0.16 150deg);
    --fvp-dark-primary: oklch(0.75 0.14 150deg);
    --fvp-radius: 0.25rem;
  }
`);

/**
 * The shadcn bridge, as a layer on `<html>` like a preset's (it matches only
 * while `<html>` names none, so only as the outermost layer).
 */
const BRIDGE = (() => {
  const text = readFileSync(
    join(import.meta.dirname, '..', 'src', 'shadcn-bridge.css'),
    'utf8',
  )
    .split(':where(:root:not([data-fve-preset]))')
    .join(":where([data-fve-preset='shadcn-bridge'])");
  return presetBlocks(text);
})();

const EXTRA = new Map([...HOST_PRESET, ...BRIDGE]);

describe("the host's layer", () => {
  it.each(PRESET_NAMES)(
    'beats %s, on the page or pinned inside another preset',
    preset => {
      for (const mode of MODES)
        for (const outer of [[], ['porcelain'], ['shadcn-bridge']]) {
          const tokens = declared(preset, mode, 'semantic', HOST_EVERYTHING, {
            outer,
            extra: EXTRA,
          });
          for (const entry of COLORS)
            expect(
              tokens.get(declaredVariable(entry)!),
              `${entry.name} in ${preset} (${mode}) under ${outer.join() || 'nothing'}`,
            ).toBe(HOST_COLOR);
        }
    },
  );

  it('beats the preset on the one token the host sets and leaves the rest to it', () => {
    const host = { '--fve-primary': HOST_COLOR };
    const alone = declared('azure', 'light', 'semantic', {});
    const withHost = declared('azure', 'light', 'semantic', host);
    expect(withHost.get('--primary')).toBe(HOST_COLOR);
    expect(alone.get('--primary')).not.toBe(HOST_COLOR);
    for (const [token, value] of alone)
      if (token !== '--primary') expect(withHost.get(token), token).toBe(value);
  });
});

describe('the reset rule', () => {
  it('empties every variable a preset may write, and nothing else', () => {
    expect([...resetVariables()].sort()).toEqual(
      ENTRIES.flatMap(presetVariables).sort(),
    );
  });

  // Every built-in preset inside every other one — a surface pinned to it on
  // a page that names another — resolves as it does alone: the outer
  // preset's colours and every group it gave and the inner one did not
  // (porcelain's filled controls and grouped ground under `brand`) are gone.
  it.each(PRESET_NAMES)(
    'lets %s pinned inside any other preset resolve as it does alone',
    preset => {
      for (const mode of MODES)
        for (const convention of CONVENTIONS) {
          const alone = declared(preset, mode, convention);
          for (const outer of PRESET_NAMES)
            expect(
              declared(preset, mode, convention, undefined, { outer: [outer] }),
              `${preset} inside ${outer} (${mode}, ${convention})`,
            ).toEqual(alone);
        }
    },
  );

  it('takes out what the outer preset gave that the inner one leaves out', () => {
    const brand = declared('brand', 'light', 'semantic', undefined, {
      outer: ['porcelain'],
    });
    const porcelain = declared('porcelain', 'light');
    // porcelain fills its controls and separates its grouped ground; brand
    // does neither, so under brand they are unset and the built-in value.
    expect(porcelain.get(tokenVariable('control'))).toBeDefined();
    expect(brand.get(tokenVariable('control'))).toBeUndefined();
    expect(brand.get(tokenVariable('canvas'))).toBe('var(--background)');
  });

  it("lets a host's own preset, written without initial, nest as it does alone", () => {
    for (const mode of MODES) {
      const alone = declared('acme', mode, 'semantic', undefined, {
        extra: EXTRA,
      });
      for (const outer of [...PRESET_NAMES, 'shadcn-bridge'])
        expect(
          declared('acme', mode, 'semantic', undefined, {
            outer: [outer],
            extra: EXTRA,
          }),
          `acme inside ${outer} (${mode})`,
        ).toEqual(alone);
      // What it does not write is the built-in value: neutral's.
      const neutral = declared('neutral', mode);
      for (const [token, value] of neutral)
        if (!['--primary', '--radius'].includes(token))
          expect(alone.get(token), token).toBe(value);
    }
  });

  it('lets a surface pinned to a preset replace what the bridge gave the page', () => {
    for (const preset of PRESET_NAMES)
      for (const mode of MODES)
        expect(
          declared(preset, mode, 'semantic', undefined, {
            outer: ['shadcn-bridge'],
            extra: EXTRA,
          }),
          `${preset} under the bridge (${mode})`,
        ).toEqual(declared(preset, mode));
  });
});

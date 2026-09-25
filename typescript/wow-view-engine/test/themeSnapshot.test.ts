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
 * What every preset resolves to, in each mode and change convention, kept
 * as a file (theme-architecture.md 3.5, 5.5): the theme restructure moves
 * the mechanism — the layers (S2), the roles (S3), the brand as an input
 * (S4), the chart's roles (S5) — and none of it may move a value. Saved at
 * S1, before any of it; a batch that changes this file on purpose says so
 * and why in its pull request, one that did not mean to is caught here.
 *
 * Filed by the registry's name for each token the stylesheet's blocks
 * declare, not by the variable the engine carries it in — S2 moves the
 * engine's own names under `--_fve-*` and the file must still compare. A
 * colour as the arithmetic composes it (sRGB channels and alpha, to six
 * places); anything else — a length, a weight, a shadow — as the text the
 * cascade hands on; a token with no value (an unset control fill) as
 * `unset`.
 */

import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  CONVENTIONS,
  declared,
  PRESET_NAMES,
  resolveTokens,
} from './fixtures/themeTokens';
import { type TokenEntry, TOKENS } from '../src/ui/theme/tokens';

const NAMES = (TOKENS as readonly TokenEntry[])
  .filter(entry => entry.block)
  .map(({ name }) => name);

const place = (channel: number) => channel.toFixed(6);

function snapshot(): string {
  const all: Record<string, Record<string, string>> = {};
  for (const preset of PRESET_NAMES)
    for (const mode of ['light', 'dark'] as const)
      for (const convention of CONVENTIONS) {
        const colors = resolveTokens(preset, mode, convention);
        const text = declared(preset, mode, convention);
        all[`${preset} ${mode} ${convention}`] = Object.fromEntries(
          NAMES.map(name => {
            const color = colors.get(`--${name}`);
            return [
              name,
              color
                ? [color.r, color.g, color.b, color.alpha].map(place).join(' ')
                : (text.get(`--${name}`) ?? 'unset'),
            ];
          }),
        );
      }
  return `${JSON.stringify(all, null, 2)}\n`;
}

describe('the resolved theme', () => {
  it('is what it was when the restructure began', async () => {
    await expect(snapshot()).toMatchFileSnapshot(
      join(import.meta.dirname, 'snapshots', 'resolvedTokens.json'),
    );
  });
});

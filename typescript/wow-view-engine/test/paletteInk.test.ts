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
 * A number written inside a bar, a stacked segment or a heatmap cell takes
 * whichever of the page's two inks stands off the colour more (`inkOn`). So
 * every slot of the palette, in both themes, must have one ink that reads at
 * 4.5:1 — the first blue had neither (4.48 and 4.41) until it was taken a
 * step darker (the user's call, 2026-09-23). This reads the shipped values
 * out of `styles.css` and `themes.css`, so a slot tuned later is held to the
 * same line — and in every preset (phase 5, 5C), with its own inks: the
 * foreground, and the ground the chart stands on, which is the page in a
 * workbench and the card in a board's panel (`groundOf`), so both are
 * measured — a grey page is a dimmer light ink than a white card.
 */

import { describe, expect, it } from 'vitest';
import { contrast, PRESET_NAMES, resolveTokens } from './fixtures/themeTokens';

describe('every palette slot has an ink that reads on it', () => {
  it.each(
    PRESET_NAMES.flatMap(preset =>
      (['light', 'dark'] as const).map(mode => [preset, mode] as const),
    ),
  )('%s, %s', (preset, mode) => {
    const tokens = resolveTokens(preset, mode);
    for (const ground of ['--background', '--card']) {
      const inks = [tokens.get('--foreground')!, tokens.get(ground)!];
      for (let slot = 1; slot <= 8; slot += 1) {
        const fill = tokens.get(`--chart-${slot}`)!;
        const best = Math.max(...inks.map(ink => contrast(ink, fill)));
        expect(best, `chart-${slot} on ${ground}`).toBeGreaterThanOrEqual(4.5);
      }
    }
  });
});

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
 * whichever of the chart's two inks stands off the colour more (`inkOn`,
 * `chart-ink-dark` and `chart-ink-light`). So every slot of the palette, in
 * both themes, must have one ink that reads at 4.5:1 — the first blue had
 * neither (4.48 and 4.41) until it was taken a step darker (the user's
 * call, 2026-09-23). This reads the shipped values out of `styles.css` and
 * `themes.css`, so a slot tuned later is held to the same line — and in
 * every preset (phase 5, 5C), with the inks that preset gives.
 *
 * The inks were the page's own, the foreground and the ground the chart
 * stands on, until a softer foreground and a lighter dark ground left a
 * mid-tone slot with neither (D94: 4.16 for the eighth slot in light, 4.27
 * for the sixth in dark). They are the chart's now, one pair in both modes.
 */

import { describe, expect, it } from 'vitest';
import {
  CHART_FALLBACK,
  concreteColor,
  readChartTheme,
} from '../src/ui/charts/theme.js';
import {
  contrast,
  declared,
  PRESET_NAMES,
  resolveTokens,
} from './fixtures/themeTokens';

describe('every palette slot has an ink that reads on it', () => {
  it.each(
    PRESET_NAMES.flatMap(preset =>
      (['light', 'dark'] as const).map(mode => [preset, mode] as const),
    ),
  )('%s, %s', (preset, mode) => {
    const tokens = resolveTokens(preset, mode);
    const inks = ['dark', 'light'].map(ink =>
      tokens.get(`--_fve-chart-ink-${ink}`)!,
    );
    for (let slot = 1; slot <= 8; slot += 1) {
      const fill = tokens.get(`--chart-${slot}`)!;
      const best = Math.max(...inks.map(ink => contrast(ink, fill)));
      expect(best, `chart-${slot}`).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('whatever the page is written in', () => {
    // A host's own ink and ground move no ink on a mark: a foreground and a
    // dark ground a mid-tone slot could not be read in still clear.
    for (const mode of ['light', 'dark'] as const) {
      const tokens = resolveTokens('neutral', mode, 'semantic', {
        '--fve-foreground': 'oklch(0.24 0.02 262deg)',
        '--fve-dark-background': 'oklch(0.205 0.008 262deg)',
        '--fve-dark-card': 'oklch(0.205 0.008 262deg)',
      });
      const inks = ['dark', 'light'].map(ink =>
        tokens.get(`--_fve-chart-ink-${ink}`)!,
      );
      for (let slot = 1; slot <= 8; slot += 1) {
        const fill = tokens.get(`--chart-${slot}`)!;
        const best = Math.max(...inks.map(ink => contrast(ink, fill)));
        expect(best, `${mode} chart-${slot}`).toBeGreaterThanOrEqual(4.5);
      }
    }
  });
});

describe('a chart reads its two inks off its element', () => {
  it('as the theme gives them, and the built-in pair where nothing does', () => {
    const chart = document.createElement('div');
    document.body.append(chart);
    expect(readChartTheme(chart).ink).toEqual(CHART_FALLBACK.ink);
    chart.style.setProperty('--_fve-chart-ink-dark', 'oklch(0.2 0.02 262)');
    chart.style.setProperty('--_fve-chart-ink-light', 'rgb(250, 250, 250)');
    expect(readChartTheme(chart).ink).toEqual({
      dark: concreteColor('oklch(0.2 0.02 262)'),
      light: 'rgb(250, 250, 250)',
    });
    chart.remove();
  });

  it('and its built-in pair is the stylesheet’s', () => {
    // `CHART_FALLBACK` is a second spelling of the light token block.
    const built = declared('neutral', 'light');
    expect(CHART_FALLBACK.ink).toEqual({
      dark: concreteColor(built.get('--_fve-chart-ink-dark')),
      light: concreteColor(built.get('--_fve-chart-ink-light')),
    });
  });

  it('is a new theme when an ink moves, so the chart redraws', () => {
    const chart = document.createElement('div');
    document.body.append(chart);
    const before = readChartTheme(chart).key;
    chart.style.setProperty('--_fve-chart-ink-dark', 'rgb(1, 2, 3)');
    expect(readChartTheme(chart).key).not.toBe(before);
    chart.remove();
  });
});

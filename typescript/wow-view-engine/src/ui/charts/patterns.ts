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

import type { EChartsCoreOption } from 'echarts/core';
import { useSyncExternalStore } from 'react';

/**
 * Whether the reader's system asks for more contrast
 * (`prefers-contrast: more`), read live — the default for drawing patterns
 * over a chart's colours (decal, docs/design/decisions.md D33 Q57).
 *
 * The palette's light slots — cyan, yellow, magenta — stand at under 3:1
 * against a card (docs/design/ui/analysis.md); a reader who asked their
 * system for more contrast gets a pattern on each series as well as its
 * colour, so two marks side by side are told apart by more than hue. A host
 * pins it either way with `--fve-chart-patterns: on | off` (`ChartTheme`);
 * it is the host's and the reader's, never the view's, and nothing about it
 * is saved (D30 Q41). Where the platform cannot say, no patterns.
 */
export function usePatterns(): boolean {
  return useSyncExternalStore(subscribe, prefersMore, () => false);
}

const QUERY = '(prefers-contrast: more)';

function media(): MediaQueryList | null {
  return typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function'
    ? window.matchMedia(QUERY)
    : null;
}

function prefersMore(): boolean {
  return media()?.matches ?? false;
}

function subscribe(change: () => void): () => void {
  const list = media();
  list?.addEventListener('change', change);
  return () => list?.removeEventListener('change', change);
}

/**
 * `option` with the library's decal patterns on every series: its aria
 * component with the patterns and nothing else. Its generated description
 * stays off — the drawing is one image named by `readChart`, and the
 * numbers are the reading table's (D21) — so the patterns are the only thing
 * it adds. They are drawn from each series' own colour; no colour is added.
 */
export function withPatterns(option: EChartsCoreOption): EChartsCoreOption {
  return {
    ...option,
    aria: { enabled: true, label: { enabled: false }, decal: { show: true } },
  };
}

/**
 * The host's pin, as `--fve-chart-patterns` reads: `on` or `off` pins it,
 * anything else — unset, `auto` — follows the reader's system.
 */
export function patternsPinned(value: string): boolean | undefined {
  const pinned = value.trim().toLowerCase();
  return pinned === 'on' ? true : pinned === 'off' ? false : undefined;
}

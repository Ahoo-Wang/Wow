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

import { isChartColor } from '../../analysis/index.js';
import type { ChartSpec } from '../../model/index.js';

/** Five slots, cycled; the theme owns what they look like. */
const PALETTE = [
  'var(--chart-1)',
  'var(--chart-2)',
  'var(--chart-3)',
  'var(--chart-4)',
  'var(--chart-5)',
];

export function color(index: number): string {
  return PALETTE[index % PALETTE.length];
}

/**
 * The colour the spec pinned for the first of `keys` that names one, and the
 * slot otherwise. A key is a series or category as the kernel labels it, never
 * an internal one: the kernel tags a pivot's key by type and the cartesian
 * family then exchanges it for `s0`, `s1` …, so a spec could not name either
 * if it tried. Nor is it the text shown, an enum's label or a bucket's day:
 * that follows the language and the definition's wording, and a saved key must
 * not.
 *
 * A spec may reach here unvalidated — the stories pass one straight in — and
 * its value ends up inside a `<style>` element, so the kernel's predicate
 * decides again here rather than being trusted to have run.
 */
export function colorOf(
  spec: ChartSpec | undefined,
  index: number,
  ...keys: string[]
): string {
  for (const key of keys) {
    const configured = spec?.colors?.[key];
    if (isChartColor(configured)) return configured;
  }
  return color(index);
}

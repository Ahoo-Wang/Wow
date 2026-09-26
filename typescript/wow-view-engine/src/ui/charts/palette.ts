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
import {
  CHART_COLOR_SLOTS,
  type ChartSpec,
  type FieldTone,
} from '../../model/index.js';

/**
 * The theme's categorical slots, `--chart-1` to `--chart-8`, in the fixed
 * order the stylesheet validated them in: the order is what keeps two
 * neighbouring series apart under colour-vision deficiency, so the slots are
 * handed out in it and never shuffled. The theme owns what they look like.
 */
const PALETTE = Array.from(
  { length: CHART_COLOR_SLOTS },
  (_, index) => `var(--chart-${index + 1})`,
);

/**
 * The slot at `index`. Past the last one it starts again — a pie folds its
 * tail before it gets there (`shapeChart`), so only a series split more
 * ways than the palette has colours still arrives here and repeats one.
 */
export function color(index: number): string {
  return PALETTE[index % PALETTE.length];
}

/**
 * The colour of a pie's merged "Other": a neutral, not a slot. It is not a
 * category — nobody can pick it or pin a colour to it — and a hue would say
 * it is one; the grey reads as "the rest" beside the slots the categories
 * wear. It is the muted ink every surface already carries, so a theme that
 * restyles its greys restyles this one with them.
 */
export const OTHER_COLOR = 'var(--muted-foreground)';

/**
 * The colour the spec pinned for the first of `keys` that names one, and the
 * slot otherwise. A key is a series or category as the kernel labels it, never
 * an internal one: the kernel tags a pivot's key by type, so a spec could
 * not name it if it tried. Nor is it the text shown, an enum's label or a bucket's day:
 * that follows the language and the definition's wording, and a saved key must
 * not.
 *
 * A spec may reach here unvalidated — the stories pass one straight in — and
 * its value reaches the drawing and the legend, so the kernel's predicate
 * decides again here rather than being trusted to have run.
 */
export function colorOf(
  spec: ChartSpec | undefined,
  index: number,
  ...keys: string[]
): string {
  return pinnedColor(spec, ...keys) ?? color(index);
}

/** The colour the spec pinned for the first of `keys` that names one. */
export function pinnedColor(
  spec: ChartSpec | undefined,
  ...keys: string[]
): string | undefined {
  for (const key of keys) {
    const configured = spec?.colors?.[key];
    if (isChartColor(configured)) return configured;
  }
  return undefined;
}

/**
 * What a category's tone paints on a chart: the same role colour its badge
 * is tinted with (`ToneBadge`), so 「不可恢复」 is the danger colour in the
 * table and on the pie alike. `neutral` says nothing about good or bad news
 * and has no colour of its own — a badge draws it grey, and on a chart grey
 * is the merged remainder's (`OTHER_COLOR`) — so it takes a slot, as a
 * category with no tone does.
 */
const TONE_COLORS: Readonly<Record<Exclude<FieldTone, 'neutral'>, string>> = {
  success: 'var(--success)',
  warning: 'var(--warning)',
  danger: 'var(--destructive)',
};

/** The role colour a tone paints with; `undefined` for none or neutral. */
export function toneColor(tone: FieldTone | undefined): string | undefined {
  return tone === undefined || tone === 'neutral'
    ? undefined
    : TONE_COLORS[tone];
}

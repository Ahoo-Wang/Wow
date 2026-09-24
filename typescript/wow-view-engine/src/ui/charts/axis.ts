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

import type { ValueFormat } from '../../model/index.js';
import { compactFormat, formatNumber } from '../display.js';

/**
 * A number as the spec asks for it, in the surface's language — through the
 * same `formatNumber` every other number goes through, so an axis tick is not
 * the one number on the page grouped for the machine rather than the reader.
 * `percent` is a ratio the kernel produced — `deltaOf` divides — and Intl's
 * percent style is what scales it.
 */
export function formatValue(
  value: number,
  format: ValueFormat | undefined,
  locale: string | undefined,
): string {
  if (format === 'percent')
    return formatNumber(
      value,
      { style: 'percent', maximumFractionDigits: 1 },
      locale,
    );
  if (format === 'compact')
    return formatNumber(value, compactFormat(undefined), locale);
  return formatNumber(value, undefined, locale);
}

/** The finest step a share is written to: a tenth of a percent. */
const SHARE_STEP = 0.001;

/**
 * A part of a whole — a slice of a pie — as a percentage with one decimal,
 * always the one: 「36.0%」 beside 「32.5%」, so a column of shares lines
 * up and none reads rounder than it is. A part too small to reach the
 * first decimal is 「<0.1%」, not 「0.0%」: 51 records out of 1.8 million
 * are few, and 「0%」 reads as none — the rare kinds are the ones an
 * operator came to the pie for (audit P1-5, as Metabase writes it). Short
 * of the whole by less than that is 「>99.9%」 for the same reason: a
 * 「100.0%」 beside a 「<0.1%」 would add up to more than everything.
 */
export function formatShare(share: number, locale: string | undefined): string {
  const percent = (value: number) =>
    formatNumber(
      value,
      {
        style: 'percent',
        minimumFractionDigits: 1,
        maximumFractionDigits: 1,
      },
      locale,
    );
  if (share > 0 && share < SHARE_STEP / 2) return `<${percent(SHARE_STEP)}`;
  if (share < 1 && share >= 1 - SHARE_STEP / 2)
    return `>${percent(1 - SHARE_STEP)}`;
  return percent(share);
}

/**
 * The longest a category name is drawn on an axis before it is cut with an
 * ellipsis. The axis sizes itself to the names rather than to a fixed 96px
 * that cut 「OrderItemReservedTrackEventProcessor」 to
 * 「kEventProcessor」 from the left; the whole name is in the tooltip.
 */
export const CATEGORY_LABEL_MAX = 24;

/** A category name as an axis draws it, cut at `CATEGORY_LABEL_MAX`. */
export function categoryTick(value: string | number): string {
  const text = String(value);
  return text.length > CATEGORY_LABEL_MAX
    ? `${text.slice(0, CATEGORY_LABEL_MAX - 1)}…`
    : text;
}

/**
 * Whether every number an axis carries is whole — a count of records, a sum
 * of counts. Such an axis takes no fractional ticks (`minInterval: 1`):
 * between 0 and 2 the scale otherwise puts 0.5 and 1.5, and the metric's
 * own format, which rounds a count, writes them 「1」 and 「2」 — an axis
 * reading 0, 1, 1, 2, 2 (found on the real compensation service,
 * 2026-09-23). Read off the values rather than the metric's kind: an
 * average of counts is not whole, and a sum of whole amounts is. A hole is
 * no number and says nothing either way.
 */
export function allWhole(values: Iterable<number | null | undefined>): boolean {
  for (const value of values)
    if (value !== null && value !== undefined && !Number.isInteger(value))
      return false;
  return true;
}

/** Which numeric axis a series or a line belongs to; the left one by default. */
export function axisId(axis: 'left' | 'right' | undefined): 'left' | 'right' {
  return axis === 'right' ? 'right' : 'left';
}

/** Han, kana and hangul, full-width forms included: scripts set upright. */
const CJK = /[\u2e80-\u9fff\uac00-\ud7af\uf900-\ufaff\uff00-\uffef]/;

/** Whether a title is set flat at its axis's head (`sideTitle`). */
export function titleAtHead(name: string | undefined): boolean {
  return name !== undefined && CJK.test(name);
}

/**
 * The title of an axis that runs up the plot — a value axis of an upright
 * chart, the category axis of one on its side, a scatter's or a heatmap's
 * vertical axis.
 *
 * Latin text turned a quarter reads bottom to top, as Metabase sets it. A
 * title in Chinese turned the same way lies on its side, every character
 * rotated, and no one reads 「金额的总和」 like that (2026-09-23 audit).
 * Stood upright, one character under the next, it would read — but it
 * falls apart on the Latin, digits and brackets titles carry (「金额（CNY）」)
 * and takes the plot's whole height. So it is set flat at the axis's head,
 * over the tick labels and against the axis line: where Chinese BI charts,
 * and the library's own default, put it. Decided by the title's own text,
 * not the page's language — an English page can name a Chinese field.
 *
 * `head` is the end of the axis at the top of the plot: `end` for a value
 * axis, `start` for an inverted one — a category axis listed top down.
 */
export function sideTitle(
  name: string | undefined,
  side: 'left' | 'right',
  head: 'start' | 'end',
  style: Record<string, unknown>,
  gap: number,
): Record<string, unknown> {
  if (!titleAtHead(name))
    return {
      name,
      nameLocation: 'middle',
      nameGap: gap,
      nameMoveOverlap: true,
      nameTextStyle: style,
    };
  return {
    name,
    nameLocation: head,
    nameRotate: 0,
    // Clear of the top tick, which stands centred on the axis's end.
    nameGap: 16,
    nameTextStyle: {
      ...style,
      // Over the tick labels, which stand on the outer side of the line.
      align: side === 'left' ? 'right' : 'left',
      verticalAlign: 'bottom',
    },
  };
}

/**
 * What a value axis is titled when the analyst typed nothing: what it
 * measures, when that is one thing — a split draws one metric many times,
 * which is still one — as Metabase titles its axes. Two metrics on the one
 * axis are named by the legend. On a chart of two axes the legend no longer
 * says which series stands on which, so each axis names all it measures
 * (「记录数、客户数」) rather than leaving a scale nobody can read
 * (2026-09-23 audit). Undefined where there is nothing to say, or a column
 * has no title to say it with.
 */
export function measuredTitle(
  measured: readonly string[],
  twoAxes: boolean,
  column: (alias: string | undefined) => string | undefined,
  join: string,
): string | undefined {
  const metrics = [...new Set(measured)];
  if (metrics.length === 1) return column(metrics[0]);
  if (!twoAxes || metrics.length === 0) return undefined;
  const titles = metrics.map(metric => column(metric));
  return titles.every(title => title !== undefined)
    ? titles.join(join)
    : undefined;
}

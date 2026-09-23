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

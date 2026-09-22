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

import type { AxisSpec, ValueFormat } from '../../model/index.js';
import { formatNumber } from '../display.js';

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
    return formatNumber(value, { notation: 'compact' }, locale);
  return formatNumber(value, undefined, locale);
}

/** A bound the spec pinned; the other end is left to the data. */
export function domainOf(axis: AxisSpec | undefined) {
  if (!axis || (axis.min === undefined && axis.max === undefined))
    return undefined;
  return [axis.min ?? 'auto', axis.max ?? 'auto'] as [
    number | 'auto',
    number | 'auto',
  ];
}

export function tickFormatterOf(
  axis: AxisSpec | undefined,
  locale: string | undefined,
) {
  if (!axis?.format) return undefined;
  return (value: number) => formatValue(value, axis.format, locale);
}

/** Which numeric axis a series or a line belongs to; the left one by default. */
export function axisId(axis: 'left' | 'right' | undefined): 'left' | 'right' {
  return axis === 'right' ? 'right' : 'left';
}

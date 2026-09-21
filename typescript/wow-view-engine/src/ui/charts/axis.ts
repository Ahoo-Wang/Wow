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

/**
 * A number as the spec asks for it. `percent` is a ratio the kernel produced
 * — `deltaOf` divides — so it is the only one that scales before it prints.
 */
export function formatValue(value: number, format?: ValueFormat): string {
  if (format === 'percent')
    return `${(value * 100).toLocaleString(undefined, { maximumFractionDigits: 1 })}%`;
  if (format === 'compact')
    return value.toLocaleString(undefined, { notation: 'compact' });
  return value.toLocaleString();
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

export function tickFormatterOf(axis: AxisSpec | undefined) {
  if (!axis?.format) return undefined;
  return (value: number) => formatValue(value, axis.format);
}

/** Which numeric axis a series or a line belongs to; the left one by default. */
export function axisId(axis: 'left' | 'right' | undefined): 'left' | 'right' {
  return axis === 'right' ? 'right' : 'left';
}

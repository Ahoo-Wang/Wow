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

import type { ChartType, ValueFormat } from '../../model/index.js';
import { formatValue } from './axis.js';
import type { ReadingContext } from './reading.js';

/*
 * The two cells every reading is built from (`reading.ts`,
 * `readingStatistics.ts`): what a drawing is called, and a measured number
 * as its column prints it.
 */

/**
 * `{type}: {measures} by {category}`, and without the tail when nothing
 * names a category — a metric card is one number, not one number per
 * anything. With neither, the family's own name is still better than the
 * `role="application"` and the empty `<title>` this replaced.
 */
export function nameOf(
  ctx: ReadingContext,
  type: ChartType,
  measures: readonly (string | undefined)[],
  category?: string,
): string {
  const kind = ctx.messages.label(`label.chart.type.${type}`, undefined, type);
  const named = measures.filter(
    (measure): measure is string => measure !== undefined,
  );
  if (named.length === 0) return kind;
  const params = {
    type: kind,
    measures: named.join(ctx.messages.label('label.filter.join')),
  };
  return category === undefined
    ? ctx.messages.label('label.chart.figure.plain', params)
    : ctx.messages.label('label.chart.figure', { ...params, category });
}

/**
 * A measured number, read as its own column reads it — the reading table and
 * the marks beside it are the same numbers, so they are the same text. An
 * axis that pinned a `ValueFormat` still wins: that is an instruction about
 * this axis, and a ratio drawn as 25% must not be read out as 0.25.
 */
export function number(
  value: number | null | undefined,
  ctx: ReadingContext,
  format?: ValueFormat,
  alias?: string,
): string {
  if (value === null || value === undefined)
    return ctx.messages.label('label.summary.unavailable');
  return format === undefined
    ? ctx.label(alias, value)
    : formatValue(value, format, ctx.locale);
}

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

import type { AnalysisViewConfig, RecordData } from '../model/index.js';

/**
 * What every chart family reads its rows by: a group value as a key that
 * keeps apart what the query kept apart (`seriesKey`), a number off a row
 * (`num`), and whether a group the rows lack is known to be empty
 * (`absenceReader`).
 */

/**
 * A split value's identity as a string key.
 *
 * Two values a query kept apart must stay apart here: `null` and `''` are two
 * series, `1` and `'1'` are two heatmap cells, and a key that merged them let
 * whichever row came second overwrite the first — wrong numbers, no warning.
 * So only a string is its own key, and everything else carries a type tag. A
 * string that holds the tag character doubles it, which is what keeps the two
 * alphabets from meeting: a tagged key's second character is a type letter,
 * an escaped string's is the tag again.
 *
 * A string is left alone rather than tagged too, because this key is also the
 * legend label of a cartesian pivot series, and a group value is a string in
 * every case that reaches a chart legend.
 */
const TYPE_TAG = '\u0001';

export function seriesKey(value: unknown): string {
  if (typeof value === 'string')
    return value.includes(TYPE_TAG)
      ? value.split(TYPE_TAG).join(`${TYPE_TAG}${TYPE_TAG}`)
      : value;
  if (value === null) return `${TYPE_TAG}n`;
  if (value === undefined) return `${TYPE_TAG}u`;
  if (typeof value === 'number') return `${TYPE_TAG}d${value}`;
  if (typeof value === 'boolean') return `${TYPE_TAG}b${value}`;
  return `${TYPE_TAG}j${JSON.stringify(value) ?? ''}`;
}

export function num(row: RecordData, alias: string): number | null {
  const value = row[alias];
  return typeof value === 'number' ? value : null;
}

/**
 * Whether a group the rows lack had no records — so an additive metric over
 * it is 0 — rather than records the query left out, which nobody can put a
 * number on. `at` is the missing group's values by alias, as far as known.
 *
 * A row is left out by two things. `having` (「只保留」) drops groups by
 * their numbers, so under it nothing absent is known to be empty. The limit
 * cuts the rows at the end of the view's sort: a result shorter than the
 * limit is every group there is, and one that fills it may have lost rows —
 * but only past its last row in the sort's leading dimension, so a group
 * whose leading value comes before that row's is still whole. Sorted by day,
 * newest first, thirty days of a longer history are thirty whole days, and
 * a day between two of them with no row is a day with no records.
 */
export function absenceReader(
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
): (at: Readonly<Record<string, unknown>>) => boolean {
  if (config.having) return () => false;
  const limit = config.limit;
  if (Number.isInteger(limit) && rows.length < limit) return () => true;
  const lead = config.sort[0]?.alias;
  const last = rows[rows.length - 1];
  if (lead === undefined || last === undefined) return () => false;
  const edge = seriesKey(last[lead]);
  return at => owns(at, lead) && seriesKey(at[lead]) !== edge;
}

/**
 * Whether `record` holds `key` itself: a split value may be any string,
 * `toString` included, and `in` would find that one on every object.
 */
export function owns(record: object, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(record, key);
}

/**
 * A group value as text: the one spelling of a category or a split value
 * wherever a string has to name it — the key of `ChartSpec.colors` (see its
 * contract in `model/chart.ts`), a pivot series' legend label, a React key.
 * Nothing and null print as empty, a number or a boolean as `String` has it,
 * anything else as JSON. The kernel labels a split series with it and every
 * chart family looks a pinned colour up by it, so a key written once colours
 * the same category in a pie and in a split.
 */
export function groupKeyText(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean')
    return String(value);
  return JSON.stringify(value) ?? '';
}

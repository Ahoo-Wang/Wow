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

import type {
  AnalysisDateUnit,
  AnalysisGroup,
  FilterTree,
  RecordData,
} from '../model/index.js';
import { readInstant } from '../filter/index.js';
import { bucketRange } from './drill.js';
import { appliedWindow } from './timeAxis.js';

/**
 * Which granularity a time dimension starts at (K4). A fresh dimension used
 * to start at the first unit the field offers, whatever the range: a year
 * of orders cut by the hour is eight thousand buckets nobody asked for, and
 * a week cut by the month is one. The recommendation reads the span the
 * analysis is over — the applied range's conditions on the field first,
 * failing that the buckets a result already has on it — and picks the
 * coarsest offered unit that still cuts it into a readable number of
 * buckets. A hand-picked unit always wins over it; this only answers what a
 * new dimension starts as.
 */

/** Roughly how long each unit is, for counting buckets across a span. */
const UNIT_MS: Record<AnalysisDateUnit, number> = {
  SECOND: 1000,
  MINUTE: 60_000,
  HOUR: 3_600_000,
  DAY: 86_400_000,
  WEEK: 7 * 86_400_000,
  MONTH: 30.4375 * 86_400_000,
  QUARTER: 91.3125 * 86_400_000,
  YEAR: 365.25 * 86_400_000,
};

/** Every unit, finest first. */
const FINE_TO_COARSE: readonly AnalysisDateUnit[] = [
  'SECOND',
  'MINUTE',
  'HOUR',
  'DAY',
  'WEEK',
  'MONTH',
  'QUARTER',
  'YEAR',
];

/** Fewer buckets than this and the chart is a couple of bars; the unit is too coarse. */
const MIN_BUCKETS = 6;

/**
 * The coarsest offered unit that cuts `span` milliseconds into at least a
 * handful of buckets, or the finest offered one when nothing does, or the
 * first offered one when there is no span to read.
 */
export function recommendDateUnit(
  span: number | null,
  offered: readonly AnalysisDateUnit[],
): AnalysisDateUnit {
  const first = offered[0] ?? 'DAY';
  if (span === null || !Number.isFinite(span) || span <= 0) return first;
  const candidates = FINE_TO_COARSE.filter(unit => offered.includes(unit));
  if (candidates.length === 0) return first;
  for (let at = candidates.length - 1; at >= 0; at -= 1) {
    const unit = candidates[at];
    if (span / UNIT_MS[unit] >= MIN_BUCKETS) return unit;
  }
  return candidates[0];
}

/**
 * The span the applied range pins on `field`, in milliseconds, or null when
 * it pins none. Only the conditions the tree ANDs together count — a
 * condition under an OR is one alternative, not a bound — and a window
 * needs both ends: a lower bound alone runs to now, an upper bound alone
 * says nothing about where the data starts.
 */
export function rangeSpan(
  filter: FilterTree,
  field: string,
  now: Date,
  timeZone: string,
): number | null {
  const { from, to } = appliedWindow(filter, field, now, timeZone);
  if (from === null) return null;
  const end = to ?? now.getTime();
  return end > from ? end - from : null;
}

/**
 * The span a result already cut on `field`, from its first bucket's start
 * to its last bucket's end, or null when the result has no time dimension
 * on the field or no rows.
 */
export function resultSpan(
  rows: readonly RecordData[],
  groups: readonly AnalysisGroup[],
  field: string,
  timeZone: string,
): number | null {
  const group = groups.find(
    entry => entry.type === 'DATE_HISTOGRAM' && entry.field === field,
  );
  if (!group || group.type !== 'DATE_HISTOGRAM') return null;
  let first: number | null = null;
  let last: number | null = null;
  for (const row of rows) {
    const start = readInstant(row[group.alias])?.ms;
    if (start === undefined) continue;
    first = first === null ? start : Math.min(first, start);
    last = last === null ? start : Math.max(last, start);
  }
  if (first === null || last === null) return null;
  const end = bucketRange(group.unit, last, group.timeZone ?? timeZone).to;
  return end > first ? end - first : null;
}

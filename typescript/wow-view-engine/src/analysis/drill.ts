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
  AnalysisGroupType,
  AnalysisViewConfig,
  FieldDefinition,
  FilterLeaf,
  FilterNode,
  FilterTree,
  RecordData,
} from '../model/index.js';
import {
  isSimpleTree,
  readInstant,
  type FieldKind,
  type FieldKindRegistry,
} from '../filter/index.js';
import { fitChartSlots } from './chartSlots.js';
import { aliasOf, termsGroup } from './defaults.js';

/**
 * One group of an aggregation result, turned back into the conditions that
 * select the records it was computed from.
 *
 * This is the inverse of bucketing (K1): a `TERMS` group is one value, a
 * `HISTOGRAM` group is the half-open interval `[key, key + interval)`, and a
 * `DATE_HISTOGRAM` group is the bucket `[start, next start)` cut in the zone
 * the histogram was cut in. The analysis kernel hands out conditions and
 * nothing else (K6): what view they open, and under which columns, is the
 * runtime's and the record kernel's to say, because `analysis` and `record`
 * never import each other.
 */

/** The instant a bucket starts, and the instant the next one does. */
export interface BucketRange {
  from: number;
  to: number;
}

const MS = { SECOND: 1000, MINUTE: 60_000, HOUR: 3_600_000 } as const;

/**
 * The bucket a date-histogram key names: `[from, to)` in epoch milliseconds,
 * where `to` is the next bucket's start in `timeZone`.
 *
 * Calendar units advance on the zone's wall clock — a month is the same day
 * of the next month, a week is seven days later — so a bucket that crosses a
 * daylight-saving change is as long as the calendar says, not 24 hours.
 * Clock units advance by their length; Wow cuts hours on the wall clock too,
 * which agrees except across the one hour a year that repeats or is skipped.
 */
export function bucketRange(
  unit: AnalysisDateUnit,
  start: number,
  timeZone: string,
): BucketRange {
  switch (unit) {
    case 'SECOND':
    case 'MINUTE':
    case 'HOUR':
      return { from: start, to: start + MS[unit] };
    default: {
      const wall = zonedParts(start, timeZone);
      const next = advance(wall, unit);
      return { from: start, to: zonedToUtc(next, timeZone) };
    }
  }
}

interface WallClock {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

function advance(
  wall: WallClock,
  unit: Exclude<AnalysisDateUnit, 'SECOND' | 'MINUTE' | 'HOUR'>,
): WallClock {
  switch (unit) {
    case 'DAY':
      return { ...wall, day: wall.day + 1 };
    case 'WEEK':
      return { ...wall, day: wall.day + 7 };
    case 'MONTH':
      return { ...wall, month: wall.month + 1 };
    case 'QUARTER':
      return { ...wall, month: wall.month + 3 };
    case 'YEAR':
      return { ...wall, year: wall.year + 1 };
  }
}

function zonedParts(ms: number, timeZone: string): WallClock {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(new Date(ms));
  const read = (type: Intl.DateTimeFormatPartTypes) =>
    Number(parts.find(part => part.type === type)?.value ?? '0');
  return {
    year: read('year'),
    month: read('month'),
    day: read('day'),
    hour: read('hour'),
    minute: read('minute'),
    second: read('second'),
  };
}

/**
 * The instant a wall-clock time in `timeZone` names. `Date.UTC` carries an
 * overflowing day or month into the next, which is what `advance` relies on.
 * The zone's offset is read at a first guess and once more at the answer,
 * which settles every transition but the repeated hour, where the earlier
 * reading is kept.
 */
function zonedToUtc(wall: WallClock, timeZone: string): number {
  const asUtc = Date.UTC(
    wall.year,
    wall.month - 1,
    wall.day,
    wall.hour,
    wall.minute,
    wall.second,
  );
  const offsetAt = (ms: number) => {
    const there = zonedParts(ms, timeZone);
    return (
      Date.UTC(
        there.year,
        there.month - 1,
        there.day,
        there.hour,
        there.minute,
        there.second,
      ) - ms
    );
  };
  const guess = asUtc - offsetAt(asUtc);
  return asUtc - offsetAt(guess);
}

export interface DrillContext {
  /** The engine's zone: the one a histogram that names none was cut in. */
  timeZone: string;
}

/**
 * The conditions that select the records behind one row of an analysis
 * result, one or two per group, or `null` when no condition can say it.
 *
 * `null` today is an analysis over expanded elements: its groups name the
 * innermost element's fields, and a record view sees root documents, so the
 * row's conditions would have to be written as a predicate on the array —
 * which is a different sentence, and one this kernel does not write yet.
 */
export function drillConditions(
  config: AnalysisViewConfig,
  fields: readonly FieldDefinition[],
  kinds: FieldKindRegistry,
  row: RecordData,
  context: DrillContext,
): FilterNode[] | null {
  if (config.elements && config.elements.length > 0) return null;
  const byName = new Map(fields.map(field => [field.name, field]));
  const leaves: FilterLeaf[] = [];
  for (const group of config.groups) {
    const field = byName.get(group.field);
    if (!field) return null;
    const made = conditionsOf(group, row[group.alias], field, kinds, context);
    if (made === null) return null;
    leaves.push(...made);
  }
  return leaves;
}

function conditionsOf(
  group: AnalysisGroup,
  value: unknown,
  field: FieldDefinition,
  kinds: FieldKindRegistry,
  context: DrillContext,
): FilterLeaf[] | null {
  switch (group.type) {
    case 'TERMS': {
      // The sentinel bucket holds the records that have no value — and any
      // whose value is the sentinel itself, which the condition cannot tell
      // apart and does not try to.
      if (
        value === null ||
        value === undefined ||
        (group.missingKey !== undefined && value === group.missingKey)
      )
        return [{ field: group.field, operator: 'IS_NULL', value: null }];
      return [equal(group.field, value, field, kinds)];
    }
    case 'HISTOGRAM': {
      if (typeof value !== 'number' || !Number.isFinite(value)) return null;
      return [
        { field: group.field, operator: 'GTE', value },
        { field: group.field, operator: 'LT', value: value + group.interval },
      ];
    }
    case 'DATE_HISTOGRAM': {
      const start = readInstant(value)?.ms;
      if (start === undefined) return null;
      const timeZone = group.timeZone ?? context.timeZone;
      const range = bucketRange(group.unit, start, timeZone);
      // `BETWEEN` closes both ends and the bucket is half-open, so the upper
      // bound is the last millisecond inside it (K1). A `LT` on a datetime
      // would say it exactly, but that operator would reach every date
      // editor in the package; a millisecond is cheaper than a control.
      return [
        {
          field: group.field,
          operator: 'BETWEEN',
          value: {
            type: 'absolute',
            from: new Date(range.from).toISOString(),
            to: new Date(range.to - 1).toISOString(),
            timeZone,
          },
        },
      ];
    }
  }
}

/**
 * "This field is this value", in the operator the field's kind understands:
 * `EQ` where the kind has it, otherwise the kind's one-of-these — an enum
 * only knows `IN`, a reference carries its label beside the id.
 */
function equal(
  name: string,
  value: unknown,
  field: FieldDefinition,
  kinds: FieldKindRegistry,
): FilterLeaf {
  const kind = kinds.get(field.kind);
  const scalar = value as string | number | boolean;
  if (kind?.operators.includes('EQ'))
    return { field: name, operator: 'EQ', value: scalar };
  if (field.kind === 'reference')
    return {
      field: name,
      operator: 'IN',
      value: { items: [{ id: scalar, label: String(scalar) }] },
    };
  return { field: name, operator: 'IN', value: [scalar] };
}

/**
 * The filter a drilled view opens under: the analysis view's own conditions
 * with the row's added, flattened into one "all of" group when the analysis
 * filter was one — so the record view opens in simple mode wherever the
 * analysis view was in it — and nested under it otherwise.
 */
export function drillFilter(
  applied: FilterTree,
  conditions: readonly FilterNode[],
): FilterTree {
  return isSimpleTree(applied)
    ? { op: 'and', children: [...applied.children, ...conditions] }
    : { op: 'and', children: [applied, ...conditions] };
}

/**
 * The two follow-up questions that stay in the analysis view (D20 追问):
 * "only this group" narrows the range to the row, "split by" narrows it and
 * asks the same question by another dimension. Both are edits to the view's
 * own config, so they land as a patch the runtime applies — undoable,
 * savable, and marked as a change like any other.
 */
export function focusOn(
  config: AnalysisViewConfig,
  conditions: readonly FilterNode[],
): Pick<AnalysisViewConfig, 'filter' | 'filterMode'> {
  const filter = drillFilter(config.filter, conditions);
  return {
    filter,
    // A row's conditions flatten into a simple tree; only an already
    // advanced range stays one.
    filterMode: isSimpleTree(filter) ? config.filterMode : 'advanced',
  };
}

export function splitBy(
  config: AnalysisViewConfig,
  conditions: readonly FilterNode[],
  group: AnalysisGroup,
): Pick<
  AnalysisViewConfig,
  'filter' | 'filterMode' | 'groups' | 'sort' | 'table' | 'chart'
> {
  const groups = [group];
  return {
    ...focusOn(config, conditions),
    groups,
    // The sort named the dimensions that are gone; the table's columns and
    // the chart's slots follow the new shape.
    sort: [],
    table: { ...config.table, columns: [] },
    chart: fitChartSlots(config.chart, groups, config.metrics),
  };
}

/**
 * The dimension a field becomes when a group is split by it: by value where
 * the field offers it, by the finest date unit it offers, else by a unit
 * band. The alias is the one the defaults use, so the split reads as if the
 * user had picked the field in the editor.
 */
export function groupFor(
  field: FieldDefinition,
  offered: {
    groups: readonly AnalysisGroupType[];
    dateUnits: readonly AnalysisDateUnit[];
  },
  kind?: Pick<FieldKind, 'singleString'>,
): AnalysisGroup {
  const alias = aliasOf(field.name, 'group');
  if (offered.groups.includes('TERMS')) return termsGroup(field, alias, kind);
  if (offered.groups.includes('DATE_HISTOGRAM'))
    return {
      type: 'DATE_HISTOGRAM',
      field: field.name,
      alias,
      unit: offered.dateUnits[0] ?? 'DAY',
    };
  return { type: 'HISTOGRAM', field: field.name, alias, interval: 1 };
}

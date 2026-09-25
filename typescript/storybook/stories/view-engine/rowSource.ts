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

import dayjs from 'dayjs';
import { aggregate, find, Query } from 'mingo';
import type { AnyObject } from 'mingo/types';
import {
  AggregationDateUnit,
  AggregationExpressionOperator,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  ComparisonOperator,
  DEFAULT_PAGINATION,
  DeletionState,
  DerivedExpressionType,
  FilterOperator,
  HavingExpressionType,
  SearchMode,
  SortDirection,
  StringComparison,
  type AggregationExpression,
  type AggregationGroup,
  type AggregationMetric,
  type AggregationQuery,
  type DateHistogramAggregationGroup,
  type DerivedExpression,
  type ElementFilterExpression,
  type FieldSort,
  type FilterExpression,
  type HavingExpression,
} from '@ahoo-wang/wow-client';
import type { RecordData, ViewSource } from '@ahoo-wang/wow-view-engine';

/**
 * A `ViewSource` over rows held in memory that answers each query the way a
 * Wow service does: it filters, sorts, pages and aggregates by what it is
 * asked.
 *
 * A story's table, summary row and chart are then the answer to the query the
 * engine really sent. A canned answer shows rows the filter excludes and sums
 * them into the summary, and a reader takes that for the engine's behaviour.
 *
 * Wow's query language maps onto MongoDB's, so the query is translated and
 * `mingo` evaluates it. What has no translation here is refused, so an
 * operator a story starts to use shows up as a failed query rather than as a
 * plausible wrong answer.
 *
 * Nothing is pre-aggregated. What makes a large set fast sits in front of
 * mingo and leaves the answer alone: a date bucket is worked out once per
 * calendar day (`bucketerOf`); with a `timeField` the rows are kept in its
 * order and a range on it cuts the slice mingo reads (`scoped`); and one
 * source answers an aggregation it has answered before from memory.
 */
export function rowSource(
  rows: readonly RecordData[],
  options: RowSourceOptions = {},
): ViewSource {
  const table = tableOf(rows, options.timeField);
  const answers = new Map<string, RecordData[]>();
  return {
    paged: async query => {
      const matched = select(table, query.filter, query.sort);
      const { index, size } = query.pagination ?? DEFAULT_PAGINATION;
      const start = (index - 1) * size;
      return {
        total: matched.length,
        list: matched.slice(start, start + size).map(projected(query)),
      };
    },
    cursor: async query => {
      const matched = select(table, query.filter, query.sort);
      // An offset stands in for Wow's cursor; both are opaque to the caller.
      const start = Number(query.cursor ?? 0);
      const end = start + (query.size ?? matched.length);
      return {
        list: matched.slice(start, end).map(projected(query)),
        nextCursor: end < matched.length ? String(end) : null,
      };
    },
    aggregate: async query => {
      // The rows never change, so the same query has the same answer. A
      // board's panels and a metric card's comparison send the same totals
      // more than once; each caller still gets its own copy, as from a
      // service, so one caller's edit never reaches another.
      const key = JSON.stringify(query);
      let answer = answers.get(key);
      if (!answer) {
        answer = summarise(table, query);
        if (answers.size >= MEMO_LIMIT) answers.clear();
        answers.set(key, answer);
      }
      return structuredClone(answer);
    },
  };
}

export interface RowSourceOptions {
  /**
   * A time column every row holds as epoch milliseconds, the way a Wow
   * snapshot keeps `firstEventTime`. The rows are kept in its order — a
   * query with no sort of its own reads them oldest first — and a range on it
   * in the filter's top-level AND is cut out by binary search before mingo
   * reads anything. Without it the rows keep the order they came in.
   */
  timeField?: string;
}

/** How many distinct aggregations one source remembers before it starts over. */
const MEMO_LIMIT = 512;

/** The rows a source answers from, with the time column's values beside them. */
interface Table {
  rows: RecordData[];
  timeField?: string;
  times?: Float64Array;
}

function tableOf(rows: readonly RecordData[], timeField?: string): Table {
  if (timeField === undefined) return { rows: [...rows] };
  const timed = rows.map(row => {
    const at = valueAt(row, timeField);
    if (typeof at !== 'number' || !Number.isFinite(at))
      throw new Error(
        `The story source keeps ${timeField} as epoch milliseconds; a row holds ${String(at)}.`,
      );
    return { row, at };
  });
  // A stable sort: rows of the same instant keep the order they came in.
  timed.sort((left, right) => left.at - right.at);
  return {
    rows: timed.map(({ row }) => row),
    timeField,
    times: Float64Array.from(timed, ({ at }) => at),
  };
}

/**
 * The rows a filter can match, before mingo reads them: all of them, or —
 * when the filter's top-level AND bounds the time column with numbers — the
 * slice between those bounds, found by binary search. The whole filter still
 * runs over the slice, the bounds included, so the slice only ever holds
 * more than the answer, never less.
 */
function scoped(
  table: Table,
  filter: FilterExpression | undefined,
): RecordData[] {
  const { rows, timeField, times } = table;
  if (!filter || timeField === undefined || !times) return rows;
  let from = 0;
  let to = rows.length;
  for (const condition of conjuncts(filter)) {
    if (!('field' in condition) || condition.field !== timeField) continue;
    switch (condition.op) {
      case FilterOperator.GT:
      case FilterOperator.GTE:
      case FilterOperator.LT:
      case FilterOperator.LTE:
      case FilterOperator.EQ: {
        const { op, value } = condition;
        if (typeof value !== 'number' || !Number.isFinite(value)) break;
        if (op !== FilterOperator.LT && op !== FilterOperator.LTE)
          from = Math.max(
            from,
            firstAt(times, value, op === FilterOperator.GT),
          );
        if (op !== FilterOperator.GT && op !== FilterOperator.GTE)
          to = Math.min(to, firstAt(times, value, op !== FilterOperator.LT));
        break;
      }
      case FilterOperator.BETWEEN: {
        const { lowerBound, upperBound } = condition;
        if (typeof lowerBound === 'number' && Number.isFinite(lowerBound))
          from = Math.max(from, firstAt(times, lowerBound, false));
        if (typeof upperBound === 'number' && Number.isFinite(upperBound))
          to = Math.min(to, firstAt(times, upperBound, true));
        break;
      }
      default:
        break;
    }
  }
  if (from === 0 && to === rows.length) return rows;
  return from < to ? rows.slice(from, to) : [];
}

/** The conditions every match must meet: an AND's operands, nested ANDs flattened. */
function conjuncts(filter: FilterExpression): FilterExpression[] {
  return filter.op === FilterOperator.AND
    ? filter.operands.flatMap(conjuncts)
    : [filter];
}

/**
 * The index of the first time at or past `value` — strictly past it when
 * `after` — in ascending `times`; `times.length` when there is none.
 */
function firstAt(times: Float64Array, value: number, after: boolean): number {
  let low = 0;
  let high = times.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (after ? times[middle] <= value : times[middle] < value)
      low = middle + 1;
    else high = middle;
  }
  return low;
}

/**
 * A row as the service answers it: only the paths the query's projection
 * includes, when it has one. A source that answered whole rows regardless
 * would let a story pass that reads a field no one asked for — the very
 * thing `rowFields` exists to say.
 */
function projected(query: { projection?: { include?: readonly string[] } }) {
  const include = query.projection?.include;
  if (!include || include.length === 0) return (row: RecordData) => row;
  // Mongo's own projection, as mingo implements it: a path through an array
  // picks that member of every element (`body.bodyType` answers each event
  // as `{ bodyType }`), which is what the service's store does too.
  const spec = Object.fromEntries(include.map(path => [path, 1]));
  return (row: RecordData): RecordData =>
    find<RecordData>([row], {}, spec).all()[0] ?? {};
}

function select(
  table: Table,
  filter: FilterExpression,
  sort: readonly FieldSort[] = [],
): RecordData[] {
  const cursor = find<RecordData>(
    scoped(table, filter),
    withDeletionDefault(filter),
  );
  return (sort.length > 0 ? cursor.sort(sortSpec(sort)) : cursor).all();
}

function sortSpec(sort: readonly FieldSort[]): AnyObject {
  return Object.fromEntries(
    sort.map(({ field, direction }) => [
      field,
      direction === SortDirection.DESC ? -1 : 1,
    ]),
  );
}

/**
 * One aggregation, with Wow's answer shape: a group's key sits beside its
 * metrics rather than under `_id`, and an ungrouped aggregation over nothing
 * still answers its one row — a count of 0 and null elsewhere — where
 * MongoDB's `$group` answers no row at all.
 */
function summarise(table: Table, query: AggregationQuery): RecordData[] {
  if (query.elements?.length)
    return summarise(tableOf(expand(table, query)), {
      ...query,
      filter: undefined,
      elements: undefined,
    });
  const groupBy = query.groupBy ?? [];
  const dense = denseGroup(groupBy);
  // A derived metric is arithmetic over the row the group produced, not an
  // accumulator, so it sits out the `$group` and is computed once the
  // numbers it reads exist.
  const accumulated = query.metrics.filter(
    metric => metric.type !== AggregationMetricType.DERIVED,
  );
  // An aggregation without a filter reads every row. The rows are matched
  // first and only those are marked and bucketed: a mark is a pure function
  // of its row, so the order changes the work, not the answer.
  const filter = query.filter ?? { op: FilterOperator.MATCH_ALL };
  const matches = new Query<RecordData>(withDeletionDefault(filter));
  const matched = scoped(table, filter).filter(row => matches.test(row));
  let marked = bucketed(gated(matched, query.metrics), groupBy);
  // Wow puts no record without a time into a dense histogram (`$ne: null`
  // before the group); the grid runs between the buckets that exist.
  if (dense)
    marked = marked.filter(row => row[`${BUCKET}${dense.alias}`] !== null);
  const grouped = (
    aggregate(marked, [
      {
        $group: {
          _id:
            groupBy.length === 0
              ? null
              : Object.fromEntries(
                  groupBy.map(group => [group.alias, groupKey(group)]),
                ),
          ...Object.fromEntries(
            accumulated.map(metric => [metric.alias, accumulator(metric)]),
          ),
        },
      },
      ...counted(accumulated),
      { $replaceWith: { $mergeObjects: ['$_id', '$$ROOT'] } },
      { $unset: '_id' },
    ]) as RecordData[]
  ).map(row => finished(row, accumulated));
  const filled = dense ? densified(grouped, dense, accumulated) : grouped;
  const answered = filled.map(row => withDerived(row, query.metrics));
  // Wow filters the grouped rows **before** it orders and cuts them, which
  // is the whole point of a having: the top five of what is kept, not what
  // is left of the top five.
  const kept = query.having
    ? answered.filter(row => keeps(query.having!, row))
    : answered;
  const ordered = query.sort?.length
    ? (aggregate(kept, [{ $sort: sortSpec(query.sort) }]) as RecordData[])
    : kept;
  const cut = query.limit ? ordered.slice(0, query.limit) : ordered;
  if (groupBy.length === 0 && cut.length === 0)
    return [withDerived(emptyValues(accumulated), query.metrics)];
  return cut;
}

/**
 * What each metric answers over no record at all: a count of 0, and null
 * for everything that has no value without one.
 */
function emptyValues(metrics: readonly AggregationMetric[]): RecordData {
  return Object.fromEntries(
    metrics.map(metric => [
      metric.alias,
      metric.type === AggregationMetricType.COUNT ||
      metric.type === AggregationMetricType.DISTINCT_COUNT
        ? 0
        : null,
    ]),
  );
}

/**
 * The innermost elements an aggregation counts, as Wow expands them: the
 * root filter picks the records first, then each step of the chain unwinds
 * the array at its path — relative to the step before — and keeps the
 * elements its own filter admits. What is left is summarised as if each
 * element were a record, which is what "the unit of counting is the
 * innermost element" means.
 */
function expand(table: Table, query: AggregationQuery): RecordData[] {
  const filter = query.filter ?? { op: FilterOperator.MATCH_ALL };
  let scope = find<RecordData>(
    scoped(table, filter),
    withDeletionDefault(filter),
  ).all();
  for (const element of query.elements ?? []) {
    scope = scope.flatMap(row => {
      const items = valueAt(row, element.path);
      return Array.isArray(items)
        ? items.filter(
            (item): item is RecordData =>
              item !== null && typeof item === 'object',
          )
        : [];
    });
    if (element.filter)
      scope = find<RecordData>(scope, criteria(element.filter)).all();
  }
  return scope;
}

/**
 * The derived metrics of one grouped row, in declaration order, so a derived
 * metric may read one declared before it. A number that cannot be computed —
 * a missing operand, a division by zero — is `null` rather than `NaN` or
 * `Infinity`: "there is no number here" is what the column has to read as.
 */
function withDerived(
  row: RecordData,
  metrics: readonly AggregationMetric[],
): RecordData {
  const answer: RecordData = { ...row };
  for (const metric of metrics)
    if (metric.type === AggregationMetricType.DERIVED)
      answer[metric.alias] = derive(metric.expression, answer);
  return answer;
}

function derive(expression: DerivedExpression, row: RecordData): number | null {
  switch (expression.type) {
    case DerivedExpressionType.CONSTANT:
      return expression.value;
    case DerivedExpressionType.METRIC_REF: {
      const value = row[expression.metric];
      return typeof value === 'number' && Number.isFinite(value) ? value : null;
    }
    case DerivedExpressionType.BINARY: {
      const left = derive(expression.left, row);
      const right = derive(expression.right, row);
      if (left === null || right === null) return null;
      return arithmetic(expression.operator, left, right);
    }
    default:
      throw new Error('The story source does not evaluate this expression.');
  }
}

function arithmetic(
  operator: AggregationExpressionOperator,
  left: number,
  right: number,
): number | null {
  switch (operator) {
    case AggregationExpressionOperator.ADD:
      return left + right;
    case AggregationExpressionOperator.SUBTRACT:
      return left - right;
    case AggregationExpressionOperator.MULTIPLY:
      return left * right;
    case AggregationExpressionOperator.DIVIDE:
      return right === 0 ? null : left / right;
    default:
      throw new Error(`The story source does not compute ${operator}.`);
  }
}

/**
 * Whether one grouped row survives the having. A group whose metric has no
 * number does not pass any comparison — there is nothing to compare — which
 * is what the tray's note says out loud.
 */
function keeps(having: HavingExpression, row: RecordData): boolean {
  switch (having.type) {
    case HavingExpressionType.AND:
      return having.operands.every(operand => keeps(operand, row));
    case HavingExpressionType.OR:
      return having.operands.some(operand => keeps(operand, row));
    case HavingExpressionType.CONDITION: {
      const value = row[having.metric];
      if (typeof value !== 'number' || !Number.isFinite(value)) return false;
      return compares(having.operator, value, having.value);
    }
    default:
      throw new Error(`The story source does not evaluate ${having.type}.`);
  }
}

function compares(
  operator: ComparisonOperator,
  value: number,
  against: number,
): boolean {
  switch (operator) {
    case ComparisonOperator.EQ:
      return value === against;
    case ComparisonOperator.NE:
      return value !== against;
    case ComparisonOperator.GT:
      return value > against;
    case ComparisonOperator.GTE:
      return value >= against;
    case ComparisonOperator.LT:
      return value < against;
    case ComparisonOperator.LTE:
      return value <= against;
    default:
      throw new Error(`The story source does not compare with ${operator}.`);
  }
}

const HOUR_MS = 3_600_000;
const DAY_MS = 24 * HOUR_MS;

/**
 * The units a bucket shorter than a day is cut at, in milliseconds. Inside a
 * local day the clock does not jump on, such a bucket is a slot of this width
 * counted from local midnight — the wall-clock hour, minute or second, which
 * is what Wow keys a sub-day bucket by in any zone, a half-hour one too.
 */
const SUB_DAY_WIDTHS: Partial<Record<AggregationDateUnit, number>> = {
  [AggregationDateUnit.HOUR]: HOUR_MS,
  [AggregationDateUnit.MINUTE]: 60_000,
  [AggregationDateUnit.SECOND]: 1_000,
};

/** One calendar day in a zone: `[start, end)` and the bucket it falls in. */
interface LocalDay {
  start: number;
  end: number;
  /** The day's bucket, for a unit of a day or more. */
  bucket: number;
  /** A day of 24 hours the zone's offset does not change during. */
  regular: boolean;
}

/**
 * The bucket start of each instant, per (unit, zone), shared by every
 * source: it is a function of the calendar alone, so a bucketer built for one
 * story's rows is right for any other's.
 */
const bucketers = new Map<string, (at: number) => number>();

/**
 * The bucket start an instant falls in, for one unit in one zone: the start
 * of its unit in the zone, in epoch milliseconds, which is the key the
 * service answers a date bucket with.
 *
 * Every bucket of a day or more starts at a local midnight, so the bucket is
 * a property of the calendar day: it is worked out once per day the rows
 * touch and looked up after that, where converting each row into the zone
 * cost a conversion per row per query. A shorter bucket is counted from the
 * day's start (`SUB_DAY_WIDTHS`), except on a day the clock jumps, which is
 * worked out row by row from the wall clock.
 *
 * The week starts on Monday, as `wow-mongo` truncates it (`$dateTrunc` with
 * `startOfWeek: "Monday"`); a quarter starts in January, April, July or
 * October. The zone arithmetic is the platform's (`Intl.DateTimeFormat`):
 * dayjs's timezone plugin answers from the host's own zone rules and was off
 * by an hour on the days the host's clock moves.
 */
function bucketerOf(
  unit: AggregationDateUnit,
  zone: string,
): (at: number) => number {
  const key = `${unit}|${zone}`;
  let bucketer = bucketers.get(key);
  if (!bucketer) {
    bucketer = newBucketer(unit, zone);
    bucketers.set(key, bucketer);
  }
  return bucketer;
}

function newBucketer(
  unit: AggregationDateUnit,
  zone: string,
): (at: number) => number {
  if (!Object.values(AggregationDateUnit).includes(unit))
    throw new Error(`The story source does not bucket by ${unit}.`);
  const clock = wallClock(zone);
  // A UTC day overlaps at most two local days, so each holds a short list.
  const days = new Map<number, LocalDay[]>();
  const dayOf = (at: number): LocalDay => {
    const known = days
      .get(Math.floor(at / DAY_MS))
      ?.find(day => day.start <= at && at < day.end);
    if (known) return known;
    const day = localDay(at, unit, clock);
    const last = Math.floor((day.end - 1) / DAY_MS);
    for (let index = Math.floor(day.start / DAY_MS); index <= last; index++) {
      const held = days.get(index);
      if (held) held.push(day);
      else days.set(index, [day]);
    }
    return day;
  };
  const width = SUB_DAY_WIDTHS[unit];
  if (width === undefined) return at => dayOf(at).bucket;
  return at => {
    const day = dayOf(at);
    if (day.regular)
      return day.start + Math.floor((at - day.start) / width) * width;
    // The wall clock's own remainder, taken off the instant: the hour keeps
    // the offset it is read in, as `wow-mongo` truncates it.
    const wall = clock.wall(at);
    return at - (mod(wall, DAY_MS) % width);
  };
}

/** A zone's wall clock: an instant read as if its wall time were UTC. */
interface WallClock {
  wall(at: number): number;
  /** The instant a wall time names (`instantAt`). */
  instant(wall: number): number;
}

const clocks = new Map<string, WallClock>();

function wallClock(zone: string): WallClock {
  let clock = clocks.get(zone);
  if (clock) return clock;
  const format = new Intl.DateTimeFormat('en-US', {
    timeZone: zone,
    hourCycle: 'h23',
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: 'numeric',
    second: 'numeric',
    era: 'short',
  });
  const wall = (at: number): number => {
    const parts: Record<string, string> = {};
    for (const { type, value } of format.formatToParts(at)) parts[type] = value;
    const year =
      parts.era === 'BC' ? 1 - Number(parts.year) : Number(parts.year);
    const date = new Date(0);
    date.setUTCFullYear(year, Number(parts.month) - 1, Number(parts.day));
    date.setUTCHours(
      Number(parts.hour),
      Number(parts.minute),
      Number(parts.second),
      mod(at, 1_000),
    );
    return date.getTime();
  };
  clock = { wall, instant: local => instantAt(local, wall) };
  clocks.set(zone, clock);
  return clock;
}

/**
 * The instant a wall time names in a zone, as `java.time` resolves one — and
 * so as Wow's dense grid does (`ZonedDateTime`): a wall time the clock passes
 * twice is the earlier instant; one the clock skips is moved later by the
 * gap, so a midnight the zone skips starts its day when the clock resumes.
 */
function instantAt(local: number, wall: (at: number) => number): number {
  const before = wall(local - DAY_MS) - (local - DAY_MS);
  const after = wall(local + DAY_MS) - (local + DAY_MS);
  const named = [local - before, local - after]
    .filter(at => wall(at) === local)
    .sort((left, right) => left - right);
  return named[0] ?? local - before;
}

function mod(value: number, by: number): number {
  return ((value % by) + by) % by;
}

function localDay(
  at: number,
  unit: AggregationDateUnit,
  clock: WallClock,
): LocalDay {
  // The wall date, as a UTC midnight: calendar arithmetic on it is plain.
  const date = clock.wall(at) - mod(clock.wall(at), DAY_MS);
  const start = clock.instant(date);
  const end = clock.instant(date + DAY_MS);
  const regular =
    end - start === DAY_MS &&
    clock.wall(start) - start === clock.wall(end - 1) - (end - 1);
  return { start, end, regular, bucket: dayBucket(date, start, unit, clock) };
}

/**
 * The bucket of the local day on wall date `date` (a UTC midnight), which
 * starts at the instant `start`, for a unit of a day or more.
 */
function dayBucket(
  date: number,
  start: number,
  unit: AggregationDateUnit,
  clock: WallClock,
): number {
  const day = new Date(date);
  const year = day.getUTCFullYear();
  const month = day.getUTCMonth();
  const first = (monthIndex: number) => {
    const midnight = new Date(0);
    midnight.setUTCFullYear(year, monthIndex, 1);
    return clock.instant(midnight.getTime());
  };
  switch (unit) {
    case AggregationDateUnit.WEEK:
      // Days back to Monday: Sunday is day 0 to `Date` and 6 here.
      return clock.instant(date - ((day.getUTCDay() + 6) % 7) * DAY_MS);
    case AggregationDateUnit.MONTH:
      return first(month);
    case AggregationDateUnit.QUARTER:
      return first(month - (month % 3));
    case AggregationDateUnit.YEAR:
      return first(0);
    default:
      return start;
  }
}

/** A time as epoch milliseconds, or `null` for what names no instant. */
function instantOf(at: unknown): number | null {
  if (typeof at === 'number') return Number.isFinite(at) ? at : null;
  if (typeof at !== 'string') return null;
  const parsed = dayjs(at).valueOf();
  return Number.isFinite(parsed) ? parsed : null;
}

/** The zone a date histogram buckets in: its own, or the reader's. */
function zoneOf(group: DateHistogramAggregationGroup): string {
  return group.timeZone ?? Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/**
 * The rows, each marked with the bucket every histogram group puts it in.
 *
 * A `DATE_HISTOGRAM` bucket is the start of its unit in the group's zone, in
 * epoch milliseconds (`bucketerOf`). MongoDB's `$dateTrunc` would do this
 * inside the pipeline; the bucket is worked out here instead, as a gate is,
 * so the zone arithmetic is the platform's and not a translation of it.
 *
 * A `HISTOGRAM` bucket is the lower bound of the band of `interval` the value
 * falls in, counted from zero — `floor(value / interval) * interval`, the key
 * the service answers a number band with.
 */
function bucketed(
  rows: readonly RecordData[],
  groupBy: readonly AggregationGroup[],
): RecordData[] {
  const cuts = groupBy.flatMap(group => {
    if (group.type === AggregationGroupType.HISTOGRAM) {
      const { interval } = group;
      return [
        (row: RecordData): [string, number | null] => {
          const at = valueAt(row, group.field);
          return [
            `${BUCKET}${group.alias}`,
            typeof at === 'number' && Number.isFinite(at)
              ? Math.floor(at / interval) * interval
              : null,
          ];
        },
      ];
    }
    if (group.type !== AggregationGroupType.DATE_HISTOGRAM) return [];
    const bucketOf = bucketerOf(group.unit, zoneOf(group));
    return [
      (row: RecordData): [string, number | null] => {
        const at = instantOf(valueAt(row, group.field));
        return [`${BUCKET}${group.alias}`, at === null ? null : bucketOf(at)];
      },
    ];
  });
  if (cuts.length === 0) return [...rows];
  return rows.map(row => ({
    ...row,
    ...Object.fromEntries(cuts.map(cut => cut(row))),
  }));
}

/**
 * The one dense date histogram of a query, or none. Wow fills empty buckets
 * only when the histogram is the query's only group
 * (`AggregationQuery` refuses `dense` beside another), so that shape is
 * refused here too rather than answered some other way.
 */
function denseGroup(
  groupBy: readonly AggregationGroup[],
): DateHistogramAggregationGroup | undefined {
  const dense = groupBy.find(
    (group): group is DateHistogramAggregationGroup =>
      group.type === AggregationGroupType.DATE_HISTOGRAM &&
      group.dense === true,
  );
  if (dense && groupBy.length > 1)
    throw new Error(
      'The story source fills a dense DATE_HISTOGRAM only when it is the only group.',
    );
  return dense;
}

/** More buckets than any answer can show (Wow's limit is 10,000). */
const MAX_DENSE_BUCKETS = 100_000;

/**
 * How far to look past a bucket's start for the next one, and how far to
 * step while the look still lands in the same bucket. The first look is
 * shorter than the unit can be; each step is shorter than any bucket, so no
 * bucket is stepped over.
 */
const NEXT_BUCKET: Record<AggregationDateUnit, [first: number, step: number]> =
  {
    [AggregationDateUnit.SECOND]: [1_000, 1_000],
    [AggregationDateUnit.MINUTE]: [60_000, 60_000],
    [AggregationDateUnit.HOUR]: [15 * 60_000, 15 * 60_000],
    [AggregationDateUnit.DAY]: [22 * HOUR_MS, HOUR_MS],
    [AggregationDateUnit.WEEK]: [166 * HOUR_MS, HOUR_MS],
    [AggregationDateUnit.MONTH]: [27 * DAY_MS, HOUR_MS],
    [AggregationDateUnit.QUARTER]: [88 * DAY_MS, HOUR_MS],
    [AggregationDateUnit.YEAR]: [364 * DAY_MS, HOUR_MS],
  };

/**
 * The grouped rows of a dense histogram with its empty buckets filled in, in
 * bucket order, as Wow answers `dense`: every bucket of the unit between the
 * first and the last bucket that has records — not beyond them — and each
 * filled bucket answers what its metrics answer over nothing (a count of 0,
 * null elsewhere). A local date the zone skipped is no bucket; the next
 * bucket start after a start is the one the next instant falls in, so a
 * skipped day is stepped over rather than invented.
 */
function densified(
  grouped: readonly RecordData[],
  group: DateHistogramAggregationGroup,
  metrics: readonly AggregationMetric[],
): RecordData[] {
  const byKey = new Map<number, RecordData>();
  for (const row of grouped) byKey.set(row[group.alias] as number, row);
  if (byKey.size === 0) return [];
  const keys = [...byKey.keys()].sort((left, right) => left - right);
  const last = keys[keys.length - 1];
  const bucketOf = bucketerOf(group.unit, zoneOf(group));
  const [first, step] = NEXT_BUCKET[group.unit];
  const answer: RecordData[] = [];
  for (let key = keys[0]; ;) {
    answer.push(
      byKey.get(key) ?? { [group.alias]: key, ...emptyValues(metrics) },
    );
    if (answer.length > MAX_DENSE_BUCKETS)
      throw new Error(
        `The story source fills at most ${MAX_DENSE_BUCKETS} dense buckets.`,
      );
    if (key >= last) break;
    let probe = key + first;
    while (bucketOf(probe) <= key) probe += step;
    key = bucketOf(probe);
  }
  return answer;
}

/** A field of a row by its dotted path; `undefined` where the path ends early. */
function valueAt(row: RecordData, path: string): unknown {
  return path
    .split('.')
    .reduce<unknown>(
      (held, segment) =>
        held !== null && typeof held === 'object'
          ? (held as RecordData)[segment]
          : undefined,
      row,
    );
}

/** The bucket one histogram group reads, kept out of every field's namespace. */
const BUCKET = '__bucket_';

/** The mark one gated metric reads, kept out of every field's namespace. */
const GATE = '__gate_';

/** A metric's own conditions, or none (D20 屏 H). */
function gateOf(metric: AggregationMetric): FilterExpression | undefined {
  return 'filter' in metric ? metric.filter : undefined;
}

/**
 * The rows, each marked with which gated metrics count it.
 *
 * A metric's own conditions decide, per record, whether that record counts
 * toward that one metric — Wow re-expresses them as a guard inside the
 * accumulator, and a real backend evaluates them there. MongoDB's `$group`
 * takes an *expression*, not a query predicate, and there is no translation
 * from one to the other, so each gate is evaluated once per row up front
 * with the same `criteria` the root filter goes through, and the
 * accumulator below reads the mark. The answer is the same; only the moment
 * differs.
 */
function gated(
  rows: readonly RecordData[],
  metrics: readonly AggregationMetric[],
): RecordData[] {
  const gates = metrics.flatMap(metric => {
    const filter = gateOf(metric);
    return filter
      ? [[metric.alias, new Query<RecordData>(criteria(filter))] as const]
      : [];
  });
  if (gates.length === 0) return [...rows];
  return rows.map(row => ({
    ...row,
    ...Object.fromEntries(
      gates.map(([alias, predicate]) => [
        `${GATE}${alias}`,
        predicate.test(row),
      ]),
    ),
  }));
}

function groupKey(group: AggregationGroup): unknown {
  if (
    group.type === AggregationGroupType.DATE_HISTOGRAM ||
    group.type === AggregationGroupType.HISTOGRAM
  )
    return `$${BUCKET}${group.alias}`;
  return group.missingKey === undefined
    ? `$${group.field}`
    : { $ifNull: [`$${group.field}`, group.missingKey] };
}

const ACCUMULATORS: Partial<Record<AggregationFunction, string>> = {
  [AggregationFunction.SUM]: '$sum',
  [AggregationFunction.AVG]: '$avg',
  [AggregationFunction.MIN]: '$min',
  [AggregationFunction.MAX]: '$max',
};

/**
 * The metrics whose values are gathered in the `$group` and summarised once
 * it is done (`finished`): a spread and a percentile are worked out here, in
 * plain arithmetic, rather than by a mingo operator whose edge cases would
 * need checking against the service's one by one.
 */
function gathers(metric: AggregationMetric): boolean {
  return (
    metric.type === AggregationMetricType.PERCENTILE ||
    (metric.type === AggregationMetricType.NUMERIC &&
      (metric.function === AggregationFunction.STDDEV ||
        metric.function === AggregationFunction.VARIANCE))
  );
}

function accumulator(metric: AggregationMetric): AnyObject {
  const gate = gateOf(metric) ? `$${GATE}${metric.alias}` : undefined;
  // A row the metric's conditions leave out contributes nothing at all.
  // `null` is what every accumulator here skips, where a 0 would be a
  // value: it would drag an average down and win a minimum outright.
  const guarded = (value: unknown) =>
    gate ? { $cond: [gate, value, null] } : value;
  if (metric.type === AggregationMetricType.COUNT)
    return { $sum: gate ? { $cond: [gate, 1, 0] } : 1 };
  if (gathers(metric))
    return {
      $push: guarded(
        measured((metric as { expression: AggregationExpression }).expression),
      ),
    };
  if (metric.type === AggregationMetricType.NUMERIC) {
    const name = ACCUMULATORS[metric.function];
    if (name) return { [name]: guarded(measured(metric.expression)) };
  }
  // One value of the field, as `wow-mongo` picks it: the greatest, which
  // skips null, so a group with no value answers null.
  if (metric.type === AggregationMetricType.ANY)
    return { $max: guarded(`$${metric.field}`) };
  // The distinct values, gathered here and counted once the group is done
  // (`counted`); a row the conditions leave out adds `null`, which is not
  // counted.
  if (metric.type === AggregationMetricType.DISTINCT_COUNT)
    return { $addToSet: guarded(measured(metric.expression)) };
  throw new Error(`The story source does not compute ${metric.alias}.`);
}

/**
 * One grouped row with each gathered metric summarised (`gathers`). Only
 * finite numbers contribute, as only numbers do to the service; a metric
 * nothing contributed to answers null.
 *
 * - `STDDEV` and `VARIANCE` are the population's, as `wow-mongo` computes
 *   them with `$stdDevPop` (and squares it for the variance).
 * - `PERCENTILE` is exact: sorted values, the rank `(n − 1) · p / 100`, and
 *   linear interpolation between the two values either side of it. The
 *   service's is an estimate that lands between those same two values (the
 *   bounds its test suite holds each backend to), which is why the screen
 *   writes "≈" beside it either way.
 */
function finished(
  row: RecordData,
  metrics: readonly AggregationMetric[],
): RecordData {
  const gathered = metrics.filter(gathers);
  if (gathered.length === 0) return row;
  const answer: RecordData = { ...row };
  for (const metric of gathered) {
    const pushed = answer[metric.alias];
    const values = (Array.isArray(pushed) ? pushed : []).filter(
      (value): value is number =>
        typeof value === 'number' && Number.isFinite(value),
    );
    answer[metric.alias] =
      values.length === 0
        ? null
        : metric.type === AggregationMetricType.PERCENTILE
          ? percentileOf(values, metric.percentile)
          : spreadOf(
              values,
              (metric as { function: AggregationFunction }).function,
            );
  }
  return answer;
}

function percentileOf(values: number[], percentile: number): number {
  const sorted = [...values].sort((left, right) => left - right);
  const rank = ((sorted.length - 1) * percentile) / 100;
  const below = Math.floor(rank);
  const above = Math.min(below + 1, sorted.length - 1);
  return sorted[below] + (rank - below) * (sorted[above] - sorted[below]);
}

function spreadOf(values: number[], fn: AggregationFunction): number {
  const mean = values.reduce((sum, value) => sum + value, 0) / values.length;
  const variance =
    values.reduce((sum, value) => sum + (value - mean) ** 2, 0) / values.length;
  return fn === AggregationFunction.VARIANCE ? variance : Math.sqrt(variance);
}

/** How many distinct values each `DISTINCT_COUNT` gathered, nulls aside. */
function counted(metrics: readonly AggregationMetric[]): AnyObject[] {
  const distinct = metrics.filter(
    metric => metric.type === AggregationMetricType.DISTINCT_COUNT,
  );
  if (distinct.length === 0) return [];
  return [
    {
      $set: Object.fromEntries(
        distinct.map(({ alias }) => [
          alias,
          {
            $size: {
              $filter: { input: `$${alias}`, cond: { $ne: ['$$this', null] } },
            },
          },
        ]),
      ),
    },
  ];
}

/** MongoDB's arithmetic operator for each of Wow's four. */
const ARITHMETIC: Record<AggregationExpressionOperator, string> = {
  [AggregationExpressionOperator.ADD]: '$add',
  [AggregationExpressionOperator.SUBTRACT]: '$subtract',
  [AggregationExpressionOperator.MULTIPLY]: '$multiply',
  [AggregationExpressionOperator.DIVIDE]: '$divide',
};

/**
 * What one record contributes, before the function summarises it across the
 * group: a field, a number, or one operation over two of those — 金额 − 成本
 * per order, then summed. A formula is computed **per record and then
 * summarised**, which is not the same number as summarising each side and
 * then subtracting whenever the function is not additive.
 */
function measured(expression: AggregationExpression): unknown {
  switch (expression.type) {
    case AggregationExpressionType.FIELD:
      return `$${expression.field}`;
    case AggregationExpressionType.CONSTANT:
      return { $literal: expression.value };
    case AggregationExpressionType.BINARY:
      return {
        [ARITHMETIC[expression.operator]]: [
          measured(expression.left),
          measured(expression.right),
        ],
      };
    default:
      throw new Error('The story source does not compute this expression.');
  }
}

type Filter = FilterExpression | ElementFilterExpression;

/**
 * A Wow source answers only the records that are not deleted unless the
 * query carries a `DELETION` filter of its own — that reading is the
 * source's, not the engine's, which is why a blank deletion condition
 * compiles to nothing (D17-2). The story source keeps the same contract.
 */
function withDeletionDefault(filter: FilterExpression): AnyObject {
  return mentionsDeletion(filter)
    ? criteria(filter)
    : { $and: [criteria(filter), { deleted: { $ne: true } }] };
}

function mentionsDeletion(filter: Filter): boolean {
  switch (filter.op) {
    case FilterOperator.DELETION:
      return true;
    case FilterOperator.AND:
    case FilterOperator.OR:
    case FilterOperator.NOR:
      return filter.operands.some(mentionsDeletion);
    default:
      return false;
  }
}

/**
 * Wow's filter as a MongoDB predicate. `relative` rewrites a field path: an
 * `ELEMENT_MATCH` predicate names element fields by their full path,
 * `items.sku`, where MongoDB's `$elemMatch` wants `sku`.
 */
function criteria(
  filter: Filter,
  relative: (field: string) => string = field => field,
): AnyObject {
  switch (filter.op) {
    case FilterOperator.MATCH_ALL:
      return {};
    case FilterOperator.MATCH_NONE:
      return { $nor: [{}] };
    // The logical and comparison operators carry MongoDB's names in capitals.
    case FilterOperator.AND:
    case FilterOperator.OR:
    case FilterOperator.NOR:
      return {
        [`$${filter.op.toLowerCase()}`]: filter.operands.map(operand =>
          criteria(operand, relative),
        ),
      };
    case FilterOperator.EQ:
    case FilterOperator.NE:
    case FilterOperator.GT:
    case FilterOperator.GTE:
    case FilterOperator.LT:
    case FilterOperator.LTE:
      return {
        [relative(filter.field)]: {
          [`$${filter.op.toLowerCase()}`]: filter.value,
        },
      };
    case FilterOperator.BETWEEN:
      return {
        [relative(filter.field)]: {
          $gte: filter.lowerBound,
          $lte: filter.upperBound,
        },
      };
    case FilterOperator.IN:
      return { [relative(filter.field)]: { $in: filter.values } };
    case FilterOperator.NOT_IN:
      return { [relative(filter.field)]: { $nin: filter.values } };
    // An array field holding every one of the values, as `wow-mongo`
    // translates it (`$all`).
    case FilterOperator.CONTAINS_ALL:
      return { [relative(filter.field)]: { $all: filter.values } };
    // The snapshot's own envelope, which the metadata operators name without
    // a field: the aggregate id and the owner, as `wow-mongo` reads them.
    case FilterOperator.AGGREGATE_ID:
      return { aggregateId: { $eq: filter.value } };
    case FilterOperator.AGGREGATE_IDS:
      return { aggregateId: { $in: filter.values } };
    case FilterOperator.OWNER_ID:
      return { ownerId: { $eq: filter.value } };
    case FilterOperator.CONTAINS:
    case FilterOperator.STARTS_WITH:
    case FilterOperator.ENDS_WITH: {
      const text = filter.value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const pattern =
        filter.op === FilterOperator.STARTS_WITH
          ? `^${text}`
          : filter.op === FilterOperator.ENDS_WITH
            ? `${text}$`
            : text;
      const flags =
        filter.stringComparison === StringComparison.CASE_INSENSITIVE
          ? 'i'
          : '';
      return { [relative(filter.field)]: new RegExp(pattern, flags) };
    }
    case FilterOperator.IS_NULL:
      return { [relative(filter.field)]: { $eq: null } };
    case FilterOperator.IS_NOT_NULL:
      return { [relative(filter.field)]: { $ne: null } };
    // The three readings of Wow's `DELETION`, over a `deleted` flag.
    case FilterOperator.DELETION:
      return filter.state === DeletionState.ALL
        ? {}
        : { deleted: { $eq: filter.state === DeletionState.DELETED } };
    // Full text as the service reads it: a phrase is the words together, in
    // order; terms are any one of them — each without regard to case, in the
    // fields the search names.
    case FilterOperator.SEARCH: {
      if (!filter.fields?.length)
        throw new Error('The story source searches named fields only.');
      const escape = (text: string) =>
        text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const words =
        filter.mode === SearchMode.PHRASE
          ? [filter.query.trim()]
          : filter.query.trim().split(/\s+/);
      return {
        $or: filter.fields.flatMap(field =>
          words.map(word => ({
            [relative(String(field))]: new RegExp(escape(word), 'i'),
          })),
        ),
      };
    }
    case FilterOperator.ELEMENT_MATCH: {
      const prefix = `${filter.field}.`;
      return {
        [relative(filter.field)]: {
          $elemMatch: criteria(filter.predicate, field =>
            field.startsWith(prefix) ? field.slice(prefix.length) : field,
          ),
        },
      };
    }
    default:
      throw new Error(`The story source does not evaluate ${filter.op}.`);
  }
}

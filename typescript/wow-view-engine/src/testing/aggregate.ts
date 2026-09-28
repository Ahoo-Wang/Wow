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

import { Query, find } from 'mingo';
import {
  AGGREGATION_LIMITS,
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  DerivedExpressionType,
  HavingExpressionType,
  SortDirection,
  type AggregationGroup,
  type AggregationMetric,
  type AggregationQuery,
  type DateHistogramAggregationGroup,
  type DatePartAggregationGroup,
  type DerivedExpression,
  type FieldSort,
  type HavingExpression,
} from '@ahoo-wang/wow-client';
import {
  PART_DOMAINS,
  bucketerOf,
  denseKeys,
  parterOf,
  zoneOf,
} from './calendar.js';
import { arithmetic, evaluated } from './expression.js';
import { compares, criteria } from './filter.js';
import { compareValues, instantOf, numberOf, valueAt } from './values.js';
import type { RecordData } from '../model/record.js';

/**
 * One aggregation over the records a query's root filter has already
 * matched, with Wow's answer shape and order:
 *
 * 1. `elements` replace the records by the elements of an array, step by
 *    step — each path relative to the step before, each gate filter naming
 *    the element's own fields; what is left is counted as if each element
 *    were a record.
 * 2. The groups key each record; a metric's own `filter` decides, per
 *    record, whether that record counts toward that one metric.
 * 3. A `dense` date group fills its empty buckets; the derived metrics are
 *    computed over each grouped row; `having` keeps the rows it admits.
 * 4. The rows are sorted by the query's sort, then by every group alias it
 *    does not name, ascending, as Wow appends them; and cut to `limit`
 *    (Wow's default 100) — after the having, so the top five of what is
 *    kept.
 *
 * An ungrouped aggregation always answers its one row, over nothing too: a
 * count of 0 and null for a value nothing contributed to.
 */
export function summarise(
  matched: readonly RecordData[],
  query: AggregationQuery,
): RecordData[] {
  const units = expand(matched, query);
  const groupBy = query.groupBy ?? [];
  const dense = denseGroup(groupBy);
  const metrics = query.metrics.filter(
    metric => metric.type !== AggregationMetricType.DERIVED,
  );
  const gates = metrics.map(metric => {
    const filter = 'filter' in metric ? metric.filter : undefined;
    return filter ? new Query(criteria(filter)) : undefined;
  });
  const keyers = groupBy.map(keyerOf);
  const groups = new Map<string, { keys: unknown[]; units: RecordData[] }>();
  if (groupBy.length === 0) groups.set('[]', { keys: [], units: [] });
  const denseAt = dense ? groupBy.indexOf(dense) : -1;
  for (const unit of units) {
    const keys = keyers.map(keyOf => keyOf(unit));
    // Wow puts no record without a time into a dense histogram.
    if (denseAt >= 0 && keys[denseAt] === null) continue;
    const id = JSON.stringify(keys);
    let group = groups.get(id);
    if (!group) groups.set(id, (group = { keys, units: [] }));
    group.units.push(unit);
  }
  let rows = [...groups.values()].map(({ keys, units: members }) =>
    rowOf(groupBy, keys, metrics, gates, members),
  );
  if (dense) rows = filled(rows, dense, metrics);
  rows = rows.map(row => withDerived(row, query.metrics));
  if (query.having) rows = rows.filter(row => keeps(query.having!, row));
  rows = sorted(rows, query.sort ?? [], groupBy);
  return rows.slice(0, query.limit ?? AGGREGATION_LIMITS.DEFAULT_LIMIT);
}

/** The innermost elements the aggregation counts: the records, when it names none. */
function expand(
  matched: readonly RecordData[],
  query: AggregationQuery,
): RecordData[] {
  let scope = [...matched];
  for (const element of query.elements ?? []) {
    scope = scope.flatMap(record => {
      const items = valueAt(record, element.path);
      return Array.isArray(items)
        ? items.filter(
            (item): item is RecordData =>
              item !== null && typeof item === 'object' && !Array.isArray(item),
          )
        : [];
    });
    if (element.filter) scope = find(scope, criteria(element.filter)).all();
  }
  return scope;
}

/**
 * The one dense date group of a query, or none. Wow fills empty buckets only
 * when the date group is the query's only group (`AggregationQuery` refuses
 * `dense` beside another), so that shape is refused here too.
 */
function denseGroup(
  groupBy: readonly AggregationGroup[],
): DateHistogramAggregationGroup | DatePartAggregationGroup | undefined {
  const dense = groupBy.find(
    (
      group,
    ): group is DateHistogramAggregationGroup | DatePartAggregationGroup =>
      (group.type === AggregationGroupType.DATE_HISTOGRAM ||
        group.type === AggregationGroupType.DATE_PART) &&
      group.dense === true,
  );
  if (dense && groupBy.length > 1)
    throw new Error(
      `The memory source fills a dense ${dense.type} only when it is the only group.`,
    );
  return dense;
}

/**
 * How one group keys a record, as the service answers the key:
 *
 * - `TERMS`: the value — `null` where the field is missing or null, or the
 *   group's `missingKey` there (`$ifNull`); an expression's number.
 * - `HISTOGRAM`: the lower bound of the band of `interval` the number falls
 *   in, counted from zero.
 * - `DATE_HISTOGRAM`: the start of the unit in the group's zone, in epoch
 *   milliseconds (`bucketerOf`): a week starts on Monday, a quarter in
 *   January, April, July or October.
 * - `DATE_PART`: the part's integer on the zone's wall clock — the ISO
 *   weekday 1 (Monday) to 7, the hour 0 to 23, the day, the month 1 to 12.
 */
function keyerOf(group: AggregationGroup): (unit: RecordData) => unknown {
  switch (group.type) {
    case AggregationGroupType.TERMS: {
      const { field, expression, missingKey } = group;
      return unit => {
        const value =
          field === undefined
            ? evaluated(expression, unit)
            : valueAt(unit, field);
        return value ?? missingKey ?? null;
      };
    }
    case AggregationGroupType.HISTOGRAM: {
      const { field, expression, interval } = group;
      return unit => {
        const value =
          field === undefined
            ? evaluated(expression, unit)
            : valueAt(unit, field);
        return typeof value === 'number' && Number.isFinite(value)
          ? Math.floor(value / interval) * interval
          : null;
      };
    }
    case AggregationGroupType.DATE_HISTOGRAM: {
      const bucketOf = bucketerOf(group.unit, zoneOf(group));
      return unit => {
        const at = instantOf(valueAt(unit, group.field));
        return at === null ? null : bucketOf(at);
      };
    }
    case AggregationGroupType.DATE_PART: {
      const partOf = parterOf(group.part, zoneOf(group));
      return unit => {
        const at = instantOf(valueAt(unit, group.field));
        return at === null ? null : partOf(at);
      };
    }
    default:
      throw new Error(
        `The memory source does not group by ${(group as { type: string }).type}.`,
      );
  }
}

/** One grouped row: the group keys, then each metric over its members. */
function rowOf(
  groupBy: readonly AggregationGroup[],
  keys: readonly unknown[],
  metrics: readonly AggregationMetric[],
  gates: readonly (Query | undefined)[],
  members: readonly RecordData[],
): RecordData {
  const row: RecordData = {};
  groupBy.forEach((group, index) => (row[group.alias] = keys[index]));
  metrics.forEach((metric, index) => {
    const gate = gates[index];
    row[metric.alias] = metricOf(
      metric,
      gate ? members.filter(unit => gate.test(unit)) : members,
    );
  });
  return row;
}

/**
 * One metric over the records that count toward it. Only finite numbers
 * contribute to a numeric one (`evaluated`); a metric nothing contributed to
 * answers null, a count 0.
 *
 * - `STDDEV` and `VARIANCE` are the population's, as `$stdDevPop`.
 * - `PERCENTILE` is exact: sorted values, the rank `(n − 1) · p / 100`, and
 *   linear interpolation between the two values either side of it. The
 *   service's is an estimate that lands between those same two values (the
 *   bounds its TCK holds each backend to).
 * - `DISTINCT_COUNT` counts the distinct values, null aside; an array field
 *   contributes each of its elements.
 * - `ANY` answers the greatest value, null aside, as `wow-mongo`'s `$max`.
 * - `FIRST` / `LAST` answer the value on the earliest / latest record by
 *   `orderBy` (the model's `eventTime` when it names none); only a record
 *   with both a value and a time counts, and the first of a tie is kept.
 */
function metricOf(
  metric: AggregationMetric,
  members: readonly RecordData[],
): unknown {
  switch (metric.type) {
    case AggregationMetricType.COUNT:
      return members.length;
    case AggregationMetricType.NUMERIC:
    case AggregationMetricType.PERCENTILE: {
      if (
        metric.type === AggregationMetricType.NUMERIC &&
        (metric.function === AggregationFunction.MIN ||
          metric.function === AggregationFunction.MAX) &&
        metric.expression.type === AggregationExpressionType.FIELD
      )
        return extremeOf(
          members,
          metric.expression.field,
          metric.function === AggregationFunction.MAX,
        );
      const values = members
        .map(unit => evaluated(metric.expression, unit))
        .filter((value): value is number => value !== null);
      if (values.length === 0) return null;
      if (metric.type === AggregationMetricType.PERCENTILE)
        return percentileOf(values, metric.percentile);
      return numericOf(values, metric.function);
    }
    case AggregationMetricType.DISTINCT_COUNT: {
      const { expression } = metric;
      const seen = new Set<string>();
      for (const unit of members) {
        const value =
          expression.type === AggregationExpressionType.FIELD
            ? valueAt(unit, expression.field)
            : evaluated(expression, unit);
        for (const each of Array.isArray(value) ? value : [value])
          if (each !== null && each !== undefined)
            seen.add(JSON.stringify(each));
      }
      return seen.size;
    }
    case AggregationMetricType.ANY: {
      let best: unknown = null;
      for (const unit of members) {
        const value = valueAt(unit, metric.field);
        if (value === null || value === undefined) continue;
        if (best === null || compareValues(value, best) > 0) best = value;
      }
      return best;
    }
    case AggregationMetricType.FIRST:
    case AggregationMetricType.LAST: {
      const first = metric.type === AggregationMetricType.FIRST;
      let best: { at: number; value: unknown } | undefined;
      for (const unit of members) {
        const at = instantOf(valueAt(unit, metric.orderBy ?? 'eventTime'));
        const value = valueAt(unit, metric.field);
        if (at === null || value === null || value === undefined) continue;
        if (!best || (first ? at < best.at : at > best.at))
          best = { at, value };
      }
      return best ? best.value : null;
    }
    default:
      throw new Error(
        `The memory source does not compute ${(metric as { alias: string }).alias}.`,
      );
  }
}

/**
 * `MIN` / `MAX` of a field, as `$min` / `$max` read it: any value compares,
 * in MongoDB's order, so the latest of a time kept as ISO text is that time
 * rather than no number. Null and a missing field are skipped; an array
 * counts as its one number, or not at all.
 */
function extremeOf(
  members: readonly RecordData[],
  field: string,
  greatest: boolean,
): unknown {
  let best: unknown = null;
  for (const unit of members) {
    const raw = valueAt(unit, field);
    const value = typeof raw === 'string' ? raw : numberOf(raw);
    if (value === null) continue;
    const by = best === null ? 1 : compareValues(value, best);
    if (greatest ? by > 0 : by < 0 || best === null) best = value;
  }
  return best;
}

function numericOf(values: number[], fn: AggregationFunction): number | null {
  const sum = values.reduce((total, value) => total + value, 0);
  const mean = sum / values.length;
  let answer: number;
  switch (fn) {
    case AggregationFunction.SUM:
      answer = sum;
      break;
    case AggregationFunction.AVG:
      answer = mean;
      break;
    case AggregationFunction.MIN:
      answer = Math.min(...values);
      break;
    case AggregationFunction.MAX:
      answer = Math.max(...values);
      break;
    case AggregationFunction.STDDEV:
    case AggregationFunction.VARIANCE: {
      const variance =
        values.reduce((total, value) => total + (value - mean) ** 2, 0) /
        values.length;
      answer =
        fn === AggregationFunction.VARIANCE ? variance : Math.sqrt(variance);
      break;
    }
    default:
      throw new Error(`The memory source does not compute ${String(fn)}.`);
  }
  return Number.isFinite(answer) ? answer : null;
}

function percentileOf(values: number[], percentile: number): number {
  const sorted = [...values].sort((left, right) => left - right);
  const rank = ((sorted.length - 1) * percentile) / 100;
  const below = Math.floor(rank);
  const above = Math.min(below + 1, sorted.length - 1);
  return sorted[below] + (rank - below) * (sorted[above] - sorted[below]);
}

/**
 * What each metric answers over no record at all: a count of 0, and null
 * for everything that has no value without one.
 */
function emptyValues(metrics: readonly AggregationMetric[]): RecordData {
  return Object.fromEntries(
    metrics.map(metric => [metric.alias, metricOf(metric, [])]),
  );
}

/**
 * The grouped rows of a dense group with its empty keys filled in, in key
 * order, as Wow answers `dense`: a date part runs through its whole domain,
 * a date histogram through every bucket between its first and last (see
 * `denseKeys`); a filled key answers what its metrics answer over nothing.
 */
function filled(
  rows: readonly RecordData[],
  group: DateHistogramAggregationGroup | DatePartAggregationGroup,
  metrics: readonly AggregationMetric[],
): RecordData[] {
  const byKey = new Map<unknown, RecordData>(
    rows.map(row => [row[group.alias], row]),
  );
  let keys: number[];
  if (group.type === AggregationGroupType.DATE_PART) {
    const [first, last] = PART_DOMAINS[group.part];
    keys = Array.from(
      { length: last - first + 1 },
      (_, index) => first + index,
    );
  } else keys = denseKeys([...byKey.keys()] as number[], group);
  return keys.map(
    key => byKey.get(key) ?? { [group.alias]: key, ...emptyValues(metrics) },
  );
}

/**
 * The derived metrics of one grouped row, in declaration order, so a derived
 * metric may read one declared before it. A number that cannot be computed —
 * a missing operand, a division by zero — is `null`.
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
      const value = arithmetic(expression.operator, left, right);
      return value !== null && Number.isFinite(value) ? value : null;
    }
    default:
      throw new Error(
        `The memory source does not derive ${(expression as { type: string }).type}.`,
      );
  }
}

/**
 * Whether one grouped row survives the having. A metric with no number
 * passes no comparison, range or set — there is nothing to compare — and
 * only `IS_NULL` asks about that.
 */
function keeps(having: HavingExpression, row: RecordData): boolean {
  const numberAt = (metric: string) => {
    const value = row[metric];
    return typeof value === 'number' && Number.isFinite(value) ? value : null;
  };
  switch (having.type) {
    case HavingExpressionType.AND:
      return having.operands.every(operand => keeps(operand, row));
    case HavingExpressionType.OR:
      return having.operands.some(operand => keeps(operand, row));
    case HavingExpressionType.CONDITION: {
      const value = numberAt(having.metric);
      return value !== null && compares(having.operator, value, having.value);
    }
    case HavingExpressionType.BETWEEN: {
      const value = numberAt(having.metric);
      return value !== null && value >= having.lower && value <= having.upper;
    }
    case HavingExpressionType.IN: {
      const value = numberAt(having.metric);
      return value !== null && having.values.includes(value);
    }
    case HavingExpressionType.IS_NULL:
      return (numberAt(having.metric) === null) !== (having.negated === true);
    default:
      throw new Error(
        `The memory source does not evaluate ${(having as { type: string }).type}.`,
      );
  }
}

/**
 * The rows in the query's order, then by each group alias the sort does not
 * name, ascending — Wow appends them so ties come out the same every time.
 * Values compare in MongoDB's order: null before any number, so a null sorts
 * first ascending and last descending.
 */
function sorted(
  rows: readonly RecordData[],
  sort: readonly FieldSort[],
  groupBy: readonly AggregationGroup[],
): RecordData[] {
  const named = new Set(sort.map(({ field }) => field));
  const order = [
    ...sort.map(({ field, direction }) => ({
      field,
      sign: direction === SortDirection.DESC ? -1 : 1,
    })),
    ...groupBy
      .filter(({ alias }) => !named.has(alias))
      .map(({ alias }) => ({ field: alias, sign: 1 })),
  ];
  if (order.length === 0) return [...rows];
  return [...rows].sort((left, right) => {
    for (const { field, sign } of order) {
      const by = compareValues(left[field], right[field]);
      if (by !== 0) return by * sign;
    }
    return 0;
  });
}

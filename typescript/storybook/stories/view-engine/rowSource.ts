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

import { aggregate, find } from 'mingo';
import type { AnyObject } from 'mingo/types';
import {
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
  type DerivedExpression,
  type ElementFilterExpression,
  type FieldSort,
  type FilterExpression,
  type HavingExpression,
} from '@ahoo-wang/fetcher-wow';
import type { RecordData, ViewSource } from '@ahoo-wang/fetcher-view-engine';

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
 */
export function rowSource(rows: readonly RecordData[]): ViewSource {
  return {
    paged: async query => {
      const matched = select(rows, query.filter, query.sort);
      const { index, size } = query.pagination ?? DEFAULT_PAGINATION;
      const start = (index - 1) * size;
      return {
        total: matched.length,
        list: matched.slice(start, start + size).map(projected(query)),
      };
    },
    cursor: async query => {
      const matched = select(rows, query.filter, query.sort);
      // An offset stands in for Wow's cursor; both are opaque to the caller.
      const start = Number(query.cursor ?? 0);
      const end = start + (query.size ?? matched.length);
      return {
        list: matched.slice(start, end).map(projected(query)),
        nextCursor: end < matched.length ? String(end) : null,
      };
    },
    aggregate: async query => summarise(rows, query),
  };
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
  rows: readonly RecordData[],
  filter: FilterExpression,
  sort: readonly FieldSort[] = [],
): RecordData[] {
  const cursor = find<RecordData>([...rows], withDeletionDefault(filter));
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
function summarise(
  rows: readonly RecordData[],
  query: AggregationQuery,
): RecordData[] {
  if (query.elements?.length)
    return summarise(expand(rows, query), {
      ...query,
      filter: undefined,
      elements: undefined,
    });
  const groupBy = query.groupBy ?? [];
  // A derived metric is arithmetic over the row the group produced, not an
  // accumulator, so it sits out the `$group` and is computed once the
  // numbers it reads exist.
  const accumulated = query.metrics.filter(
    metric => metric.type !== AggregationMetricType.DERIVED,
  );
  const grouped = aggregate(gated(rows, query.metrics), [
    // An aggregation without a filter reads every row.
    {
      $match: withDeletionDefault(
        query.filter ?? { op: FilterOperator.MATCH_ALL },
      ),
    },
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
    { $replaceWith: { $mergeObjects: ['$_id', '$$ROOT'] } },
    { $unset: '_id' },
  ]) as RecordData[];
  const answered = grouped.map(row => withDerived(row, query.metrics));
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
    return [
      withDerived(
        Object.fromEntries(
          accumulated.map(metric => [
            metric.alias,
            metric.type === AggregationMetricType.COUNT ? 0 : null,
          ]),
        ),
        query.metrics,
      ),
    ];
  return cut;
}

/**
 * The innermost elements an aggregation counts, as Wow expands them: the
 * root filter picks the records first, then each step of the chain unwinds
 * the array at its path — relative to the step before — and keeps the
 * elements its own filter admits. What is left is summarised as if each
 * element were a record, which is what "the unit of counting is the
 * innermost element" means.
 */
function expand(
  rows: readonly RecordData[],
  query: AggregationQuery,
): RecordData[] {
  let scope = find<RecordData>(
    [...rows],
    withDeletionDefault(query.filter ?? { op: FilterOperator.MATCH_ALL }),
  ).all();
  for (const element of query.elements ?? []) {
    scope = scope.flatMap(row => {
      const items = element.path
        .split('.')
        .reduce<unknown>(
          (value, segment) =>
            value !== null && typeof value === 'object'
              ? (value as RecordData)[segment]
              : undefined,
          row,
        );
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
    return filter ? [[metric.alias, criteria(filter)] as const] : [];
  });
  if (gates.length === 0) return [...rows];
  return rows.map(row => ({
    ...row,
    ...Object.fromEntries(
      gates.map(([alias, predicate]) => [
        `${GATE}${alias}`,
        find<RecordData>([row], predicate).all().length > 0,
      ]),
    ),
  }));
}

function groupKey(group: AggregationGroup): unknown {
  if (group.type !== AggregationGroupType.TERMS)
    throw new Error(`The story source does not group by ${group.type}.`);
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

function accumulator(metric: AggregationMetric): AnyObject {
  const gate = gateOf(metric) ? `$${GATE}${metric.alias}` : undefined;
  if (metric.type === AggregationMetricType.COUNT)
    return { $sum: gate ? { $cond: [gate, 1, 0] } : 1 };
  if (metric.type === AggregationMetricType.NUMERIC) {
    const name = ACCUMULATORS[metric.function];
    if (name) {
      const value = measured(metric.expression);
      // A row the metric's conditions leave out contributes nothing at all.
      // `null` is what every accumulator here skips, where a 0 would be a
      // value: it would drag an average down and win a minimum outright.
      return { [name]: gate ? { $cond: [gate, value, null] } : value };
    }
  }
  throw new Error(`The story source does not compute ${metric.alias}.`);
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

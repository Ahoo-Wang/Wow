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
  AggregationExpressionType,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  DEFAULT_PAGINATION,
  FilterOperator,
  SortDirection,
  StringComparison,
  type AggregationGroup,
  type AggregationMetric,
  type AggregationQuery,
  type ElementFilterExpression,
  type FieldSort,
  type FilterExpression,
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
        list: matched.slice(start, start + size),
      };
    },
    cursor: async query => {
      const matched = select(rows, query.filter, query.sort);
      // An offset stands in for Wow's cursor; both are opaque to the caller.
      const start = Number(query.cursor ?? 0);
      const end = start + (query.size ?? matched.length);
      return {
        list: matched.slice(start, end),
        nextCursor: end < matched.length ? String(end) : null,
      };
    },
    aggregate: async query => summarise(rows, query),
  };
}

function select(
  rows: readonly RecordData[],
  filter: FilterExpression,
  sort: readonly FieldSort[] = [],
): RecordData[] {
  const cursor = find<RecordData>([...rows], criteria(filter));
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
  if (query.elements?.length || query.having)
    throw new Error('The story source does not evaluate elements or having.');
  const groupBy = query.groupBy ?? [];
  const answered = aggregate(
    [...rows],
    [
      // An aggregation without a filter reads every row.
      { $match: query.filter ? criteria(query.filter) : {} },
      {
        $group: {
          _id:
            groupBy.length === 0
              ? null
              : Object.fromEntries(
                  groupBy.map(group => [group.alias, groupKey(group)]),
                ),
          ...Object.fromEntries(
            query.metrics.map(metric => [metric.alias, accumulator(metric)]),
          ),
        },
      },
      { $replaceWith: { $mergeObjects: ['$_id', '$$ROOT'] } },
      { $unset: '_id' },
      ...(query.sort?.length ? [{ $sort: sortSpec(query.sort) }] : []),
      ...(query.limit ? [{ $limit: query.limit }] : []),
    ],
  );
  if (groupBy.length === 0 && answered.length === 0)
    return [
      Object.fromEntries(
        query.metrics.map(metric => [
          metric.alias,
          metric.type === AggregationMetricType.COUNT ? 0 : null,
        ]),
      ),
    ];
  return answered;
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
  if (metric.type === AggregationMetricType.COUNT) return { $sum: 1 };
  if (metric.type === AggregationMetricType.NUMERIC) {
    const name = ACCUMULATORS[metric.function];
    const { expression } = metric;
    if (name && expression.type === AggregationExpressionType.FIELD)
      return { [name]: `$${expression.field}` };
  }
  throw new Error(`The story source does not compute ${metric.alias}.`);
}

type Filter = FilterExpression | ElementFilterExpression;

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

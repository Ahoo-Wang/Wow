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

import type { FieldSort, SortDirection } from '../sort.js';
import type { AggregationMetricType } from './metrics.js';
import type { AggregationDateUnit } from './groups.js';
import { AggregationExpressionOperator } from './types.js';

/** The kind of a {@link DerivedExpression} node, sent as its `type`. */
export enum DerivedExpressionType {
  /** The value of another metric of the row, by its alias. */
  METRIC_REF = 'metric-ref',
  /** A number. */
  CONSTANT = 'constant',
  /** Two operands and an {@link AggregationExpressionOperator}. */
  BINARY = 'binary',
  /** A window: the metric summed over a partition of the groups. */
  TOTAL = 'total',
  /** A window: the metric accumulated along an order, or its moving average. */
  RUNNING = 'running',
  /** A window: the metric of an earlier period along a date group. */
  PREVIOUS = 'previous',
  /** A window: the dense rank of the metric within a partition. */
  RANK = 'rank',
}

/**
 * The window functions of derived expressions, as a storage declares them in
 * `analysis.windows` of its descriptor.
 */
export enum WindowFunction {
  TOTAL = 'total',
  RUNNING = 'running',
  PREVIOUS = 'previous',
  RANK = 'rank',
}

/** What a `RUNNING` window computes over its frame. */
export enum RunningFunction {
  SUM = 'sum',
  AVG = 'avg',
}

/**
 * A window over the groups of the aggregation. Wow computes it over every
 * group the filter selects, before `having`, `sort` and `limit`; `metric`
 * names a non-derived numeric metric declared before the derived one, and
 * `within`, `order` and `along` name groups of the query. Wow 9.9 and later,
 * where `analysis.windows` lists the function.
 */
export type DerivedWindow =
  | {
      type: DerivedExpressionType.TOTAL;
      /** A `COUNT` or `SUM` metric. */
      metric: string;
      /** The partition's group aliases; every group when absent. */
      within?: string[];
    }
  | {
      type: DerivedExpressionType.RUNNING;
      /** A `COUNT` or `SUM` metric for `SUM`; any numeric metric for `AVG`. */
      metric: string;
      /** A group alias or a non-derived numeric metric, with its direction. */
      order: FieldSort;
      within?: string[];
      /** The last this many groups; every group up to this one when absent. */
      frame?: number;
      /** `SUM` when absent. */
      function?: RunningFunction;
    }
  | {
      type: DerivedExpressionType.PREVIOUS;
      metric: string;
      /** The alias of a `DATE_HISTOGRAM` group. */
      along: string;
      /** How many steps back; 1 when absent. */
      shift?: number;
      /** Step by this calendar period instead of by one bucket of `along`. */
      period?: AggregationDateUnit;
    }
  | {
      type: DerivedExpressionType.RANK;
      metric: string;
      within?: string[];
      /** `DESC` when absent. */
      direction?: SortDirection;
    };

/**
 * Post-aggregation arithmetic; references must name earlier metrics that are
 * not `ANY`, `FIRST` or `LAST`.
 */
export type DerivedExpression =
  | { type: DerivedExpressionType.METRIC_REF; metric: string }
  | { type: DerivedExpressionType.CONSTANT; value: number }
  | {
      type: DerivedExpressionType.BINARY;
      operator: AggregationExpressionOperator;
      left: DerivedExpression;
      right: DerivedExpression;
    }
  | DerivedWindow;

/**
 * A metric computed from metrics declared before it in the same query, after
 * aggregation; {@link aggregation.derived}. Wow 9.1 and later.
 */
export interface DerivedAggregationMetric {
  type: AggregationMetricType.DERIVED;
  /** The arithmetic over the other metrics. */
  expression: DerivedExpression;
  /** The name of the metric's column in the result rows. */
  alias: string;
}

/**
 * The builders of a derived metric's arithmetic, handed to the callback of
 * `aggregation.derived(d => …, alias)`: references to other metrics of the
 * same row, numbers, and the four operators. Mirrors Kotlin's
 * `DerivedExpressionDsl` (`wow-query`, `AggregationQueryDsl.kt`).
 *
 * `constant` checks its number here; `aggregation.query()` checks what
 * spans the query: that each `ref` names a non-ANY metric declared before the
 * derived one, and the depth and size of the tree.
 *
 * These are not `aggregation.add` and friends: those build the expressions a
 * metric aggregates over, which name fields, not metrics.
 */
export interface DerivedExpressionDsl {
  /** The value of another metric of the row. `{ type: 'metric-ref', metric }`. */
  ref(metric: string): DerivedExpression;
  /**
   * A number. `{ type: 'constant', value }`.
   *
   * @throws TypeError when `value` is not finite.
   */
  constant(value: number): DerivedExpression;
  /** `left + right`. `{ type: 'binary', operator: 'add', left, right }`. */
  add(left: DerivedExpression, right: DerivedExpression): DerivedExpression;
  /** `left - right`. `{ type: 'binary', operator: 'subtract', left, right }`. */
  subtract(
    left: DerivedExpression,
    right: DerivedExpression,
  ): DerivedExpression;
  /** `left * right`. `{ type: 'binary', operator: 'multiply', left, right }`. */
  multiply(
    left: DerivedExpression,
    right: DerivedExpression,
  ): DerivedExpression;
  /** `left / right`. `{ type: 'binary', operator: 'divide', left, right }`. */
  divide(left: DerivedExpression, right: DerivedExpression): DerivedExpression;
  /**
   * `metric` summed over the groups sharing this group's `within` keys.
   * `{ type: 'total', metric, within }`.
   */
  total(metric: string, ...within: string[]): DerivedExpression;
  /**
   * `metric` accumulated along `order` within the groups sharing this group's
   * `within` keys; with `frame` and `function: 'avg'`, a moving average.
   */
  running(
    metric: string,
    order: FieldSort,
    options?: {
      within?: string[];
      frame?: number;
      function?: RunningFunction;
    },
  ): DerivedExpression;
  /**
   * `metric` of the group `shift` buckets (or `period`s) earlier along the
   * `DATE_HISTOGRAM` group `along`.
   */
  previous(
    metric: string,
    along: string,
    options?: { shift?: number; period?: AggregationDateUnit },
  ): DerivedExpression;
  /**
   * The dense rank of `metric` among the groups sharing this group's
   * `within` keys, `DESC` unless `direction` says otherwise.
   */
  rank(
    metric: string,
    options?: { within?: string[]; direction?: SortDirection },
  ): DerivedExpression;
}

function binary(
  operator: AggregationExpressionOperator,
  left: DerivedExpression,
  right: DerivedExpression,
): DerivedExpression {
  return { type: DerivedExpressionType.BINARY, operator, left, right };
}

export const derivedExpressionDsl: DerivedExpressionDsl = {
  ref: metric => ({ type: DerivedExpressionType.METRIC_REF, metric }),
  constant(value) {
    if (!Number.isFinite(value)) {
      throw new TypeError('derived constant must be finite.');
    }
    return { type: DerivedExpressionType.CONSTANT, value };
  },
  add: (left, right) => binary(AggregationExpressionOperator.ADD, left, right),
  subtract: (left, right) =>
    binary(AggregationExpressionOperator.SUBTRACT, left, right),
  multiply: (left, right) =>
    binary(AggregationExpressionOperator.MULTIPLY, left, right),
  divide: (left, right) =>
    binary(AggregationExpressionOperator.DIVIDE, left, right),
  total: (metric, ...within) => ({
    type: DerivedExpressionType.TOTAL,
    metric,
    ...(within.length > 0 && { within }),
  }),
  running: (metric, order, options = {}) => ({
    type: DerivedExpressionType.RUNNING,
    metric,
    order,
    ...options,
  }),
  previous: (metric, along, options = {}) => ({
    type: DerivedExpressionType.PREVIOUS,
    metric,
    along,
    ...options,
  }),
  rank: (metric, options = {}) => ({
    type: DerivedExpressionType.RANK,
    metric,
    ...options,
  }),
};

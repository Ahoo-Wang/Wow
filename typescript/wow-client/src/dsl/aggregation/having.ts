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

import { constantName } from '../constantName.js';
import { ComparisonOperator } from './types.js';

/** The kind of a {@link HavingExpression} node, sent as its `type`. */
export enum HavingExpressionType {
  /** A metric compared with a number; `aggregation.having.eq` … `lte`. */
  CONDITION = 'condition',
  /** A metric within bounds, inclusive; `aggregation.having.between`. */
  BETWEEN = 'between',
  /** A metric equal to one of the numbers; `aggregation.having.isIn`. */
  IN = 'in',
  /**
   * A metric without a value, or with one when `negated`;
   * `aggregation.having.isNull`, `isNotNull`.
   */
  IS_NULL = 'is-null',
  /** All operands hold; `aggregation.having.and`. */
  AND = 'and',
  /** At least one operand holds; `aggregation.having.or`. */
  OR = 'or',
}

/**
 * Filters grouped results by metric aliases, before sorting/limit; never an
 * `ANY`, `FIRST` or `LAST` metric.
 */
export type HavingExpression =
  | {
      type: HavingExpressionType.CONDITION;
      metric: string;
      operator: ComparisonOperator;
      value: number;
    }
  | {
      type: HavingExpressionType.BETWEEN;
      metric: string;
      lower: number;
      upper: number;
    }
  | {
      type: HavingExpressionType.IN;
      metric: string;
      values: [number, ...number[]];
    }
  | { type: HavingExpressionType.IS_NULL; metric: string; negated?: boolean }
  | {
      type: HavingExpressionType.AND | HavingExpressionType.OR;
      operands: [HavingExpression, ...HavingExpression[]];
    };

/**
 * The builders of a `having` expression, `aggregation.having.*`: conditions on
 * the metric columns of the grouped rows, and their `and`/`or`. Mirrors
 * Kotlin's `HavingDsl` (`wow-query`, `AggregationQueryDsl.kt`).
 *
 * Each builder checks its own numbers and throws a `TypeError` with the
 * message Wow answers; `aggregation.query()` checks what spans the query:
 * that `metric` names a declared, non-ANY metric, and that the query groups.
 */
export interface HavingDsl {
  /**
   * `metric = value`. `{ type: 'condition', metric, operator: 'eq', value }`.
   *
   * @param metric - The alias of a metric of the same query.
   * @throws TypeError when `value` is not finite.
   * @example
   * ```typescript
   * aggregation.having.eq('orders', 1);
   * ```
   */
  eq(metric: string, value: number): HavingExpression;
  /** `metric ≠ value`. `{ type: 'condition', operator: 'ne', … }`. */
  ne(metric: string, value: number): HavingExpression;
  /**
   * `metric > value`. `{ type: 'condition', operator: 'gt', … }`.
   *
   * @example
   * ```typescript
   * aggregation.having.gt('revenue', 100);
   * ```
   */
  gt(metric: string, value: number): HavingExpression;
  /** `metric ≥ value`. `{ type: 'condition', operator: 'gte', … }`. */
  gte(metric: string, value: number): HavingExpression;
  /** `metric < value`. `{ type: 'condition', operator: 'lt', … }`. */
  lt(metric: string, value: number): HavingExpression;
  /** `metric ≤ value`. `{ type: 'condition', operator: 'lte', … }`. */
  lte(metric: string, value: number): HavingExpression;
  /**
   * `lower ≤ metric ≤ upper`. `{ type: 'between', metric, lower, upper }`.
   *
   * @throws TypeError when a bound is not finite, or `lower` exceeds `upper`.
   * @example
   * ```typescript
   * aggregation.having.between('revenue', 10, 1000);
   * ```
   */
  between(metric: string, lower: number, upper: number): HavingExpression;
  /**
   * `metric` is one of `values`. `{ type: 'in', metric, values }` with a
   * copy of `values`.
   *
   * @throws TypeError when `values` is empty or holds a non-finite number.
   * @example
   * ```typescript
   * aggregation.having.isIn('orders', [2, 3]);
   * ```
   */
  isIn(metric: string, values: readonly number[]): HavingExpression;
  /**
   * `metric` has no value in the row. `{ type: 'is-null', metric }`.
   *
   * @example
   * ```typescript
   * aggregation.having.isNull('averageOrder');
   * ```
   */
  isNull(metric: string): HavingExpression;
  /** `metric` has a value. `{ type: 'is-null', metric, negated: true }`. */
  isNotNull(metric: string): HavingExpression;
  /**
   * All of `operands` hold. `{ type: 'and', operands }` with a copy of
   * `operands`.
   *
   * @throws TypeError when `operands` is empty.
   * @example
   * ```typescript
   * aggregation.having.and([
   *   aggregation.having.gt('orders', 1),
   *   aggregation.having.isNotNull('revenue'),
   * ]);
   * ```
   */
  and(operands: readonly HavingExpression[]): HavingExpression;
  /**
   * At least one of `operands` holds. `{ type: 'or', operands }` with a copy
   * of `operands`.
   *
   * @throws TypeError when `operands` is empty.
   */
  or(operands: readonly HavingExpression[]): HavingExpression;
}

function condition(
  operator: ComparisonOperator,
  metric: string,
  value: number,
): HavingExpression {
  if (!Number.isFinite(value)) {
    throw new TypeError(`having condition [${metric}] value must be finite.`);
  }
  return { type: HavingExpressionType.CONDITION, metric, operator, value };
}

function logical(
  type: HavingExpressionType.AND | HavingExpressionType.OR,
  operands: readonly HavingExpression[],
): HavingExpression {
  const [first, ...rest] = operands;
  if (first === undefined) {
    throw new TypeError(
      `having ${constantName(type)} operands must not be empty.`,
    );
  }
  return { type, operands: [first, ...rest] };
}

export const havingDsl: HavingDsl = {
  eq: (metric, value) => condition(ComparisonOperator.EQ, metric, value),
  ne: (metric, value) => condition(ComparisonOperator.NE, metric, value),
  gt: (metric, value) => condition(ComparisonOperator.GT, metric, value),
  gte: (metric, value) => condition(ComparisonOperator.GTE, metric, value),
  lt: (metric, value) => condition(ComparisonOperator.LT, metric, value),
  lte: (metric, value) => condition(ComparisonOperator.LTE, metric, value),
  between(metric, lower, upper) {
    if (!Number.isFinite(lower) || !Number.isFinite(upper)) {
      throw new TypeError(`having between [${metric}] bounds must be finite.`);
    }
    if (lower > upper) {
      throw new TypeError(
        `having between [${metric}] lower bound must not exceed upper bound.`,
      );
    }
    return { type: HavingExpressionType.BETWEEN, metric, lower, upper };
  },
  isIn(metric, values) {
    const [first, ...rest] = values;
    if (first === undefined) {
      throw new TypeError(`having in [${metric}] values must not be empty.`);
    }
    if (!values.every(Number.isFinite)) {
      throw new TypeError(`having in [${metric}] values must be finite.`);
    }
    return { type: HavingExpressionType.IN, metric, values: [first, ...rest] };
  },
  isNull: metric => ({ type: HavingExpressionType.IS_NULL, metric }),
  isNotNull: metric => ({
    type: HavingExpressionType.IS_NULL,
    metric,
    negated: true,
  }),
  and: operands => logical(HavingExpressionType.AND, operands),
  or: operands => logical(HavingExpressionType.OR, operands),
};

/** The builders of the query's `having`. */
export const havingBuilders = {
  /**
   * The builders of the query's `having`: conditions on the metric columns of
   * the grouped rows (`eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `between`, `isIn`,
   * `isNull`, `isNotNull`) and their `and`/`or`. See `HavingDsl`.
   *
   * @example
   * ```typescript
   * aggregation.query({
   *   groupBy: [aggregation.terms('state.status', 'status')],
   *   metrics: [aggregation.count('orders')],
   *   having: aggregation.having.gt('orders', 10),
   * });
   * ```
   */
  having: havingDsl,
};

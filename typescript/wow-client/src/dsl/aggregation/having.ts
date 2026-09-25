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

import {
  ComparisonOperator,
  HavingExpressionType,
  type HavingExpression,
} from './types.js';

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
   * `metric = value`. `{ type: 'CONDITION', metric, operator: 'EQ', value }`.
   *
   * @param metric - The alias of a metric of the same query.
   * @throws TypeError when `value` is not finite.
   * @example
   * ```typescript
   * aggregation.having.eq('orders', 1);
   * ```
   */
  eq(metric: string, value: number): HavingExpression;
  /** `metric ≠ value`. `{ type: 'CONDITION', operator: 'NE', … }`. */
  ne(metric: string, value: number): HavingExpression;
  /**
   * `metric > value`. `{ type: 'CONDITION', operator: 'GT', … }`.
   *
   * @example
   * ```typescript
   * aggregation.having.gt('revenue', 100);
   * ```
   */
  gt(metric: string, value: number): HavingExpression;
  /** `metric ≥ value`. `{ type: 'CONDITION', operator: 'GTE', … }`. */
  gte(metric: string, value: number): HavingExpression;
  /** `metric < value`. `{ type: 'CONDITION', operator: 'LT', … }`. */
  lt(metric: string, value: number): HavingExpression;
  /** `metric ≤ value`. `{ type: 'CONDITION', operator: 'LTE', … }`. */
  lte(metric: string, value: number): HavingExpression;
  /**
   * `lower ≤ metric ≤ upper`. `{ type: 'BETWEEN', metric, lower, upper }`.
   *
   * @throws TypeError when a bound is not finite, or `lower` exceeds `upper`.
   * @example
   * ```typescript
   * aggregation.having.between('revenue', 10, 1000);
   * ```
   */
  between(metric: string, lower: number, upper: number): HavingExpression;
  /**
   * `metric` is one of `values`. `{ type: 'IN', metric, values }` with a
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
   * `metric` has no value in the row. `{ type: 'IS_NULL', metric }`.
   *
   * @example
   * ```typescript
   * aggregation.having.isNull('averageOrder');
   * ```
   */
  isNull(metric: string): HavingExpression;
  /** `metric` has a value. `{ type: 'IS_NULL', metric, negated: true }`. */
  isNotNull(metric: string): HavingExpression;
  /**
   * All of `operands` hold. `{ type: 'AND', operands }` with a copy of
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
   * At least one of `operands` holds. `{ type: 'OR', operands }` with a copy
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
    throw new TypeError(`having ${type} operands must not be empty.`);
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

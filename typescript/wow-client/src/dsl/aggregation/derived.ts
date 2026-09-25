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
  AggregationExpressionOperator,
  DerivedExpressionType,
  type DerivedExpression,
} from './types.js';

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
  /** The value of another metric of the row. `{ type: 'METRIC_REF', metric }`. */
  ref(metric: string): DerivedExpression;
  /**
   * A number. `{ type: 'CONSTANT', value }`.
   *
   * @throws TypeError when `value` is not finite.
   */
  constant(value: number): DerivedExpression;
  /** `left + right`. `{ type: 'BINARY', operator: 'ADD', left, right }`. */
  add(left: DerivedExpression, right: DerivedExpression): DerivedExpression;
  /** `left - right`. `{ type: 'BINARY', operator: 'SUBTRACT', left, right }`. */
  subtract(
    left: DerivedExpression,
    right: DerivedExpression,
  ): DerivedExpression;
  /** `left * right`. `{ type: 'BINARY', operator: 'MULTIPLY', left, right }`. */
  multiply(
    left: DerivedExpression,
    right: DerivedExpression,
  ): DerivedExpression;
  /** `left / right`. `{ type: 'BINARY', operator: 'DIVIDE', left, right }`. */
  divide(left: DerivedExpression, right: DerivedExpression): DerivedExpression;
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
};

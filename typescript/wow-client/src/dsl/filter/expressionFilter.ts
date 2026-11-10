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
  expressionFields,
  requireValidExpressionTrees,
} from '../aggregation/expressionTrees.js';
import {
  ComparisonOperator,
  type AggregationExpression,
} from '../aggregation/types.js';
import { FilterOperator } from './operator.js';
import type { ExpressionFilter } from './types.js';

/** The filter that compares a computed expression with a number. */
export const expressionFilters = {
  /**
   * Compares a computed expression with a number, such as the hours from
   * `paidAt` to `shippedAt`. `{ op: 'expression', expression, comparison,
   * value }`.
   *
   * The expression is evaluated per record, so this is an expensive
   * operator, admitted where the descriptor lists `EXPRESSION` in
   * `record.rootOperators`. A record whose expression has no value (an
   * operand absent or holding several values, or a division by zero) does
   * not match, whatever the comparison, `NE` included. Allowed at the root,
   * in a metric's filter and in an aggregation element's filter, but not
   * inside `elementMatch`. Needs a Wow server of 9.2.0 or later.
   *
   * @param expression - What is compared; it must read at least one field.
   * @param comparison - How it compares with `value`.
   * @param value - A finite number.
   * @throws TypeError If `value` is not finite, `comparison` is not a
   *   {@link ComparisonOperator}, `expression` reads no field, or the tree is
   *   too deep or too large.
   * @example
   * ```typescript
   * // Shipped more than two days after it was paid.
   * filter.expression(
   *   aggregation.dateDiff('state.paidAt', 'state.shippedAt', DateDiffUnit.HOUR),
   *   ComparisonOperator.GT,
   *   48,
   * );
   * ```
   */
  expression<FIELDS extends string>(
    expression: AggregationExpression<FIELDS>,
    comparison: ComparisonOperator,
    value: number,
  ): ExpressionFilter<FIELDS> {
    if (!Number.isFinite(value)) {
      throw new TypeError('EXPRESSION value must be finite.');
    }
    if (!Object.values(ComparisonOperator).includes(comparison)) {
      throw new TypeError(
        `EXPRESSION comparison is invalid: [${String(comparison)}].`,
      );
    }
    if (expressionFields(expression).length === 0) {
      throw new TypeError('EXPRESSION must read at least one field.');
    }
    requireValidExpressionTrees([expression]);
    return { op: FilterOperator.EXPRESSION, expression, comparison, value };
  },
};

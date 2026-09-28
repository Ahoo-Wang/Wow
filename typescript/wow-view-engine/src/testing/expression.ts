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
  AggregationExpressionType,
  DateDiffUnit,
  type AggregationExpression,
} from '@ahoo-wang/wow-client';
import { instantOf, numberOf, valueAt } from './values.js';

/** The length of each unit a `DATE_DIFF` measures in; a day is 24 hours. */
const DATE_DIFF_MS: Record<DateDiffUnit, number> = {
  [DateDiffUnit.SECOND]: 1_000,
  [DateDiffUnit.MINUTE]: 60_000,
  [DateDiffUnit.HOUR]: 3_600_000,
  [DateDiffUnit.DAY]: 86_400_000,
};

/** Wow's four operations; a division by zero has no value. */
export function arithmetic(
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
      throw new Error(
        `The memory source does not compute ${String(operator)}.`,
      );
  }
}

/**
 * What one record contributes, before a metric summarises it across the
 * group, or what an `EXPRESSION` filter compares, or what a band is cut from:
 * a field, a number, one operation over two of those, or the time between
 * two moments (`to − from` in the unit). A formula is computed **per record
 * and then summarised** — 金额 − 成本 per order, then summed.
 *
 * `null` is "no value", as the service skips such a record: a field that
 * holds no number (an array of one number counts as that number, an array of
 * several as none), a division by zero, a result that is not finite (an
 * overflow), a `DATE_DIFF` missing either moment.
 */
export function evaluated(
  expression: AggregationExpression,
  record: unknown,
): number | null {
  const finite = (value: number | null) =>
    value !== null && Number.isFinite(value) ? value : null;
  switch (expression.type) {
    case AggregationExpressionType.FIELD:
      return numberOf(valueAt(record, expression.field));
    case AggregationExpressionType.CONSTANT:
      return finite(expression.value);
    case AggregationExpressionType.BINARY: {
      const left = evaluated(expression.left, record);
      const right = evaluated(expression.right, record);
      if (left === null || right === null) return null;
      return finite(arithmetic(expression.operator, left, right));
    }
    case AggregationExpressionType.DATE_DIFF: {
      const unit = DATE_DIFF_MS[expression.unit];
      if (unit === undefined)
        throw new Error(
          `The memory source does not measure in ${expression.unit}.`,
        );
      const from = instantOf(valueAt(record, expression.from));
      const to = instantOf(valueAt(record, expression.to));
      return from === null || to === null ? null : (to - from) / unit;
    }
    default:
      throw new Error(
        `The memory source does not compute the expression ${(expression as { type: string }).type}.`,
      );
  }
}

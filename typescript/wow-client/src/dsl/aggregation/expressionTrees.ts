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
  AGGREGATION_LIMITS,
  AggregationExpressionType,
  DateDiffUnit,
  type AggregationExpression,
} from './types.js';

// Internal: the one walk over aggregation expression trees that metrics,
// expression groups and `EXPRESSION` filters share. index.ts does not
// re-export this file.

/**
 * Bounds the depth of each tree and the nodes of all of them together, as
 * Wow's `requireValidExpressionTrees` does: depth is per expression and the
 * node count is one budget over the list, so a query cannot slip past by
 * spreading a large tree across many metrics.
 */
export function requireValidExpressionTrees(
  expressions: readonly AggregationExpression[],
): void {
  const pending = expressions.map(expression => ({ expression, depth: 1 }));
  let nodes = 0;
  while (pending.length > 0) {
    const { expression, depth } = pending.pop()!;
    if (depth > AGGREGATION_LIMITS.MAX_EXPRESSION_DEPTH) {
      throw new TypeError(
        `aggregation expression depth must be at most ${AGGREGATION_LIMITS.MAX_EXPRESSION_DEPTH}.`,
      );
    }
    nodes++;
    if (nodes > AGGREGATION_LIMITS.MAX_EXPRESSION_NODES) {
      throw new TypeError(
        `aggregation expressions must contain at most ${AGGREGATION_LIMITS.MAX_EXPRESSION_NODES} nodes.`,
      );
    }
    switch (expression.type) {
      case AggregationExpressionType.FIELD:
        break;
      // `aggregation.constant` refuses these, but a query rebuilt from a
      // stored config never passed through it, and JSON has no NaN: the value
      // would serialise to null and be refused on arrival instead.
      case AggregationExpressionType.CONSTANT:
        if (!Number.isFinite(expression.value)) {
          throw new TypeError('aggregation constant must be finite.');
        }
        break;
      case AggregationExpressionType.DATE_DIFF:
        if (!Object.values(DateDiffUnit).includes(expression.unit)) {
          throw new TypeError('date diff unit is invalid.');
        }
        break;
      case AggregationExpressionType.BINARY:
        pending.push({ expression: expression.left, depth: depth + 1 });
        pending.push({ expression: expression.right, depth: depth + 1 });
        break;
      default:
        throw new TypeError(
          `Unsupported aggregation expression: ${String((expression as { type: unknown }).type)}.`,
        );
    }
  }
}

/** The fields an expression reads, in tree order. */
export function expressionFields(expression: AggregationExpression): string[] {
  switch (expression.type) {
    case AggregationExpressionType.FIELD:
      return [expression.field];
    case AggregationExpressionType.DATE_DIFF:
      return [expression.from, expression.to];
    case AggregationExpressionType.BINARY:
      return [
        ...expressionFields(expression.left),
        ...expressionFields(expression.right),
      ];
    default:
      return [];
  }
}

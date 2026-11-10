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

import type {
  ElementFilterExpression,
  ExpressionFilter,
} from '../filter/index.js';
import { requireElementScopedFilter } from '../filter/scope.js';
import { admitAggregationQuery } from './admit.js';
import { aggregationField } from './aliases.js';
import {
  DateDiffUnit,
  AggregationExpressionOperator,
  AggregationExpressionType,
  type AggregationElement,
  type AggregationExpression,
  type DateDiffAggregationExpression,
  type AggregationQuery,
  type BinaryAggregationExpression,
  type ConstantAggregationExpression,
  type FieldAggregationExpression,
} from './types.js';
import { derivedBuilders } from './derived.js';
import { groupBuilders } from './groups.js';
import { havingBuilders } from './having.js';
import { metricBuilders } from './metrics.js';

function binary<FIELDS extends string>(
  operator: AggregationExpressionOperator,
  left: AggregationExpression<FIELDS>,
  right: AggregationExpression<FIELDS>,
): BinaryAggregationExpression<FIELDS> {
  return { type: AggregationExpressionType.BINARY, operator, left, right };
}

/** The builders of an aggregation query's elements and expressions, and of the query itself. */
export const queryBuilders = {
  /**
   * Aggregates over the elements of an array field instead of the root
   * documents. `{ path, filter? }`.
   *
   * @param path - The array field, a query field path.
   * @param predicate - Keeps only the elements that match; may not contain
   *   root filters (id, owner, tenant, space, deletion) or any search, even
   *   one that names its fields. An `EXPRESSION` filter is allowed.
   * @throws TypeError when `path` is not a valid field path or `predicate`
   *   contains a root filter.
   * @example
   * ```typescript
   * aggregation.element('items', filter.gt('quantity', 0));
   * ```
   */
  element(
    path: string,
    predicate?: ElementFilterExpression | ExpressionFilter,
  ): AggregationElement {
    const validPath = aggregationField(path);
    if (predicate === undefined) return { path: validPath };
    requireElementScopedFilter(predicate, 'Aggregation element filter', false);
    return { path: validPath, filter: predicate };
  },
  /**
   * The value of a field, as an operand. `{ type: 'field', field }`.
   *
   * @throws TypeError when `field` is not a valid field path.
   * @example
   * ```typescript
   * aggregation.sum(aggregation.field('state.amount'), 'amount');
   * ```
   */
  field<FIELDS extends string>(
    field: FIELDS,
  ): FieldAggregationExpression<FIELDS> {
    return {
      type: AggregationExpressionType.FIELD,
      field: aggregationField(field),
    };
  },
  /**
   * A number, as an operand. `{ type: 'constant', value }`.
   *
   * @throws TypeError when `value` is not finite.
   * @example
   * ```typescript
   * aggregation.multiply(aggregation.field('price'), aggregation.constant(100));
   * ```
   */
  constant(value: number): ConstantAggregationExpression {
    if (!Number.isFinite(value)) {
      throw new TypeError('aggregation constant must be finite.');
    }
    return { type: AggregationExpressionType.CONSTANT, value };
  },
  /**
   * The time from the instant in `from` to the instant in `to`, `to − from`,
   * in `unit`, as a signed decimal: negative when `to` is earlier, and no
   * value when either instant is absent. A `DAY` is exactly 24 hours.
   * `{ type: 'date-diff', from, to, unit }`. Both fields must be
   * single-valued time fields, those whose `aggregate.groups` list
   * `DATE_HISTOGRAM` in the descriptor, and `unit` one of its
   * `analysis.dateDiffUnits`. Wow 9.2 and later.
   *
   * @throws TypeError when `from` or `to` is not a valid field path, or
   *   `unit` is not a {@link DateDiffUnit}.
   * @example
   * ```typescript
   * // Hours from payment to shipping, averaged per warehouse.
   * aggregation.avg(
   *   aggregation.dateDiff('state.paidAt', 'state.shippedAt', DateDiffUnit.HOUR),
   *   'hoursToShip',
   * );
   * ```
   */
  dateDiff<FIELDS extends string>(
    from: FIELDS,
    to: FIELDS,
    unit: DateDiffUnit,
  ): DateDiffAggregationExpression<FIELDS> {
    if (!Object.values(DateDiffUnit).includes(unit)) {
      throw new TypeError('date diff unit is invalid.');
    }
    return {
      type: AggregationExpressionType.DATE_DIFF,
      from: aggregationField(from),
      to: aggregationField(to),
      unit,
    };
  },
  /** `left + right`. `{ type: 'binary', operator: 'add', left, right }`. */
  add: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.ADD, left, right),
  /** `left - right`. `{ type: 'binary', operator: 'subtract', left, right }`. */
  subtract: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.SUBTRACT, left, right),
  /** `left * right`. `{ type: 'binary', operator: 'multiply', left, right }`. */
  multiply: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.MULTIPLY, left, right),
  /** `left / right`. `{ type: 'binary', operator: 'divide', left, right }`. */
  divide: <FIELDS extends string>(
    left: AggregationExpression<FIELDS>,
    right: AggregationExpression<FIELDS>,
  ) => binary(AggregationExpressionOperator.DIVIDE, left, right),
  /**
   * Admits a whole aggregation query against the rules Wow enforces on arrival.
   *
   * The factories above each check their own part; only the assembled query
   * can answer the questions that span parts — whether two aliases collide,
   * whether a sort names one of them, whether `having` has rows to filter.
   * Wow checks all of it in `AggregationQuery`'s constructor and answers a
   * query that fails with a 400, so the same rules run here against the object
   * about to be sent.
   */
  query<
    ROOT_FIELDS extends string = string,
    AGGREGATION_FIELDS extends string = ROOT_FIELDS,
  >(
    query: AggregationQuery<ROOT_FIELDS, AGGREGATION_FIELDS>,
  ): AggregationQuery<ROOT_FIELDS, AGGREGATION_FIELDS> {
    return admitAggregationQuery(query);
  },
};

/**
 * Builders of an `AggregationQuery`: its elements, groups, metrics and the
 * arithmetic expressions metrics compute over. Each builder checks its own
 * part and throws a `TypeError` naming what is wrong; {@link aggregation.query}
 * checks the parts against each other.
 *
 * Every group and metric takes its target first, its alias second, and any
 * further options as a trailing object.
 */
export const aggregation: typeof queryBuilders &
  typeof groupBuilders &
  typeof metricBuilders &
  typeof derivedBuilders &
  typeof havingBuilders = {
  ...queryBuilders,
  ...groupBuilders,
  ...metricBuilders,
  ...derivedBuilders,
  ...havingBuilders,
};

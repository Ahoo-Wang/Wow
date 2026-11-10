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
import { queryField } from '../field.js';
import { FilterOperator } from './operator.js';
import { requireElementScopedFilter } from './scope.js';
import type {
  ElementFilterExpression,
  ElementLogicalFilter,
  ElementMatchFilter,
  FilterExpression,
  LogicalFilter,
} from './types.js';
import { requireNonEmpty } from './validate.js';

function logical<FIELDS extends string>(
  op: LogicalFilter['op'],
  operands: readonly FilterExpression<FIELDS>[],
): LogicalFilter<FIELDS> {
  requireNonEmpty(`${constantName(op)} operands`, operands);
  return { op, operands: [...operands] };
}

/**
 * `filter.and`: matches when all of `operands` match. With element-scoped
 * operands only, the result is element-scoped too, so it can be an
 * `ELEMENT_MATCH` predicate.
 */
function andFilter<FIELDS extends string>(
  operands: readonly ElementFilterExpression<FIELDS>[],
): ElementLogicalFilter<FIELDS>;
/** `filter.and`: matches when all of `operands` match. */
function andFilter<FIELDS extends string>(
  operands: readonly FilterExpression<FIELDS>[],
): LogicalFilter<FIELDS>;
function andFilter<FIELDS extends string>(
  operands: readonly FilterExpression<FIELDS>[],
): LogicalFilter<FIELDS> {
  return logical(FilterOperator.AND, operands);
}

/**
 * `filter.or`: matches when at least one of `operands` matches. With
 * element-scoped operands only, the result is element-scoped too, so it can
 * be an `ELEMENT_MATCH` predicate.
 */
function orFilter<FIELDS extends string>(
  operands: readonly ElementFilterExpression<FIELDS>[],
): ElementLogicalFilter<FIELDS>;
/** `filter.or`: matches when at least one of `operands` matches. */
function orFilter<FIELDS extends string>(
  operands: readonly FilterExpression<FIELDS>[],
): LogicalFilter<FIELDS>;
function orFilter<FIELDS extends string>(
  operands: readonly FilterExpression<FIELDS>[],
): LogicalFilter<FIELDS> {
  return logical(FilterOperator.OR, operands);
}

/**
 * `filter.nor`: matches when none of `operands` match. With element-scoped
 * operands only, the result is element-scoped too, so it can be an
 * `ELEMENT_MATCH` predicate.
 */
function norFilter<FIELDS extends string>(
  operands: readonly ElementFilterExpression<FIELDS>[],
): ElementLogicalFilter<FIELDS>;
/** `filter.nor`: matches when none of `operands` match. */
function norFilter<FIELDS extends string>(
  operands: readonly FilterExpression<FIELDS>[],
): LogicalFilter<FIELDS>;
function norFilter<FIELDS extends string>(
  operands: readonly FilterExpression<FIELDS>[],
): LogicalFilter<FIELDS> {
  return logical(FilterOperator.NOR, operands);
}

/** The filters that combine others: `and`, `or`, `nor`, and `elementMatch` over the elements of an array field. */
export const logicalFilters = {
  /**
   * Matches when all of `operands` match. Returns an element-scoped
   * filter when every operand is element-scoped, so the result can be used
   * inside `elementMatch`.
   *
   * @param operands - Non-empty list of filter expressions.
   * @returns `{ op: 'and', operands }` with a copy of `operands`.
   * @throws TypeError If `operands` is empty or contains `null` or
   *   `undefined`.
   * @example
   * ```typescript
   * filter.and([
   *   filter.eq('state.status', 'PAID'),
   *   filter.eq('state.status', 'SHIPPED'),
   * ]);
   * ```
   */
  and: andFilter,
  /**
   * Matches when at least one of `operands` match. Returns an element-scoped
   * filter when every operand is element-scoped, so the result can be used
   * inside `elementMatch`.
   *
   * @param operands - Non-empty list of filter expressions.
   * @returns `{ op: 'or', operands }` with a copy of `operands`.
   * @throws TypeError If `operands` is empty or contains `null` or
   *   `undefined`.
   * @example
   * ```typescript
   * filter.or([
   *   filter.eq('state.status', 'PAID'),
   *   filter.eq('state.status', 'SHIPPED'),
   * ]);
   * ```
   */
  or: orFilter,
  /**
   * Matches when none of `operands` match. Returns an element-scoped
   * filter when every operand is element-scoped, so the result can be used
   * inside `elementMatch`.
   *
   * @param operands - Non-empty list of filter expressions.
   * @returns `{ op: 'nor', operands }` with a copy of `operands`.
   * @throws TypeError If `operands` is empty or contains `null` or
   *   `undefined`.
   * @example
   * ```typescript
   * filter.nor([
   *   filter.eq('state.status', 'PAID'),
   *   filter.eq('state.status', 'SHIPPED'),
   * ]);
   * ```
   */
  nor: norFilter,
  /**
   * Matches records where a single element of the array `field` satisfies
   * `predicate`. Fields in `predicate` are relative to the element.
   *
   * @param field - Query field path of the array, e.g. `state.items`.
   * @param predicate - Element-scoped filter expression.
   * @returns `{ op: 'element-match', field, predicate }`.
   * A `SEARCH` inside it must name the fields it searches, relative to the
   * element (Wow 9.2 and later, where the descriptor lists
   * `elements[].search`); a model-wide `SEARCH` is a root filter.
   *
   * @throws TypeError If `predicate` contains, at any depth, a root-only filter
   *   (`ID`, `IDS`, `AGGREGATE_ID`, `AGGREGATE_IDS`, `TENANT_ID`, `OWNER_ID`,
   *   `SPACE_ID`, `DELETION` or a `SEARCH` without fields) or an `AND`, `OR`
   *   or `NOR` whose operands are empty or contain `null`, or if `field` is
   *   not a valid query field path.
   * @example
   * ```typescript
   * filter.elementMatch('state.items', filter.gt('quantity', 1));
   * filter.elementMatch(
   *   'state.items',
   *   filter.search('usb cable', { fields: ['productName'] }),
   * );
   * ```
   */
  elementMatch<FIELDS extends string, ELEMENT_FIELDS extends string>(
    field: FIELDS,
    predicate: ElementFilterExpression<ELEMENT_FIELDS>,
  ): ElementMatchFilter<FIELDS, ELEMENT_FIELDS> {
    requireElementScopedFilter(predicate, 'ELEMENT_MATCH predicate');
    return {
      op: FilterOperator.ELEMENT_MATCH,
      field: queryField(field),
      predicate,
    };
  },
};

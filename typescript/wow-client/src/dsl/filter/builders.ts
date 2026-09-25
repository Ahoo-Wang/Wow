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

import { DeletionState } from '../deletionState.js';
import { queryField } from '../field.js';
import { FilterOperator, SearchMode, StringComparison } from './operator.js';
import { requireElementScopedFilter } from './scope.js';
import type {
  BeforeTodayFilter,
  BetweenFilter,
  CalendarFilter,
  CollectionFilter,
  ComparableFilterLiteral,
  ComparisonFilter,
  DaysFilter,
  DeletionFilter,
  ElementFilterExpression,
  ElementLogicalFilter,
  ElementMatchFilter,
  EqualityFilter,
  EqualityFilterValue,
  FieldPresenceFilter,
  FilterExpression,
  LogicalFilter,
  MatchFilter,
  MetadataValueFilter,
  MetadataValuesFilter,
  NowFilter,
  RelativeTimeFilterOptions,
  SearchFilter,
  SearchFilterOptions,
  StringFilter,
} from './types.js';
import {
  filterLiteral,
  requireLocalTime,
  requireDuration,
  requireNonEmpty,
  requiredString,
  validateDays,
  validateRelativeTimeOptions,
  validateStringComparison,
} from './validate.js';

// One builder per filter shape, keyed by the operator. Each shape's public
// builders below differ only in the operator they pass, so a new operator of
// an existing shape is one enum member, one type-union member and one line
// here; its JSDoc goes on the public builder, where the IDE shows it.

function metadataValue(
  op: MetadataValueFilter['op'],
  value: string,
): MetadataValueFilter {
  return { op, value: requiredString(`${op} value`, value) };
}

function metadataValues(
  op: MetadataValuesFilter['op'],
  values: readonly string[],
): MetadataValuesFilter {
  requireNonEmpty(`${op} values`, values);
  values.forEach(value => requiredString(`${op} value`, value));
  return { op, values: [...values] };
}

function logical<FIELDS extends string>(
  op: LogicalFilter['op'],
  operands: readonly FilterExpression<FIELDS>[],
): LogicalFilter<FIELDS> {
  requireNonEmpty(`${op} operands`, operands);
  return { op, operands: [...operands] };
}

function equality<FIELDS extends string>(
  op: EqualityFilter['op'],
  field: FIELDS,
  value: EqualityFilterValue,
): EqualityFilter<FIELDS> {
  return { op, field: queryField(field), value: filterLiteral(value, true) };
}

function comparison<FIELDS extends string>(
  op: ComparisonFilter['op'],
  field: FIELDS,
  value: ComparableFilterLiteral,
): ComparisonFilter<FIELDS> {
  return { op, field: queryField(field), value: filterLiteral(value, false) };
}

function stringMatch<FIELDS extends string>(
  op: StringFilter['op'],
  field: FIELDS,
  value: string,
  stringComparison: StringComparison,
): StringFilter<FIELDS> {
  validateStringComparison(stringComparison);
  return {
    op,
    field: queryField(field),
    value: requiredString(`${op} value`, value),
    stringComparison,
  };
}

function collection<FIELDS extends string>(
  op: CollectionFilter['op'],
  field: FIELDS,
  values: readonly ComparableFilterLiteral[],
): CollectionFilter<FIELDS> {
  requireNonEmpty(`${op} values`, values);
  values.forEach(value => filterLiteral(value, false));
  return { op, field: queryField(field), values: [...values] };
}

function presence<FIELDS extends string>(
  op: FieldPresenceFilter['op'],
  field: FIELDS,
): FieldPresenceFilter<FIELDS> {
  return { op, field: queryField(field) };
}

function calendar<FIELDS extends string>(
  op: CalendarFilter['op'],
  field: FIELDS,
  options: RelativeTimeFilterOptions,
): CalendarFilter<FIELDS> {
  return {
    ...validateRelativeTimeOptions(options),
    op,
    field: queryField(field),
  };
}

function dayWindow<FIELDS extends string>(
  op: DaysFilter['op'],
  field: FIELDS,
  days: number,
  options: RelativeTimeFilterOptions,
): DaysFilter<FIELDS> {
  validateDays(op, days);
  return {
    ...validateRelativeTimeOptions(options),
    op,
    field: queryField(field),
    days,
  };
}

function nowRelative<FIELDS extends string>(
  op: NowFilter['op'],
  field: FIELDS,
  offset: string,
  options: RelativeTimeFilterOptions,
): NowFilter<FIELDS> {
  requireDuration(op, offset);
  return {
    ...validateRelativeTimeOptions(options),
    op,
    field: queryField(field),
    offset,
  };
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

/**
 * Builders for {@link FilterExpression} values. Each builder validates its
 * arguments, throws `TypeError` on invalid input, and returns a plain JSON
 * object in the server wire shape.
 *
 * @example
 * ```typescript
 * const paid = filter.and([
 *   filter.eq('state.status', 'PAID'),
 *   filter.gte('state.total', 100),
 * ]);
 * ```
 */
export const filter = {
  /**
   * Matches every record in the query scope.
   *
   * @returns `{ op: 'MATCH_ALL' }`.
   * @example
   * ```typescript
   * filter.matchAll();
   * ```
   */
  matchAll(): MatchFilter {
    return { op: FilterOperator.MATCH_ALL };
  },
  /**
   * Matches no record.
   *
   * @returns `{ op: 'MATCH_NONE' }`.
   * @example
   * ```typescript
   * filter.matchNone();
   * ```
   */
  matchNone(): MatchFilter {
    return { op: FilterOperator.MATCH_NONE };
  },
  /**
   * Matches the record whose record ID equals `value`. Root-only: not allowed
   * inside `elementMatch`.
   *
   * @param value - The record ID.
   * @returns `{ op: 'ID', value }`.
   * @throws TypeError If `value` is not a string.
   * @example
   * ```typescript
   * filter.id('order-1');
   * ```
   */
  id(value: string): MetadataValueFilter {
    return metadataValue(FilterOperator.ID, value);
  },
  /**
   * Matches records whose record ID is one of `values`. Root-only: not
   * allowed inside `elementMatch`.
   *
   * @param values - Non-empty list of record IDs.
   * @returns `{ op: 'IDS', values }` with a copy of `values`.
   * @throws TypeError If `values` is empty, contains `null` or `undefined`,
   *   or contains a non-string.
   * @example
   * ```typescript
   * filter.ids(['order-1', 'order-2']);
   * ```
   */
  ids(values: readonly string[]): MetadataValuesFilter {
    return metadataValues(FilterOperator.IDS, values);
  },
  /**
   * Matches the record whose aggregate ID equals `value`. Root-only: not allowed
   * inside `elementMatch`.
   *
   * @param value - The aggregate ID.
   * @returns `{ op: 'AGGREGATE_ID', value }`.
   * @throws TypeError If `value` is not a string.
   * @example
   * ```typescript
   * filter.aggregateId('order-1');
   * ```
   */
  aggregateId(value: string): MetadataValueFilter {
    return metadataValue(FilterOperator.AGGREGATE_ID, value);
  },
  /**
   * Matches records whose aggregate ID is one of `values`. Root-only: not
   * allowed inside `elementMatch`.
   *
   * @param values - Non-empty list of aggregate IDs.
   * @returns `{ op: 'AGGREGATE_IDS', values }` with a copy of `values`.
   * @throws TypeError If `values` is empty, contains `null` or `undefined`,
   *   or contains a non-string.
   * @example
   * ```typescript
   * filter.aggregateIds(['order-1', 'order-2']);
   * ```
   */
  aggregateIds(values: readonly string[]): MetadataValuesFilter {
    return metadataValues(FilterOperator.AGGREGATE_IDS, values);
  },
  /**
   * Matches the record whose tenant ID equals `value`. Root-only: not allowed
   * inside `elementMatch`.
   *
   * @param value - The tenant ID.
   * @returns `{ op: 'TENANT_ID', value }`.
   * @throws TypeError If `value` is not a string.
   * @example
   * ```typescript
   * filter.tenantId('tenant-a');
   * ```
   */
  tenantId(value: string): MetadataValueFilter {
    return metadataValue(FilterOperator.TENANT_ID, value);
  },
  /**
   * Matches the record whose owner ID equals `value`. Root-only: not allowed
   * inside `elementMatch`.
   *
   * @param value - The owner ID.
   * @returns `{ op: 'OWNER_ID', value }`.
   * @throws TypeError If `value` is not a string.
   * @example
   * ```typescript
   * filter.ownerId('user-1');
   * ```
   */
  ownerId(value: string): MetadataValueFilter {
    return metadataValue(FilterOperator.OWNER_ID, value);
  },
  /**
   * Matches the record whose space ID equals `value`. Root-only: not allowed
   * inside `elementMatch`.
   *
   * @param value - The space ID.
   * @returns `{ op: 'SPACE_ID', value }`.
   * @throws TypeError If `value` is not a string.
   * @example
   * ```typescript
   * filter.spaceId('space-1');
   * ```
   */
  spaceId(value: string): MetadataValueFilter {
    return metadataValue(FilterOperator.SPACE_ID, value);
  },
  /**
   * Matches when all of `operands` match. Returns an element-scoped
   * filter when every operand is element-scoped, so the result can be used
   * inside `elementMatch`.
   *
   * @param operands - Non-empty list of filter expressions.
   * @returns `{ op: 'AND', operands }` with a copy of `operands`.
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
   * @returns `{ op: 'OR', operands }` with a copy of `operands`.
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
   * @returns `{ op: 'NOR', operands }` with a copy of `operands`.
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
   * Matches records whose `field` equals `value`.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param value - JSON scalar. `null` is sent as is; the server rewrites it
   *   to `IS_NULL`.
   * @returns `{ op: 'EQ', field, value }`.
   * @throws TypeError If `field` is not a valid query field path, or `value` is not
   *   `null`, a string, a boolean or a finite number.
   * @example
   * ```typescript
   * filter.eq('state.status', 'PAID');
   * ```
   */
  eq<FIELDS extends string>(
    field: FIELDS,
    value: EqualityFilterValue,
  ): EqualityFilter<FIELDS> {
    return equality(FilterOperator.EQ, field, value);
  },
  /**
   * Matches records whose `field` does not equal `value`.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param value - JSON scalar. `null` is sent as is; the server rewrites it
   *   to `IS_NOT_NULL`.
   * @returns `{ op: 'NE', field, value }`.
   * @throws TypeError If `field` is not a valid query field path, or `value` is not
   *   `null`, a string, a boolean or a finite number.
   * @example
   * ```typescript
   * filter.ne('state.status', 'CANCELLED');
   * ```
   */
  ne<FIELDS extends string>(
    field: FIELDS,
    value: EqualityFilterValue,
  ): EqualityFilter<FIELDS> {
    return equality(FilterOperator.NE, field, value);
  },
  /**
   * Matches records whose `field` is greater than `value` (`field > value`).
   * Numbers compare at the backend's stored precision.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param value - Non-null JSON scalar.
   * @returns `{ op: 'GT', field, value }`.
   * @throws TypeError If `field` is not a valid query field path, or `value` is not a
   *   string, a boolean or a finite number.
   * @example
   * ```typescript
   * filter.gt('state.total', 100);
   * ```
   */
  gt<FIELDS extends string>(
    field: FIELDS,
    value: ComparableFilterLiteral,
  ): ComparisonFilter<FIELDS> {
    return comparison(FilterOperator.GT, field, value);
  },
  /**
   * Matches records whose `field` is greater than or equal to `value` (`field >= value`).
   * Numbers compare at the backend's stored precision.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param value - Non-null JSON scalar.
   * @returns `{ op: 'GTE', field, value }`.
   * @throws TypeError If `field` is not a valid query field path, or `value` is not a
   *   string, a boolean or a finite number.
   * @example
   * ```typescript
   * filter.gte('state.total', 100);
   * ```
   */
  gte<FIELDS extends string>(
    field: FIELDS,
    value: ComparableFilterLiteral,
  ): ComparisonFilter<FIELDS> {
    return comparison(FilterOperator.GTE, field, value);
  },
  /**
   * Matches records whose `field` is less than `value` (`field < value`).
   * Numbers compare at the backend's stored precision.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param value - Non-null JSON scalar.
   * @returns `{ op: 'LT', field, value }`.
   * @throws TypeError If `field` is not a valid query field path, or `value` is not a
   *   string, a boolean or a finite number.
   * @example
   * ```typescript
   * filter.lt('state.total', 100);
   * ```
   */
  lt<FIELDS extends string>(
    field: FIELDS,
    value: ComparableFilterLiteral,
  ): ComparisonFilter<FIELDS> {
    return comparison(FilterOperator.LT, field, value);
  },
  /**
   * Matches records whose `field` is less than or equal to `value` (`field <= value`).
   * Numbers compare at the backend's stored precision.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param value - Non-null JSON scalar.
   * @returns `{ op: 'LTE', field, value }`.
   * @throws TypeError If `field` is not a valid query field path, or `value` is not a
   *   string, a boolean or a finite number.
   * @example
   * ```typescript
   * filter.lte('state.total', 100);
   * ```
   */
  lte<FIELDS extends string>(
    field: FIELDS,
    value: ComparableFilterLiteral,
  ): ComparisonFilter<FIELDS> {
    return comparison(FilterOperator.LTE, field, value);
  },
  /**
   * Matches records whose string `field` contains `value` literally. No
   * full-text analyzer is applied.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param value - Literal text.
   * @param stringComparison - Case handling. Defaults to
   *   `StringComparison.CASE_SENSITIVE`.
   * @returns `{ op: 'CONTAINS', field, value, stringComparison }`.
   * @throws TypeError If `stringComparison` is not a {@link StringComparison}
   *   member, `field` is not a valid query field path, or `value` is not a string.
   * @example
   * ```typescript
   * filter.contains('state.note', 'vip', StringComparison.CASE_INSENSITIVE);
   * ```
   */
  contains<FIELDS extends string>(
    field: FIELDS,
    value: string,
    stringComparison = StringComparison.CASE_SENSITIVE,
  ): StringFilter<FIELDS> {
    return stringMatch(FilterOperator.CONTAINS, field, value, stringComparison);
  },
  /**
   * Matches records whose string `field` starts with `value` literally. No
   * full-text analyzer is applied.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param value - Literal text.
   * @param stringComparison - Case handling. Defaults to
   *   `StringComparison.CASE_SENSITIVE`.
   * @returns `{ op: 'STARTS_WITH', field, value, stringComparison }`.
   * @throws TypeError If `stringComparison` is not a {@link StringComparison}
   *   member, `field` is not a valid query field path, or `value` is not a string.
   * @example
   * ```typescript
   * filter.startsWith('state.note', 'VIP-', StringComparison.CASE_INSENSITIVE);
   * ```
   */
  startsWith<FIELDS extends string>(
    field: FIELDS,
    value: string,
    stringComparison = StringComparison.CASE_SENSITIVE,
  ): StringFilter<FIELDS> {
    return stringMatch(
      FilterOperator.STARTS_WITH,
      field,
      value,
      stringComparison,
    );
  },
  /**
   * Matches records whose string `field` ends with `value` literally. No
   * full-text analyzer is applied.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param value - Literal text.
   * @param stringComparison - Case handling. Defaults to
   *   `StringComparison.CASE_SENSITIVE`.
   * @returns `{ op: 'ENDS_WITH', field, value, stringComparison }`.
   * @throws TypeError If `stringComparison` is not a {@link StringComparison}
   *   member, `field` is not a valid query field path, or `value` is not a string.
   * @example
   * ```typescript
   * filter.endsWith('state.note', '.pdf', StringComparison.CASE_INSENSITIVE);
   * ```
   */
  endsWith<FIELDS extends string>(
    field: FIELDS,
    value: string,
    stringComparison = StringComparison.CASE_SENSITIVE,
  ): StringFilter<FIELDS> {
    return stringMatch(
      FilterOperator.ENDS_WITH,
      field,
      value,
      stringComparison,
    );
  },
  /**
   * Matches records whose `field` equals one of `values`.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param values - Non-empty list of non-null JSON scalars.
   * @returns `{ op: 'IN', field, values }` with a copy of `values`.
   * @throws TypeError If `values` is empty, contains `null` or `undefined`,
   *   or contains a value that is not a string, a boolean or a finite
   *   number, or if `field` is not a valid query field path.
   * @example
   * ```typescript
   * filter.isIn('state.status', ['PAID', 'SHIPPED']);
   * ```
   */
  isIn<FIELDS extends string>(
    field: FIELDS,
    values: readonly ComparableFilterLiteral[],
  ): CollectionFilter<FIELDS> {
    return collection(FilterOperator.IN, field, values);
  },
  /**
   * Matches records whose `field` equals none of `values`.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param values - Non-empty list of non-null JSON scalars.
   * @returns `{ op: 'NOT_IN', field, values }` with a copy of `values`.
   * @throws TypeError If `values` is empty, contains `null` or `undefined`,
   *   or contains a value that is not a string, a boolean or a finite
   *   number, or if `field` is not a valid query field path.
   * @example
   * ```typescript
   * filter.notIn('state.status', ['CANCELLED', 'REFUNDED']);
   * ```
   */
  notIn<FIELDS extends string>(
    field: FIELDS,
    values: readonly ComparableFilterLiteral[],
  ): CollectionFilter<FIELDS> {
    return collection(FilterOperator.NOT_IN, field, values);
  },
  /**
   * Matches records whose array `field` contains every value in `values`.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param values - Non-empty list of non-null JSON scalars.
   * @returns `{ op: 'CONTAINS_ALL', field, values }` with a copy of `values`.
   * @throws TypeError If `values` is empty, contains `null` or `undefined`,
   *   or contains a value that is not a string, a boolean or a finite
   *   number, or if `field` is not a valid query field path.
   * @example
   * ```typescript
   * filter.containsAll('state.tags', ['vip', 'new']);
   * ```
   */
  containsAll<FIELDS extends string>(
    field: FIELDS,
    values: readonly ComparableFilterLiteral[],
  ): CollectionFilter<FIELDS> {
    return collection(FilterOperator.CONTAINS_ALL, field, values);
  },
  /**
   * Matches records whose `field` lies in the inclusive range
   * `lowerBound <= field <= upperBound`.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param lowerBound - Inclusive lower bound; non-null JSON scalar.
   * @param upperBound - Inclusive upper bound; non-null JSON scalar.
   * @returns `{ op: 'BETWEEN', field, lowerBound, upperBound }`.
   * @throws TypeError If `field` is not a valid query field path, or either bound is not a
   *   string, a boolean or a finite number.
   * @example
   * ```typescript
   * filter.between('state.total', 100, 200);
   * ```
   */
  between<FIELDS extends string>(
    field: FIELDS,
    lowerBound: ComparableFilterLiteral,
    upperBound: ComparableFilterLiteral,
  ): BetweenFilter<FIELDS> {
    return {
      op: FilterOperator.BETWEEN,
      field: queryField(field),
      lowerBound: filterLiteral(lowerBound, false),
      upperBound: filterLiteral(upperBound, false),
    };
  },
  /**
   * Matches records whose array `field` is empty. On Elasticsearch it may also
   * match a missing or `null` field.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @returns `{ op: 'IS_EMPTY', field }`.
   * @throws TypeError If `field` is not a valid query field path.
   * @example
   * ```typescript
   * filter.isEmpty('state.items');
   * ```
   */
  isEmpty<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS> {
    return presence(FilterOperator.IS_EMPTY, field);
  },
  /**
   * Matches records whose string `field` equals `""`. Whitespace-only
   * strings do not match.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @returns `{ op: 'IS_EMPTY_STRING', field }`.
   * @throws TypeError If `field` is not a valid query field path.
   * @example
   * ```typescript
   * filter.isEmptyString('state.note');
   * ```
   */
  isEmptyString<FIELDS extends string>(
    field: FIELDS,
  ): FieldPresenceFilter<FIELDS> {
    return presence(FilterOperator.IS_EMPTY_STRING, field);
  },
  /**
   * Matches records whose string `field` exists, is not `null` and is not
   * `""`.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @returns `{ op: 'IS_NOT_EMPTY_STRING', field }`.
   * @throws TypeError If `field` is not a valid query field path.
   * @example
   * ```typescript
   * filter.isNotEmptyString('state.note');
   * ```
   */
  isNotEmptyString<FIELDS extends string>(
    field: FIELDS,
  ): FieldPresenceFilter<FIELDS> {
    return presence(FilterOperator.IS_NOT_EMPTY_STRING, field);
  },
  /**
   * Matches records whose `field` is `null` or missing, following the
   * backend's null semantics.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @returns `{ op: 'IS_NULL', field }`.
   * @throws TypeError If `field` is not a valid query field path.
   * @example
   * ```typescript
   * filter.isNull('state.note');
   * ```
   */
  isNull<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS> {
    return presence(FilterOperator.IS_NULL, field);
  },
  /**
   * Matches records whose `field` exists and is not `null`, following the
   * backend's null semantics.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @returns `{ op: 'IS_NOT_NULL', field }`.
   * @throws TypeError If `field` is not a valid query field path.
   * @example
   * ```typescript
   * filter.isNotNull('state.note');
   * ```
   */
  isNotNull<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS> {
    return presence(FilterOperator.IS_NOT_NULL, field);
  },
  /**
   * Matches records where `field` exists. On MongoDB this includes a `null`
   * value.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @returns `{ op: 'EXISTS', field }`.
   * @throws TypeError If `field` is not a valid query field path.
   * @example
   * ```typescript
   * filter.exists('state.note');
   * ```
   */
  exists<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS> {
    return presence(FilterOperator.EXISTS, field);
  },
  /**
   * Matches records where `field` is missing.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @returns `{ op: 'NOT_EXISTS', field }`.
   * @throws TypeError If `field` is not a valid query field path.
   * @example
   * ```typescript
   * filter.notExists('state.note');
   * ```
   */
  notExists<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS> {
    return presence(FilterOperator.NOT_EXISTS, field);
  },
  /**
   * Matches snapshots by deletion state. An explicit `DELETION` at the root or
   * in a root `AND` replaces the gateway's default `ACTIVE` scope. Root-only:
   * not allowed inside `elementMatch`.
   *
   * @param state - `ACTIVE`, `DELETED` or `ALL`.
   * @returns `{ op: 'DELETION', state }`.
   * @throws TypeError If `state` is not a {@link DeletionState} member.
   * @example
   * ```typescript
   * filter.deletion(DeletionState.ALL);
   * ```
   */
  deletion(state: DeletionState): DeletionFilter {
    if (
      state !== DeletionState.ACTIVE &&
      state !== DeletionState.DELETED &&
      state !== DeletionState.ALL
    ) {
      throw new TypeError(`Deletion state is invalid: [${String(state)}].`);
    }
    return { op: FilterOperator.DELETION, state };
  },
  /**
   * Matches records where a single element of the array `field` satisfies
   * `predicate`. Fields in `predicate` are relative to the element.
   *
   * @param field - Query field path of the array, e.g. `state.items`.
   * @param predicate - Element-scoped filter expression.
   * @returns `{ op: 'ELEMENT_MATCH', field, predicate }`.
   * @throws TypeError If `predicate` contains, at any depth, a root-only filter
   *   (`ID`, `IDS`, `AGGREGATE_ID`, `AGGREGATE_IDS`, `TENANT_ID`, `OWNER_ID`,
   *   `SPACE_ID`, `DELETION` or `SEARCH`) or an `AND`, `OR` or `NOR` whose
   *   operands are empty or contain `null`, or if `field` is not a valid query field path.
   * @example
   * ```typescript
   * filter.elementMatch('state.items', filter.gt('quantity', 1));
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
  /**
   * Matches records by full-text search. Tokenization and matching depend on
   * the backend's analyzer. Root-only: not allowed inside `elementMatch`.
   *
   * @param query - Search text.
   * @param options - Optional `fields` (defaults to `[]`, the backend's default
   *   search fields) and `mode` (defaults to `SearchMode.TERMS`).
   * @returns `{ op: 'SEARCH', query, mode, fields }`.
   * @throws TypeError If `query` is not a string or is blank, `options` is
   *   given but is `null`, an array or not an object, `mode` is not a
   *   {@link SearchMode} member, or any of `fields` is not a valid query field
   *   path.
   * @example
   * ```typescript
   * filter.search('event sourcing', {
   *   fields: ['state.description'],
   *   mode: SearchMode.PHRASE,
   * });
   * ```
   */
  search<FIELDS extends string>(
    query: string,
    options?: SearchFilterOptions<FIELDS>,
  ): SearchFilter<FIELDS> {
    if (typeof query !== 'string' || !query.trim()) {
      throw new TypeError('SEARCH query cannot be blank.');
    }
    if (
      options !== undefined &&
      (options === null ||
        typeof options !== 'object' ||
        Array.isArray(options))
    ) {
      throw new TypeError('SEARCH options must be a non-null object.');
    }
    const { fields = [], mode = SearchMode.TERMS } = options ?? {};
    if (!Object.values(SearchMode).includes(mode)) {
      throw new TypeError(`SEARCH mode is invalid: [${String(mode)}].`);
    }
    return {
      op: FilterOperator.SEARCH,
      query,
      mode,
      fields: fields.map(queryField),
    };
  },
  /**
   * Matches times within today, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'TODAY', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.today('state.createTime', { zoneId: 'Asia/Shanghai' });
   * ```
   */
  today<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.TODAY, field, options);
  },
  /**
   * Matches times earlier than today at the local time `time`
   * (`field < today at time`) in the configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param time - 24-hour local time: `HH:mm`, `HH:mm:ss` or `HH:mm:ss.S` with
   *   1 to 9 fraction digits.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'BEFORE_TODAY', field, time, timeUnit }`, plus `zoneId`
   *   and `datePattern` when set.
   * @throws TypeError If `time` is not a string in one of the formats above,
   *   `field` is not a valid query field path, `zoneId` is blank or an invalid UTC offset,
   *   `datePattern` is blank or not a valid `java.time` pattern, or
   *   `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.beforeToday('state.createTime', '12:00');
   * ```
   */
  beforeToday<FIELDS extends string>(
    field: FIELDS,
    time: string,
    options: RelativeTimeFilterOptions = {},
  ): BeforeTodayFilter<FIELDS> {
    const localTime = requireLocalTime(time);
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.BEFORE_TODAY,
      field: queryField(field),
      time: localTime,
    };
  },
  /**
   * Matches times within tomorrow, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'TOMORROW', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.tomorrow('state.createTime');
   * ```
   */
  tomorrow<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.TOMORROW, field, options);
  },
  /**
   * Matches times within the current Monday-start week, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'THIS_WEEK', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.thisWeek('state.createTime');
   * ```
   */
  thisWeek<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.THIS_WEEK, field, options);
  },
  /**
   * Matches times within the next Monday-start week, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'NEXT_WEEK', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.nextWeek('state.createTime');
   * ```
   */
  nextWeek<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.NEXT_WEEK, field, options);
  },
  /**
   * Matches times within the previous Monday-start week, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'LAST_WEEK', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.lastWeek('state.createTime');
   * ```
   */
  lastWeek<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.LAST_WEEK, field, options);
  },
  /**
   * Matches times within the current calendar month, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'THIS_MONTH', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.thisMonth('state.createTime');
   * ```
   */
  thisMonth<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.THIS_MONTH, field, options);
  },
  /**
   * Matches times within the previous calendar month, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'LAST_MONTH', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.lastMonth('state.createTime');
   * ```
   */
  lastMonth<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.LAST_MONTH, field, options);
  },
  /**
   * Matches times within yesterday, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'YESTERDAY', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.yesterday('state.createTime');
   * ```
   */
  yesterday<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.YESTERDAY, field, options);
  },
  /**
   * Matches times within the next calendar month, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'NEXT_MONTH', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.nextMonth('state.createTime');
   * ```
   */
  nextMonth<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.NEXT_MONTH, field, options);
  },
  /**
   * Matches times within the previous calendar year, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'LAST_YEAR', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.lastYear('state.createTime');
   * ```
   */
  lastYear<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.LAST_YEAR, field, options);
  },
  /**
   * Matches times within the current calendar year, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'THIS_YEAR', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.thisYear('state.createTime', { timeUnit: TimeUnit.SECONDS });
   * ```
   */
  thisYear<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.THIS_YEAR, field, options);
  },
  /**
   * Matches times within the next calendar year, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'NEXT_YEAR', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.nextYear('state.createTime');
   * ```
   */
  nextYear<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.NEXT_YEAR, field, options);
  },
  /**
   * Matches times from the start of the day `days - 1` days ago until the end
   * of today. `recentDays(field, 7)` covers today and the six previous days.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param days - Number of calendar days, including today; an integer from
   *   1 to 2147483647.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'RECENT_DAYS', field, days, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `days` is not an integer from 1 to 2147483647,
   *   `field` is not a valid query field path, `zoneId` is blank or an invalid UTC offset,
   *   `datePattern` is blank or not a valid `java.time` pattern, or
   *   `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.recentDays('state.createTime', 7);
   * ```
   */
  recentDays<FIELDS extends string>(
    field: FIELDS,
    days: number,
    options: RelativeTimeFilterOptions = {},
  ): DaysFilter<FIELDS> {
    return dayWindow(FilterOperator.RECENT_DAYS, field, days, options);
  },
  /**
   * Matches times before the start of the day `days - 1` days ago, that is,
   * before the window that `recentDays` covers with the same `days`.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param days - Number of calendar days, including today; an integer from
   *   1 to 2147483647.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'EARLIER_DAYS', field, days, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `days` is not an integer from 1 to 2147483647,
   *   `field` is not a valid query field path, `zoneId` is blank or an invalid UTC offset,
   *   `datePattern` is blank or not a valid `java.time` pattern, or
   *   `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.earlierDays('state.createTime', 7);
   * ```
   */
  earlierDays<FIELDS extends string>(
    field: FIELDS,
    days: number,
    options: RelativeTimeFilterOptions = {},
  ): DaysFilter<FIELDS> {
    return dayWindow(FilterOperator.EARLIER_DAYS, field, days, options);
  },
  /**
   * Matches times strictly before the server's `now + offset`
   * (`field < now + offset`). The server reads its clock once per query,
   * so a saved query never depends on the client's clock, and the moment is
   * encoded the way the field stores time: an epoch in `timeUnit`, or text
   * in `datePattern` and `zoneId`.
   *
   * Needs a Wow server of 9.2.0 or later; an earlier one refuses `BEFORE_NOW`.
   *
   * @param field - Query field path, e.g. `state.timeoutAt`.
   * @param offset - ISO-8601 duration added to now, as
   *   `java.time.Duration.parse` reads it: days, hours, minutes and seconds,
   *   each optionally signed, e.g. `PT0S`, `-PT30M` or `P1DT2H`. A negative
   *   offset looks back. Defaults to `PT0S`, now itself.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'BEFORE_NOW', field, offset, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `offset` is not an ISO-8601 duration in that
   *   grammar, `field` is not a valid query field path, `zoneId` is blank or
   *   an invalid UTC offset, `datePattern` is blank or not a valid `java.time`
   *   pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * // Timed out: the deadline has passed.
   * filter.beforeNow('state.timeoutAt');
   * ```
   */
  beforeNow<FIELDS extends string>(
    field: FIELDS,
    offset = 'PT0S',
    options: RelativeTimeFilterOptions = {},
  ): NowFilter<FIELDS> {
    return nowRelative(FilterOperator.BEFORE_NOW, field, offset, options);
  },
  /**
   * Matches times strictly after the server's `now + offset`
   * (`field > now + offset`). The server reads its clock once per query,
   * so a saved query never depends on the client's clock, and the moment is
   * encoded the way the field stores time: an epoch in `timeUnit`, or text
   * in `datePattern` and `zoneId`.
   *
   * Needs a Wow server of 9.2.0 or later; an earlier one refuses `AFTER_NOW`.
   *
   * @param field - Query field path, e.g. `state.timeoutAt`.
   * @param offset - ISO-8601 duration added to now, as
   *   `java.time.Duration.parse` reads it: days, hours, minutes and seconds,
   *   each optionally signed, e.g. `PT0S`, `-PT30M` or `P1DT2H`. A negative
   *   offset looks back. Defaults to `PT0S`, now itself.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'AFTER_NOW', field, offset, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `offset` is not an ISO-8601 duration in that
   *   grammar, `field` is not a valid query field path, `zoneId` is blank or
   *   an invalid UTC offset, `datePattern` is blank or not a valid `java.time`
   *   pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * // Created in the last 30 minutes.
   * filter.afterNow('state.createTime', '-PT30M');
   * ```
   */
  afterNow<FIELDS extends string>(
    field: FIELDS,
    offset = 'PT0S',
    options: RelativeTimeFilterOptions = {},
  ): NowFilter<FIELDS> {
    return nowRelative(FilterOperator.AFTER_NOW, field, offset, options);
  },
};

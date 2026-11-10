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
import { FilterOperator, StringComparison } from './operator.js';
import type {
  BetweenFilter,
  CollectionFilter,
  ComparableFilterLiteral,
  ComparisonFilter,
  EqualityFilter,
  EqualityFilterValue,
  FieldPresenceFilter,
  StringFilter,
} from './types.js';
import {
  filterLiteral,
  requireNonEmpty,
  requiredString,
  validateStringComparison,
} from './validate.js';

// One builder per filter shape, keyed by the operator. Each shape's public
// builders below differ only in the operator they pass, so a new operator of
// an existing shape is one enum member, one type-union member and one line
// here; its JSDoc goes on the public builder, where the IDE shows it.

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
    value: requiredString(`${constantName(op)} value`, value),
    stringComparison,
  };
}

function collection<FIELDS extends string>(
  op: CollectionFilter['op'],
  field: FIELDS,
  values: readonly ComparableFilterLiteral[],
): CollectionFilter<FIELDS> {
  requireNonEmpty(`${constantName(op)} values`, values);
  values.forEach(value => filterLiteral(value, false));
  return { op, field: queryField(field), values: [...values] };
}

function presence<FIELDS extends string>(
  op: FieldPresenceFilter['op'],
  field: FIELDS,
): FieldPresenceFilter<FIELDS> {
  return { op, field: queryField(field) };
}

/** The filters on a field's value: equality, order, text, collections, ranges and presence. */
export const fieldFilters = {
  /**
   * Matches records whose `field` equals `value`.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param value - JSON scalar. `null` is sent as is; the server rewrites it
   *   to `IS_NULL`.
   * @returns `{ op: 'eq', field, value }`.
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
   * @returns `{ op: 'ne', field, value }`.
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
   * @returns `{ op: 'gt', field, value }`.
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
   * @returns `{ op: 'gte', field, value }`.
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
   * @returns `{ op: 'lt', field, value }`.
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
   * @returns `{ op: 'lte', field, value }`.
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
   * @returns `{ op: 'contains', field, value, stringComparison }`.
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
   * @returns `{ op: 'starts-with', field, value, stringComparison }`.
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
   * @returns `{ op: 'ends-with', field, value, stringComparison }`.
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
   * @returns `{ op: 'in', field, values }` with a copy of `values`.
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
   * @returns `{ op: 'not-in', field, values }` with a copy of `values`.
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
   * @returns `{ op: 'contains-all', field, values }` with a copy of `values`.
   * @throws TypeError If `values` is empty, contains `null` or `undefined`,
   *   or contains a value that is not a string, a boolean or a finite
   *   number, or if `field` is not a valid query field path.
   * @example
   * ```typescript
   * filter.containsAll('access.teams', ['sales', 'support']);
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
   * @returns `{ op: 'between', field, lowerBound, upperBound }`.
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
   * @returns `{ op: 'is-empty', field }`.
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
   * @returns `{ op: 'is-empty-string', field }`.
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
   * @returns `{ op: 'is-not-empty-string', field }`.
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
   * @returns `{ op: 'is-null', field }`.
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
   * @returns `{ op: 'is-not-null', field }`.
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
   * @returns `{ op: 'exists', field }`.
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
   * @returns `{ op: 'not-exists', field }`.
   * @throws TypeError If `field` is not a valid query field path.
   * @example
   * ```typescript
   * filter.notExists('state.note');
   * ```
   */
  notExists<FIELDS extends string>(field: FIELDS): FieldPresenceFilter<FIELDS> {
    return presence(FilterOperator.NOT_EXISTS, field);
  },
};

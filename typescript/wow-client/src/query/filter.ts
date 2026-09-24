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

import { DeletionState } from './deletionState.js';
import { requireElementScopedFilter } from './elementScope.js';
import { queryField } from './queryField.js';

/**
 * Dot-separated logical field path. The first segment is a name; later
 * segments are names or decimal array indexes. A name may start with `@`,
 * begins with an ASCII letter or `_`, and continues with ASCII letters,
 * digits, `_` or `-`. Builders throw `TypeError` for any other string.
 */
export type QueryField<FIELDS extends string = string> = FIELDS;
/** @deprecated Use QueryField instead. Removed in v10. */
export type LogicalField<FIELDS extends string = string> = QueryField<FIELDS>;
/**
 * JSON scalar accepted as a filter value. Numbers must be finite.
 */
export type FilterLiteral = null | string | number | boolean;
/**
 * Value accepted by `EQ` and `NE`, including `null`.
 */
export type EqualityFilterValue = FilterLiteral;
/**
 * Non-null JSON scalar accepted by comparison, range and collection filters.
 */
export type ComparableFilterLiteral = Exclude<FilterLiteral, null>;

/**
 * Discriminator sent as the `op` property of every filter expression. The
 * values match the server-side `FilterOperator` enum.
 */
export enum FilterOperator {
  MATCH_ALL = 'MATCH_ALL',
  MATCH_NONE = 'MATCH_NONE',
  ID = 'ID',
  IDS = 'IDS',
  AGGREGATE_ID = 'AGGREGATE_ID',
  AGGREGATE_IDS = 'AGGREGATE_IDS',
  TENANT_ID = 'TENANT_ID',
  OWNER_ID = 'OWNER_ID',
  SPACE_ID = 'SPACE_ID',
  AND = 'AND',
  OR = 'OR',
  NOR = 'NOR',
  EQ = 'EQ',
  NE = 'NE',
  GT = 'GT',
  GTE = 'GTE',
  LT = 'LT',
  LTE = 'LTE',
  CONTAINS = 'CONTAINS',
  STARTS_WITH = 'STARTS_WITH',
  ENDS_WITH = 'ENDS_WITH',
  IN = 'IN',
  NOT_IN = 'NOT_IN',
  BETWEEN = 'BETWEEN',
  CONTAINS_ALL = 'CONTAINS_ALL',
  IS_EMPTY = 'IS_EMPTY',
  IS_EMPTY_STRING = 'IS_EMPTY_STRING',
  IS_NOT_EMPTY_STRING = 'IS_NOT_EMPTY_STRING',
  IS_NULL = 'IS_NULL',
  IS_NOT_NULL = 'IS_NOT_NULL',
  EXISTS = 'EXISTS',
  NOT_EXISTS = 'NOT_EXISTS',
  DELETION = 'DELETION',
  ELEMENT_MATCH = 'ELEMENT_MATCH',
  SEARCH = 'SEARCH',
  TODAY = 'TODAY',
  BEFORE_TODAY = 'BEFORE_TODAY',
  TOMORROW = 'TOMORROW',
  THIS_WEEK = 'THIS_WEEK',
  NEXT_WEEK = 'NEXT_WEEK',
  LAST_WEEK = 'LAST_WEEK',
  THIS_MONTH = 'THIS_MONTH',
  LAST_MONTH = 'LAST_MONTH',
  YESTERDAY = 'YESTERDAY',
  NEXT_MONTH = 'NEXT_MONTH',
  LAST_YEAR = 'LAST_YEAR',
  THIS_YEAR = 'THIS_YEAR',
  NEXT_YEAR = 'NEXT_YEAR',
  RECENT_DAYS = 'RECENT_DAYS',
  EARLIER_DAYS = 'EARLIER_DAYS',
}

/**
 * Case handling for `CONTAINS`, `STARTS_WITH` and `ENDS_WITH`.
 */
export enum StringComparison {
  /**
   * Characters must match exactly. The default.
   */
  CASE_SENSITIVE = 'CASE_SENSITIVE',
  /**
   * Ignores case. Backends may execute this more expensively.
   */
  CASE_INSENSITIVE = 'CASE_INSENSITIVE',
}

/**
 * Matching mode of a full-text `SEARCH` filter.
 */
export enum SearchMode {
  /**
   * Analyzed terms match independently; they need not appear together. The
   * default.
   */
  TERMS = 'TERMS',
  /**
   * Analyzed terms must appear in order and at adjacent positions.
   */
  PHRASE = 'PHRASE',
}

/**
 * Unit of a numeric epoch time field targeted by a relative time filter.
 * Ignored when `datePattern` is set.
 */
export enum TimeUnit {
  NANOSECONDS = 'NANOSECONDS',
  MICROSECONDS = 'MICROSECONDS',
  MILLISECONDS = 'MILLISECONDS',
  SECONDS = 'SECONDS',
  MINUTES = 'MINUTES',
  HOURS = 'HOURS',
  DAYS = 'DAYS',
}

const LOCAL_TIME_PATTERN =
  /^([01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:\.[0-9]{1,9})?)?$/;
const OFFSET_ZONE_PATTERN =
  /^(?:UTC|GMT|UT)?[+-](\d{1,2}|\d{4}|\d{6}|\d{2}:\d{2}|\d{2}:\d{2}:\d{2})$/;
const OFFSET_ZONE_CANDIDATE_PATTERN = /^(?:UTC|GMT|UT)?[+-]/;
const DATE_PATTERN_COUNTS: Readonly<
  Record<string, number | readonly number[]>
> = {
  G: 5,
  u: 19,
  y: 19,
  Q: 5,
  q: 5,
  M: 5,
  L: 5,
  D: 3,
  d: 2,
  F: 1,
  E: 5,
  e: 5,
  c: [1, 3, 4, 5],
  a: 1,
  B: [1, 4, 5],
  h: 2,
  H: 2,
  k: 2,
  K: 2,
  m: 2,
  s: 2,
  S: 9,
  A: 19,
  n: 19,
  N: 19,
  V: [2],
  v: [1, 4],
  z: 4,
  O: [1, 4],
  X: 5,
  x: 5,
  Z: 5,
  W: 1,
  w: 2,
  Y: Number.POSITIVE_INFINITY,
  g: 19,
};

function filterLiteral<T extends FilterLiteral>(
  value: T,
  nullable: boolean,
): T {
  const valid =
    value === null
      ? nullable
      : typeof value === 'string' ||
        typeof value === 'boolean' ||
        (typeof value === 'number' && Number.isFinite(value));
  if (!valid) {
    throw new TypeError('Filter value must be a JSON scalar.');
  }
  return value;
}

function requiredString(name: string, value: string): string {
  if (typeof value !== 'string') {
    throw new TypeError(`${name} must be a string.`);
  }
  return value;
}

function validateStringComparison(comparison: StringComparison): void {
  if (
    comparison !== StringComparison.CASE_SENSITIVE &&
    comparison !== StringComparison.CASE_INSENSITIVE
  ) {
    throw new TypeError(
      `String comparison is invalid: [${String(comparison)}].`,
    );
  }
}

function requireNonEmpty(name: string, values: readonly unknown[]): void {
  if (values.length === 0) {
    throw new TypeError(`${name} cannot be empty.`);
  }
  if (values.some(value => value === null || value === undefined)) {
    throw new TypeError(`${name} cannot contain null.`);
  }
}

function isValidOffsetZone(zoneId: string): boolean {
  const match = OFFSET_ZONE_PATTERN.exec(zoneId);
  if (!match) return false;
  const offset = match[1];
  const parts = offset.includes(':')
    ? offset.split(':')
    : offset.length <= 2
      ? [offset]
      : [offset.slice(0, 2), offset.slice(2, 4), offset.slice(4, 6)];
  const [hours, minutes = 0, seconds = 0] = parts.map(Number);
  return (
    minutes <= 59 &&
    seconds <= 59 &&
    (hours < 18 || (hours === 18 && minutes === 0 && seconds === 0))
  );
}

function validateRelativeTimeOptions({
  zoneId,
  datePattern,
  timeUnit = TimeUnit.MILLISECONDS,
}: RelativeTimeFilterOptions): RelativeTimeFilterOptions & {
  timeUnit: TimeUnit;
} {
  if (zoneId !== undefined) {
    if (typeof zoneId !== 'string' || !zoneId.trim()) {
      throw new TypeError('zoneId cannot be blank.');
    }
    if (
      OFFSET_ZONE_CANDIDATE_PATTERN.test(zoneId) &&
      !isValidOffsetZone(zoneId)
    ) {
      throw new TypeError(`zoneId is invalid: [${zoneId}].`);
    }
  }
  if (datePattern !== undefined) {
    validateDatePattern(datePattern);
  }
  if (!Object.values(TimeUnit).includes(timeUnit)) {
    throw new TypeError(`timeUnit is invalid: [${String(timeUnit)}].`);
  }
  return {
    ...(zoneId === undefined ? {} : { zoneId }),
    ...(datePattern === undefined ? {} : { datePattern }),
    timeUnit,
  };
}

function validateDatePatternLetter(
  pattern: string,
  letter: string,
  count: number,
): void {
  const allowed = DATE_PATTERN_COUNTS[letter];
  const valid =
    typeof allowed === 'number'
      ? count <= allowed
      : allowed?.includes(count) === true;
  if (!valid) {
    throw new TypeError(`datePattern is invalid: [${pattern}].`);
  }
}

function isNumericDatePatternLetter(
  letter: string | undefined,
  count: number,
): boolean {
  return (
    letter !== undefined &&
    ('uyDFdhHkKmsSgAnNWwY'.includes(letter) ||
      (letter === 'c' && count === 1) ||
      ('eMLQq'.includes(letter) && count <= 2))
  );
}

function validateDatePattern(pattern: string): void {
  if (typeof pattern !== 'string' || !pattern.trim()) {
    throw new TypeError('datePattern cannot be blank.');
  }
  let quoted = false;
  let optionalDepth = 0;
  for (let index = 0; index < pattern.length; index++) {
    const character = pattern[index];
    if (character === "'") {
      if (pattern[index + 1] === "'") {
        index++;
      } else {
        quoted = !quoted;
      }
      continue;
    }
    if (quoted) continue;
    if (/[A-Za-z]/.test(character)) {
      let letter = character;
      let end = index + 1;
      while (pattern[end] === letter) end++;
      let count = end - index;
      let padded = false;
      if (letter === 'p') {
        padded = true;
        letter = pattern[end];
        if (!letter || !/[A-Za-z]/.test(letter) || letter === 'p') {
          throw new TypeError(`datePattern is invalid: [${pattern}].`);
        }
        const fieldStart = end++;
        while (pattern[end] === letter) end++;
        count = end - fieldStart;
      }
      validateDatePatternLetter(pattern, letter, count);
      if (padded && isNumericDatePatternLetter(letter, count)) {
        const nextLetter = pattern[end];
        let nextEnd = end;
        while (nextEnd < pattern.length && pattern[nextEnd] === nextLetter)
          nextEnd++;
        if (isNumericDatePatternLetter(nextLetter, nextEnd - end)) {
          throw new TypeError(`datePattern is invalid: [${pattern}].`);
        }
      }
      index = end - 1;
    } else if (character === '[') {
      optionalDepth++;
    } else if (character === ']') {
      if (optionalDepth === 0)
        throw new TypeError(`datePattern is invalid: [${pattern}].`);
      optionalDepth--;
    } else if ('{}#'.includes(character)) {
      throw new TypeError(`datePattern is invalid: [${pattern}].`);
    }
  }
  if (quoted) {
    throw new TypeError(`datePattern is invalid: [${pattern}].`);
  }
}

function validateDays(operator: FilterOperator, days: number): void {
  if (!Number.isInteger(days) || days < 1 || days > 2_147_483_647) {
    throw new TypeError(`${operator} days must be a positive JVM Int.`);
  }
}

/**
 * Constant filter: `MATCH_ALL` matches everything in the query scope,
 * `MATCH_NONE` matches nothing.
 */
export type MatchFilter = {
  op: FilterOperator.MATCH_ALL | FilterOperator.MATCH_NONE;
};

/**
 * Root-only filter on one system identifier: record ID, aggregate ID,
 * tenant, owner or space.
 */
export type MetadataValueFilter = {
  op:
    | FilterOperator.ID
    | FilterOperator.AGGREGATE_ID
    | FilterOperator.TENANT_ID
    | FilterOperator.OWNER_ID
    | FilterOperator.SPACE_ID;
  value: string;
};

/**
 * Root-only filter on a non-empty set of record IDs or aggregate IDs.
 */
export type MetadataValuesFilter = {
  op: FilterOperator.IDS | FilterOperator.AGGREGATE_IDS;
  values: string[];
};

/**
 * Any root-only system identifier filter.
 */
export type MetadataFilter = MetadataValueFilter | MetadataValuesFilter;

/**
 * `AND`, `OR` or `NOR` over a non-empty list of filter expressions.
 */
export type LogicalFilter<FIELDS extends string = string> = {
  op: FilterOperator.AND | FilterOperator.OR | FilterOperator.NOR;
  operands: FilterExpression<FIELDS>[];
};

/**
 * `AND`, `OR` or `NOR` whose operands are all valid inside an
 * `ELEMENT_MATCH` predicate.
 */
export type ElementLogicalFilter<FIELDS extends string = string> = {
  op: FilterOperator.AND | FilterOperator.OR | FilterOperator.NOR;
  operands: ElementFilterExpression<FIELDS>[];
};

/**
 * `EQ` or `NE` on a field. The server rewrites a `null` value to `IS_NULL`
 * or `IS_NOT_NULL`.
 */
export type EqualityFilter<FIELDS extends string = string> = {
  op: FilterOperator.EQ | FilterOperator.NE;
  field: QueryField<FIELDS>;
  value: EqualityFilterValue;
};

/**
 * `GT`, `GTE`, `LT` or `LTE` on a field against a non-null scalar.
 */
export type ComparisonFilter<FIELDS extends string = string> = {
  op:
    | FilterOperator.GT
    | FilterOperator.GTE
    | FilterOperator.LT
    | FilterOperator.LTE;
  field: QueryField<FIELDS>;
  value: ComparableFilterLiteral;
};

/**
 * Literal substring, prefix or suffix match on a string field. No full-text
 * analyzer is applied. `stringComparison` defaults to `CASE_SENSITIVE`.
 */
export type StringFilter<FIELDS extends string = string> = {
  op:
    | FilterOperator.CONTAINS
    | FilterOperator.STARTS_WITH
    | FilterOperator.ENDS_WITH;
  field: QueryField<FIELDS>;
  value: string;
  stringComparison?: StringComparison;
};

/**
 * `IN`, `NOT_IN` or `CONTAINS_ALL` on a field against a non-empty list of
 * non-null scalars.
 */
export type CollectionFilter<FIELDS extends string = string> = {
  op: FilterOperator.IN | FilterOperator.NOT_IN | FilterOperator.CONTAINS_ALL;
  field: QueryField<FIELDS>;
  values: ComparableFilterLiteral[];
};

/**
 * Inclusive range on a field: `lowerBound <= field <= upperBound`.
 */
export type BetweenFilter<FIELDS extends string = string> = {
  op: FilterOperator.BETWEEN;
  field: QueryField<FIELDS>;
  lowerBound: ComparableFilterLiteral;
  upperBound: ComparableFilterLiteral;
};

/**
 * Operand-free check of a field's null, existence, empty-collection or
 * empty-string state.
 */
export type FieldPresenceFilter<FIELDS extends string = string> = {
  op:
    | FilterOperator.IS_EMPTY
    | FilterOperator.IS_EMPTY_STRING
    | FilterOperator.IS_NOT_EMPTY_STRING
    | FilterOperator.IS_NULL
    | FilterOperator.IS_NOT_NULL
    | FilterOperator.EXISTS
    | FilterOperator.NOT_EXISTS;
  field: QueryField<FIELDS>;
};

/**
 * Root-only filter on the deletion state of snapshots.
 */
export type DeletionFilter = {
  op: FilterOperator.DELETION;
  state: DeletionState;
};

/**
 * Matches when a single element of the array `field` satisfies `predicate`.
 * Predicate fields are relative to the element.
 */
export type ElementMatchFilter<
  FIELDS extends string = string,
  ELEMENT_FIELDS extends string = string,
> = {
  op: FilterOperator.ELEMENT_MATCH;
  field: QueryField<FIELDS>;
  predicate: ElementFilterExpression<ELEMENT_FIELDS>;
};

/**
 * Root-only full-text search. Matching depends on the backend's index and
 * analyzer. Empty `fields` means the backend's default search fields.
 */
export type SearchFilter<FIELDS extends string = string> = {
  op: FilterOperator.SEARCH;
  query: string;
  fields?: QueryField<FIELDS>[];
  /**
   * Matching mode. Defaults to `SearchMode.TERMS`.
   */
  mode?: SearchMode;
};

/**
 * Options for {@link filter.search}.
 */
export interface SearchFilterOptions<FIELDS extends string = string> {
  /**
   * Fields to search. Defaults to `[]`, the backend's default search fields.
   */
  fields?: readonly QueryField<FIELDS>[];
  /**
   * Matching mode. Defaults to `SearchMode.TERMS`.
   */
  mode?: SearchMode;
}

/**
 * Options shared by relative time filters. Unset options are omitted from
 * the payload, except `timeUnit`, which the builders always send.
 */
export interface RelativeTimeFilterOptions {
  /**
   * Time zone that defines calendar days, e.g. `Asia/Shanghai` or `+08:00`.
   * Defaults to the server process time zone. Must not be blank. A value
   * that starts with an optional `UTC`, `GMT` or `UT` prefix followed by `+`
   * or `-` must be a valid offset within +/-18:00; region IDs are checked by
   * the server only.
   */
  zoneId?: string;
  /**
   * `java.time.format.DateTimeFormatter` pattern for fields stored as
   * formatted strings, e.g. `yyyy-MM-dd`. Must equal the pattern declared by
   * the query schema. Must not be blank.
   */
  datePattern?: string;
  /**
   * Unit of a numeric epoch field. Defaults to `TimeUnit.MILLISECONDS`.
   * Ignored when `datePattern` is set.
   */
  timeUnit?: TimeUnit;
}

/**
 * Matches times in a calendar window relative to now: `[start, end)` in the
 * configured time zone. Weeks start on Monday.
 */
export type CalendarFilter<FIELDS extends string = string> =
  RelativeTimeFilterOptions & {
    op:
      | FilterOperator.TODAY
      | FilterOperator.TOMORROW
      | FilterOperator.THIS_WEEK
      | FilterOperator.NEXT_WEEK
      | FilterOperator.LAST_WEEK
      | FilterOperator.THIS_MONTH
      | FilterOperator.LAST_MONTH
      | FilterOperator.YESTERDAY
      | FilterOperator.NEXT_MONTH
      | FilterOperator.LAST_YEAR
      | FilterOperator.THIS_YEAR
      | FilterOperator.NEXT_YEAR;
    field: QueryField<FIELDS>;
  };

/**
 * Matches times earlier than today at the local time `time`.
 */
export type BeforeTodayFilter<FIELDS extends string = string> =
  RelativeTimeFilterOptions & {
    op: FilterOperator.BEFORE_TODAY;
    field: QueryField<FIELDS>;
    time: string;
  };

/**
 * `RECENT_DAYS` matches today and the `days - 1` previous calendar days;
 * `EARLIER_DAYS` matches times before that window.
 */
export type DaysFilter<FIELDS extends string = string> =
  RelativeTimeFilterOptions & {
    op: FilterOperator.RECENT_DAYS | FilterOperator.EARLIER_DAYS;
    field: QueryField<FIELDS>;
    days: number;
  };

/**
 * Filter expression allowed inside an `ELEMENT_MATCH` predicate. Excludes
 * root-only filters: system identifiers, `DELETION` and `SEARCH`.
 */
export type ElementFilterExpression<FIELDS extends string = string> =
  | MatchFilter
  | ElementLogicalFilter<FIELDS>
  | EqualityFilter<FIELDS>
  | ComparisonFilter<FIELDS>
  | StringFilter<FIELDS>
  | CollectionFilter<FIELDS>
  | BetweenFilter<FIELDS>
  | FieldPresenceFilter<FIELDS>
  | ElementMatchFilter<FIELDS>
  | CalendarFilter<FIELDS>
  | BeforeTodayFilter<FIELDS>
  | DaysFilter<FIELDS>;

/**
 * Filter expression sent to the query API. Build values with
 * {@link filter}.
 */
export type FilterExpression<FIELDS extends string = string> =
  | MatchFilter
  | MetadataFilter
  | LogicalFilter<FIELDS>
  | EqualityFilter<FIELDS>
  | ComparisonFilter<FIELDS>
  | StringFilter<FIELDS>
  | CollectionFilter<FIELDS>
  | BetweenFilter<FIELDS>
  | FieldPresenceFilter<FIELDS>
  | DeletionFilter
  | ElementMatchFilter<FIELDS>
  | SearchFilter<FIELDS>
  | CalendarFilter<FIELDS>
  | BeforeTodayFilter<FIELDS>
  | DaysFilter<FIELDS>;

/**
 * Query that carries a filter expression.
 */
export interface FilterCapable<FIELDS extends string = string> {
  filter: FilterExpression<FIELDS>;
}

function andFilter<FIELDS extends string>(
  operands: readonly ElementFilterExpression<FIELDS>[],
): ElementLogicalFilter<FIELDS>;
function andFilter<FIELDS extends string>(
  operands: readonly FilterExpression<FIELDS>[],
): LogicalFilter<FIELDS>;
function andFilter<FIELDS extends string>(
  operands: readonly FilterExpression<FIELDS>[],
): LogicalFilter<FIELDS> {
  requireNonEmpty('AND operands', operands);
  return { op: FilterOperator.AND, operands: [...operands] };
}

function orFilter<FIELDS extends string>(
  operands: readonly ElementFilterExpression<FIELDS>[],
): ElementLogicalFilter<FIELDS>;
function orFilter<FIELDS extends string>(
  operands: readonly FilterExpression<FIELDS>[],
): LogicalFilter<FIELDS>;
function orFilter<FIELDS extends string>(
  operands: readonly FilterExpression<FIELDS>[],
): LogicalFilter<FIELDS> {
  requireNonEmpty('OR operands', operands);
  return { op: FilterOperator.OR, operands: [...operands] };
}

function norFilter<FIELDS extends string>(
  operands: readonly ElementFilterExpression<FIELDS>[],
): ElementLogicalFilter<FIELDS>;
function norFilter<FIELDS extends string>(
  operands: readonly FilterExpression<FIELDS>[],
): LogicalFilter<FIELDS>;
function norFilter<FIELDS extends string>(
  operands: readonly FilterExpression<FIELDS>[],
): LogicalFilter<FIELDS> {
  requireNonEmpty('NOR operands', operands);
  return { op: FilterOperator.NOR, operands: [...operands] };
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
    return { op: FilterOperator.ID, value: requiredString('ID value', value) };
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
    requireNonEmpty('IDS values', values);
    values.forEach(value => requiredString('IDS value', value));
    return { op: FilterOperator.IDS, values: [...values] };
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
    return {
      op: FilterOperator.AGGREGATE_ID,
      value: requiredString('AGGREGATE_ID value', value),
    };
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
    requireNonEmpty('AGGREGATE_IDS values', values);
    values.forEach(value => requiredString('AGGREGATE_IDS value', value));
    return { op: FilterOperator.AGGREGATE_IDS, values: [...values] };
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
    return {
      op: FilterOperator.TENANT_ID,
      value: requiredString('TENANT_ID value', value),
    };
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
    return {
      op: FilterOperator.OWNER_ID,
      value: requiredString('OWNER_ID value', value),
    };
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
    return {
      op: FilterOperator.SPACE_ID,
      value: requiredString('SPACE_ID value', value),
    };
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
    return {
      op: FilterOperator.EQ,
      field: queryField(field),
      value: filterLiteral(value, true),
    };
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
    return {
      op: FilterOperator.NE,
      field: queryField(field),
      value: filterLiteral(value, true),
    };
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
    return {
      op: FilterOperator.GT,
      field: queryField(field),
      value: filterLiteral(value, false),
    };
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
    return {
      op: FilterOperator.GTE,
      field: queryField(field),
      value: filterLiteral(value, false),
    };
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
    return {
      op: FilterOperator.LT,
      field: queryField(field),
      value: filterLiteral(value, false),
    };
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
    return {
      op: FilterOperator.LTE,
      field: queryField(field),
      value: filterLiteral(value, false),
    };
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
    validateStringComparison(stringComparison);
    return {
      op: FilterOperator.CONTAINS,
      field: queryField(field),
      value: requiredString('CONTAINS value', value),
      stringComparison,
    };
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
    validateStringComparison(stringComparison);
    return {
      op: FilterOperator.STARTS_WITH,
      field: queryField(field),
      value: requiredString('STARTS_WITH value', value),
      stringComparison,
    };
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
    validateStringComparison(stringComparison);
    return {
      op: FilterOperator.ENDS_WITH,
      field: queryField(field),
      value: requiredString('ENDS_WITH value', value),
      stringComparison,
    };
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
    requireNonEmpty('IN values', values);
    values.forEach(value => filterLiteral(value, false));
    return {
      op: FilterOperator.IN,
      field: queryField(field),
      values: [...values],
    };
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
    requireNonEmpty('NOT_IN values', values);
    values.forEach(value => filterLiteral(value, false));
    return {
      op: FilterOperator.NOT_IN,
      field: queryField(field),
      values: [...values],
    };
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
    requireNonEmpty('CONTAINS_ALL values', values);
    values.forEach(value => filterLiteral(value, false));
    return {
      op: FilterOperator.CONTAINS_ALL,
      field: queryField(field),
      values: [...values],
    };
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
    return { op: FilterOperator.IS_EMPTY, field: queryField(field) };
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
    return { op: FilterOperator.IS_EMPTY_STRING, field: queryField(field) };
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
    return {
      op: FilterOperator.IS_NOT_EMPTY_STRING,
      field: queryField(field),
    };
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
    return { op: FilterOperator.IS_NULL, field: queryField(field) };
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
    return { op: FilterOperator.IS_NOT_NULL, field: queryField(field) };
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
    return { op: FilterOperator.EXISTS, field: queryField(field) };
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
    return { op: FilterOperator.NOT_EXISTS, field: queryField(field) };
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
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.TODAY,
      field: queryField(field),
    };
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
    if (typeof time !== 'string' || !LOCAL_TIME_PATTERN.test(time)) {
      throw new TypeError('BEFORE_TODAY time is invalid.');
    }
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.BEFORE_TODAY,
      field: queryField(field),
      time,
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
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.TOMORROW,
      field: queryField(field),
    };
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
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.THIS_WEEK,
      field: queryField(field),
    };
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
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.NEXT_WEEK,
      field: queryField(field),
    };
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
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.LAST_WEEK,
      field: queryField(field),
    };
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
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.THIS_MONTH,
      field: queryField(field),
    };
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
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.LAST_MONTH,
      field: queryField(field),
    };
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
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.YESTERDAY,
      field: queryField(field),
    };
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
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.NEXT_MONTH,
      field: queryField(field),
    };
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
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.LAST_YEAR,
      field: queryField(field),
    };
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
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.THIS_YEAR,
      field: queryField(field),
    };
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
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.NEXT_YEAR,
      field: queryField(field),
    };
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
    validateDays(FilterOperator.RECENT_DAYS, days);
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.RECENT_DAYS,
      field: queryField(field),
      days,
    };
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
    validateDays(FilterOperator.EARLIER_DAYS, days);
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.EARLIER_DAYS,
      field: queryField(field),
      days,
    };
  },
};

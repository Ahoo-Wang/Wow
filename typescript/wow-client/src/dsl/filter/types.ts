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

import type { DeletionState } from '../deletionState.js';
import type {
  FilterOperator,
  SearchMode,
  StringComparison,
  TimeUnit,
} from './operator.js';

/**
 * Dot-separated logical field path. The first segment is a name; later
 * segments are names or decimal array indexes. A name may start with `@`,
 * begins with an ASCII letter or `_`, and continues with ASCII letters,
 * digits, `_` or `-`. Builders throw `TypeError` for any other string.
 */
export type QueryField<FIELDS extends string = string> = FIELDS;
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
 * `BEFORE_NOW` matches times strictly before the server's `now + offset`;
 * `AFTER_NOW` matches times strictly after it. The server reads its clock
 * once per query, so every condition of one query compares against the same
 * moment and a saved query never depends on the client's clock. `offset` is
 * an ISO-8601 duration (`PT0S`, `-PT30M`, `P1DT2H`); a negative offset looks
 * back.
 *
 * Needs a Wow server of 9.2.0 or later; an earlier one refuses the operator.
 */
export type NowFilter<FIELDS extends string = string> =
  RelativeTimeFilterOptions & {
    op: FilterOperator.BEFORE_NOW | FilterOperator.AFTER_NOW;
    field: QueryField<FIELDS>;
    offset: string;
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
  | DaysFilter<FIELDS>
  | NowFilter<FIELDS>;

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
  | DaysFilter<FIELDS>
  | NowFilter<FIELDS>;

/**
 * Query that carries a filter expression.
 */
export interface FilterCapable<FIELDS extends string = string> {
  filter: FilterExpression<FIELDS>;
}

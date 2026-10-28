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

/**
 * Discriminator sent as the `op` property of every filter expression. The
 * values match the server-side `FilterOperator` enum.
 */
export enum FilterOperator {
  MATCH_ALL = 'match-all',
  MATCH_NONE = 'match-none',
  ID = 'id',
  IDS = 'ids',
  AGGREGATE_ID = 'aggregate-id',
  AGGREGATE_IDS = 'aggregate-ids',
  TENANT_ID = 'tenant-id',
  OWNER_ID = 'owner-id',
  SPACE_ID = 'space-id',
  AND = 'and',
  OR = 'or',
  NOR = 'nor',
  EQ = 'eq',
  NE = 'ne',
  GT = 'gt',
  GTE = 'gte',
  LT = 'lt',
  LTE = 'lte',
  CONTAINS = 'contains',
  STARTS_WITH = 'starts-with',
  ENDS_WITH = 'ends-with',
  IN = 'in',
  NOT_IN = 'not-in',
  BETWEEN = 'between',
  CONTAINS_ALL = 'contains-all',
  IS_EMPTY = 'is-empty',
  IS_EMPTY_STRING = 'is-empty-string',
  IS_NOT_EMPTY_STRING = 'is-not-empty-string',
  IS_NULL = 'is-null',
  IS_NOT_NULL = 'is-not-null',
  EXISTS = 'exists',
  NOT_EXISTS = 'not-exists',
  DELETION = 'deletion',
  ELEMENT_MATCH = 'element-match',
  SEARCH = 'search',
  TODAY = 'today',
  BEFORE_TODAY = 'before-today',
  TOMORROW = 'tomorrow',
  THIS_WEEK = 'this-week',
  NEXT_WEEK = 'next-week',
  LAST_WEEK = 'last-week',
  THIS_MONTH = 'this-month',
  LAST_MONTH = 'last-month',
  YESTERDAY = 'yesterday',
  NEXT_MONTH = 'next-month',
  LAST_YEAR = 'last-year',
  THIS_YEAR = 'this-year',
  NEXT_YEAR = 'next-year',
  RECENT_DAYS = 'recent-days',
  EARLIER_DAYS = 'earlier-days',
  /**
   * The field is strictly before the server's `now + offset`. Needs a Wow
   * server of 9.2.0 or later.
   */
  BEFORE_NOW = 'before-now',
  /**
   * The field is strictly after the server's `now + offset`. Needs a Wow
   * server of 9.2.0 or later.
   */
  AFTER_NOW = 'after-now',
  /**
   * A computed expression, such as the hours between two time fields,
   * compared with a number. Evaluated per record, so expensive; a record
   * whose expression has no value never matches. Needs a Wow server of
   * 9.2.0 or later.
   */
  EXPRESSION = 'expression',
}

/**
 * Case handling for `CONTAINS`, `STARTS_WITH` and `ENDS_WITH`.
 */
export enum StringComparison {
  /**
   * Characters must match exactly. The default.
   */
  CASE_SENSITIVE = 'case-sensitive',
  /**
   * Ignores case. Backends may execute this more expensively.
   */
  CASE_INSENSITIVE = 'case-insensitive',
}

/**
 * Matching mode of a full-text `SEARCH` filter.
 */
export enum SearchMode {
  /**
   * Analyzed terms match independently; they need not appear together. The
   * default.
   */
  TERMS = 'terms',
  /**
   * Analyzed terms must appear in order and at adjacent positions.
   */
  PHRASE = 'phrase',
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

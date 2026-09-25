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
  /**
   * The field is strictly before the server's `now + offset`. Needs a Wow
   * server of 9.2.0 or later.
   */
  BEFORE_NOW = 'BEFORE_NOW',
  /**
   * The field is strictly after the server's `now + offset`. Needs a Wow
   * server of 9.2.0 or later.
   */
  AFTER_NOW = 'AFTER_NOW',
  /**
   * A computed expression, such as the hours between two time fields,
   * compared with a number. Evaluated per record, so expensive; a record
   * whose expression has no value never matches. Needs a Wow server of
   * 9.2.0 or later.
   */
  EXPRESSION = 'EXPRESSION',
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

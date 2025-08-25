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

export enum Operator {
  /**
   * Performs logical AND on the provided condition list
   */
  AND = 'AND',

  /**
   * Performs logical OR on the provided condition list
   */
  OR = 'OR',

  /**
   * Performs logical NOR on the provided condition list
   */
  NOR = 'NOR',

  /**
   * Matches all documents where the `id` field value equals the specified value
   */
  ID = 'ID',

  /**
   * Matches all documents where the `id` field value equals any value in the specified list
   */
  IDS = 'IDS',

  /**
   * Matches documents where the aggregate root ID equals the specified value
   */
  AGGREGATE_ID = 'AGGREGATE_ID',

  /**
   * Matches all documents where the aggregate root ID equals any value in the specified list
   */
  AGGREGATE_IDS = 'AGGREGATE_IDS',

  /**
   * Matches all documents where the `tenantId` field value equals the specified value
   */
  TENANT_ID = 'TENANT_ID',

  /**
   * Matches all documents where the `ownerId` field value equals the specified value
   */
  OWNER_ID = 'OWNER_ID',

  /**
   * Matches all documents where the `deleted` field value equals the specified value
   */
  DELETED = 'DELETED',

  /**
   * Matches all documents
   */
  ALL = 'ALL',

  /**
   * Matches all documents where the field name value equals the specified value
   */
  EQ = 'EQ',

  /**
   * Matches all documents where the field name value does not equal the specified value
   */
  NE = 'NE',

  /**
   * Matches all documents where the given field's value is greater than the specified value
   */
  GT = 'GT',

  /**
   * Matches all documents where the given field's value is less than the specified value
   */
  LT = 'LT',

  /**
   * Matches all documents where the given field's value is greater than or equal to the specified value
   */
  GTE = 'GTE',

  /**
   * Matches all documents where the given field's value is less than or equal to the specified value
   */
  LTE = 'LTE',

  /**
   * Matches all documents where the given field's value contains the specified value
   */
  CONTAINS = 'CONTAINS',

  /**
   * Matches all documents where the field value equals any value in the specified list
   */
  IN = 'IN',

  /**
   * Matches all documents where the field value does not equal any specified value or does not exist
   */
  NOT_IN = 'NOT_IN',

  /**
   * Matches all documents where the field value is within the specified range
   */
  BETWEEN = 'BETWEEN',

  /**
   * Matches all documents where the field value is an array containing all specified values
   */
  ALL_IN = 'ALL_IN',

  /**
   * Matches documents where the field value starts with the specified string
   */
  STARTS_WITH = 'STARTS_WITH',

  /**
   * Matches documents where the field value ends with the specified string
   */
  ENDS_WITH = 'ENDS_WITH',

  /**
   * Matches all documents where the condition includes an array field,
   * and at least one member of the array matches the given condition.
   */
  ELEM_MATCH = 'ELEM_MATCH',

  /**
   * Matches all documents where the field value is `null`
   */
  NULL = 'NULL',

  /**
   * Matches all documents where the field value is not `null`
   */
  NOT_NULL = 'NOT_NULL',

  /**
   * Matches all documents where the field value is `true`
   */
  TRUE = 'TRUE',

  /**
   * Matches all documents where the field value is `false`
   */
  FALSE = 'FALSE',

  /**
   * Matches documents based on whether the field exists
   */
  EXISTS = 'EXISTS',

  // #region Date filtering conditions, field requirement: `long` type timestamp in milliseconds
  /**
   * Matches all documents where the field is within today's range
   * > For example: if `today` is `2024-06-06`, matches documents in the range
   * `2024-06-06 00:00:00.000` ~ `2024-06-06 23:59:59.999`
   */
  TODAY = 'TODAY',

  /**
   * Matches all documents where the field is before today
   */
  BEFORE_TODAY = 'BEFORE_TODAY',

  /**
   * Matches all documents where the field is within yesterday's range
   * > For example: if `today` is `2024-06-06`, matches documents in the range
   * `2024-06-05 00:00:00.000` ~ `2024-06-05 23:59:59.999`
   */
  TOMORROW = 'TOMORROW',

  /**
   * Matches all documents where the field is within this week's range
   */
  THIS_WEEK = 'THIS_WEEK',

  /**
   * Matches all documents where the field is within next week's range
   */
  NEXT_WEEK = 'NEXT_WEEK',

  /**
   * Matches all documents where the field is within last week's range
   */
  LAST_WEEK = 'LAST_WEEK',

  /**
   * Matches all documents where the field is within this month's range
   * > For example:
   * - `today`: `2024-06-06`
   * - Matching range: `2024-06-01 00:00:00.000` ~ `2024-06-30 23:59:59.999`
   */
  THIS_MONTH = 'THIS_MONTH',

  /**
   * Matches all documents where the field is within last month's range
   * > For example:
   * - `today`: `2024-06-06`
   * - Matching range: `2024-05-01 00:00:00.000` ~ `2024-05-31 23:59:59.999`
   */
  LAST_MONTH = 'LAST_MONTH',

  /**
   * Matches all documents where the field is within the specified number of recent days
   * > For example: last 3 days
   * - `today`: `2024-06-06`
   * - Matching range: `2024-06-04 00:00:00.000` ~ `2024-06-06 23:59:59.999`
   * - That is: today, yesterday, the day before yesterday
   */
  RECENT_DAYS = 'RECENT_DAYS',

  /**
   * Matches all documents where the field is before the specified number of days
   *
   * > For example: before 3 days
   * - `today`: `2024-06-06`
   * - Matching range: all documents less than `2024-06-04 00:00:00.000`
   */
  EARLIER_DAYS = 'EARLIER_DAYS',
  // #endregion

  /**
   * Raw operator, uses the condition value directly as the raw database query condition
   */
  RAW = 'RAW',
}
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

package me.ahoo.wow.api.query

/**
 * Enumeration of query operators used for building database query conditions.
 *
 * Each operator defines a specific type of comparison or logical operation that can be
 * applied to fields in database queries. Operators range from simple equality checks
 * to complex date range queries and array operations.
 */
enum class Operator {
    /**
     * Logical AND operator.
     * Combines multiple conditions where all must be true.
     */
    AND,

    /**
     * Logical OR operator.
     * Combines multiple conditions where at least one must be true.
     */
    OR,

    /**
     * Logical NOR operator.
     * Combines multiple conditions where none must be true.
     */
    NOR,

    /**
     * ID equality operator.
     * Matches documents where the 'id' field equals the specified value.
     */
    ID,

    /**
     * ID list operator.
     * Matches documents where the 'id' field equals any value in the specified list.
     */
    IDS,

    /**
     * Aggregate ID equality operator.
     * Matches documents where the aggregate root ID equals the specified value.
     */
    AGGREGATE_ID,

    /**
     * Aggregate ID list operator.
     * Matches documents where the aggregate root ID equals any value in the specified list.
     */
    AGGREGATE_IDS,

    /**
     * Tenant ID equality operator.
     * Matches documents where the 'tenantId' field equals the specified value.
     */
    TENANT_ID,

    /**
     * Owner ID equality operator.
     * Matches documents where the 'ownerId' field equals the specified value.
     */
    OWNER_ID,

    /**
     * Deletion state operator.
     * Matches documents where the 'deleted' field equals the specified value.
     */
    DELETED,

    /**
     * Match all operator.
     * Matches all documents without any filtering.
     */
    ALL,

    /**
     * Equality operator.
     * Matches documents where the specified field equals the given value.
     */
    EQ,

    /**
     * Not-equal operator.
     * Matches documents where the specified field does not equal the given value.
     */
    NE,

    /**
     * Greater-than operator.
     * Matches documents where the specified field is greater than the given value.
     */
    GT,

    /**
     * Less-than operator.
     * Matches documents where the specified field is less than the given value.
     */
    LT,

    /**
     * Greater-than-or-equal operator.
     * Matches documents where the specified field is greater than or equal to the given value.
     */
    GTE,

    /**
     * Less-than-or-equal operator.
     * Matches documents where the specified field is less than or equal to the given value.
     */
    LTE,

    /**
     * Contains operator.
     * Matches documents where the specified field contains the given substring.
     */
    CONTAINS,

    /**
     * In operator.
     * Matches documents where the specified field equals any value in the given list.
     */
    IN,

    /**
     * Not-in operator.
     * Matches documents where the specified field does not equal any value in the given list.
     */
    NOT_IN,

    /**
     * Between operator.
     * Matches documents where the specified field falls within the given range (inclusive).
     */
    BETWEEN,

    /**
     * All-in operator.
     * Matches documents where the specified array field contains all of the given values.
     */
    ALL_IN,

    /**
     * Starts-with operator.
     * Matches documents where the specified field starts with the given prefix.
     */
    STARTS_WITH,

    /**
     * Ends-with operator.
     * Matches documents where the specified field ends with the given suffix.
     */
    ENDS_WITH,

    /**
     * Element match operator.
     * Matches documents where at least one element in the specified array field matches the given condition.
     */
    ELEM_MATCH,

    /**
     * Null check operator.
     * Matches documents where the specified field is null.
     */
    NULL,

    /**
     * Not-null check operator.
     * Matches documents where the specified field is not null.
     */
    NOT_NULL,

    /**
     * Boolean true operator.
     * Matches documents where the specified field is true.
     */
    TRUE,

    /**
     * Boolean false operator.
     * Matches documents where the specified field is false.
     */
    FALSE,

    /**
     * Field existence operator.
     * Matches documents based on whether the specified field exists.
     */
    EXISTS,

    // Date-based filtering operators (fields must be long timestamps in milliseconds)

    /**
     * Today operator.
     * Matches documents where the date field falls within today's date range.
     * Example: If today is 2024-06-06, matches range 2024-06-06 00:00:00.000 to 2024-06-06 23:59:59.999.
     */
    TODAY,

    /**
     * Before today operator.
     * Matches documents where the date field is before the specified time today.
     */
    BEFORE_TODAY,

    /**
     * Tomorrow operator.
     * Matches documents where the date field falls within tomorrow's date range.
     * Example: If today is 2024-06-06, matches range 2024-06-07 00:00:00.000 to 2024-06-07 23:59:59.999.
     */
    TOMORROW,

    /**
     * This week operator.
     * Matches documents where the date field falls within the current week's date range.
     */
    THIS_WEEK,

    /**
     * Next week operator.
     * Matches documents where the date field falls within next week's date range.
     */
    NEXT_WEEK,

    /**
     * Last week operator.
     * Matches documents where the date field falls within last week's date range.
     */
    LAST_WEEK,

    /**
     * This month operator.
     * Matches documents where the date field falls within the current month's date range.
     * Example: If today is 2024-06-06, matches range 2024-06-01 00:00:00.000 to 2024-06-30 23:59:59.999.
     */
    THIS_MONTH,

    /**
     * Last month operator.
     * Matches documents where the date field falls within last month's date range.
     * Example: If today is 2024-06-06, matches range 2024-05-01 00:00:00.000 to 2024-05-31 23:59:59.999.
     */
    LAST_MONTH,

    /**
     * Recent days operator.
     * Matches documents where the date field falls within the last N days (including today).
     * Example: For 3 recent days, if today is 2024-06-06, matches from 2024-06-04 00:00:00.000 onwards.
     */
    RECENT_DAYS,

    /**
     * Earlier days operator.
     * Matches documents where the date field is earlier than N days ago.
     * Example: For 3 days ago, if today is 2024-06-06, matches anything before 2024-06-04 00:00:00.000.
     */
    EARLIER_DAYS,

    /**
     * Raw operator.
     * Uses the condition value directly as a raw database query condition without any processing.
     */
    RAW
}

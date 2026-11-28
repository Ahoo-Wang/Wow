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

package me.ahoo.wow.query.spec

import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.NotEqualFilter

/**
 * Whether this condition matches where its field is absent, as the query language's Presence table states it: a
 * missing field is null, so `ne` and `not-in` of a value, `is-null`, `eq` of `null` and `not-exists` match it, and
 * every other condition on a field does not: a comparison, a string operator, a range, `in`, `contains-all`, `exists`,
 * `is-not-null` (`ne` of `null`), the empty tests, an element match (no element), a computed `expression` (no value)
 * and a `search` (no word). `null` for an operator that names no field of its own: `match-all`, `match-none`, the
 * logical ones and those of a system field.
 *
 * The in-memory backend, the reference semantics, answers the same for every operator (`AbsentFieldsTest`); a new
 * operator is classified here before it compiles.
 */
internal fun FilterExpression.matchesAbsentField(): Boolean? = when (operator) {
    FilterOperator.MATCH_ALL,
    FilterOperator.MATCH_NONE,
    FilterOperator.AND,
    FilterOperator.OR,
    FilterOperator.NOR,
    FilterOperator.ID,
    FilterOperator.IDS,
    FilterOperator.AGGREGATE_ID,
    FilterOperator.AGGREGATE_IDS,
    FilterOperator.TENANT_ID,
    FilterOperator.OWNER_ID,
    FilterOperator.SPACE_ID,
    FilterOperator.DELETION,
    -> null

    FilterOperator.EQ -> (this as EqualFilter).value == null
    FilterOperator.NE -> (this as NotEqualFilter).value != null

    FilterOperator.NOT_IN,
    FilterOperator.IS_NULL,
    FilterOperator.NOT_EXISTS,
    -> true

    FilterOperator.GT,
    FilterOperator.GTE,
    FilterOperator.LT,
    FilterOperator.LTE,
    FilterOperator.CONTAINS,
    FilterOperator.STARTS_WITH,
    FilterOperator.ENDS_WITH,
    FilterOperator.IN,
    FilterOperator.BETWEEN,
    FilterOperator.CONTAINS_ALL,
    FilterOperator.IS_EMPTY,
    FilterOperator.IS_EMPTY_STRING,
    FilterOperator.IS_NOT_EMPTY_STRING,
    FilterOperator.IS_NOT_NULL,
    FilterOperator.EXISTS,
    FilterOperator.ELEMENT_MATCH,
    FilterOperator.SEARCH,
    FilterOperator.TODAY,
    FilterOperator.BEFORE_TODAY,
    FilterOperator.TOMORROW,
    FilterOperator.THIS_WEEK,
    FilterOperator.NEXT_WEEK,
    FilterOperator.LAST_WEEK,
    FilterOperator.THIS_MONTH,
    FilterOperator.LAST_MONTH,
    FilterOperator.RECENT_DAYS,
    FilterOperator.EARLIER_DAYS,
    FilterOperator.YESTERDAY,
    FilterOperator.NEXT_MONTH,
    FilterOperator.LAST_YEAR,
    FilterOperator.THIS_YEAR,
    FilterOperator.NEXT_YEAR,
    FilterOperator.BEFORE_NOW,
    FilterOperator.AFTER_NOW,
    FilterOperator.EXPRESSION,
    -> false
}

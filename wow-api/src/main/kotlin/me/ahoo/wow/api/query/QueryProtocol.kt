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

internal object QueryProtocol {
    object Polymorphic {
        const val TYPE = "type"
    }

    object FilterExpression {
        const val OP = "op"
        const val OPERANDS = "operands"
        const val PREDICATE = "predicate"

        object Operator {
            const val MATCH_ALL = "MATCH_ALL"
            const val MATCH_NONE = "MATCH_NONE"
            const val ID = "ID"
            const val IDS = "IDS"
            const val AGGREGATE_ID = "AGGREGATE_ID"
            const val AGGREGATE_IDS = "AGGREGATE_IDS"
            const val TENANT_ID = "TENANT_ID"
            const val OWNER_ID = "OWNER_ID"
            const val SPACE_ID = "SPACE_ID"
            const val AND = "AND"
            const val OR = "OR"
            const val NOR = "NOR"
            const val EQ = "EQ"
            const val NE = "NE"
            const val GT = "GT"
            const val GTE = "GTE"
            const val LT = "LT"
            const val LTE = "LTE"
            const val CONTAINS = "CONTAINS"
            const val STARTS_WITH = "STARTS_WITH"
            const val ENDS_WITH = "ENDS_WITH"
            const val IN = "IN"
            const val NOT_IN = "NOT_IN"
            const val BETWEEN = "BETWEEN"
            const val CONTAINS_ALL = "CONTAINS_ALL"
            const val IS_EMPTY = "IS_EMPTY"
            const val IS_NULL = "IS_NULL"
            const val IS_NOT_NULL = "IS_NOT_NULL"
            const val EXISTS = "EXISTS"
            const val NOT_EXISTS = "NOT_EXISTS"
            const val DELETION = "DELETION"
            const val ELEMENT_MATCH = "ELEMENT_MATCH"
            const val SEARCH = "SEARCH"
            const val TODAY = "TODAY"
            const val BEFORE_TODAY = "BEFORE_TODAY"
            const val TOMORROW = "TOMORROW"
            const val THIS_WEEK = "THIS_WEEK"
            const val NEXT_WEEK = "NEXT_WEEK"
            const val LAST_WEEK = "LAST_WEEK"
            const val THIS_MONTH = "THIS_MONTH"
            const val LAST_MONTH = "LAST_MONTH"
            const val RECENT_DAYS = "RECENT_DAYS"
            const val EARLIER_DAYS = "EARLIER_DAYS"
            const val YESTERDAY = "YESTERDAY"
            const val NEXT_MONTH = "NEXT_MONTH"
            const val LAST_YEAR = "LAST_YEAR"
            const val THIS_YEAR = "THIS_YEAR"
            const val NEXT_YEAR = "NEXT_YEAR"
        }
    }

    object QueryEnvelope {
        const val FILTER = "filter"
        const val CONDITION = "condition"
        const val PROJECTION = "projection"
        const val SORT = "sort"
        const val PAGINATION = "pagination"
        const val LIMIT = "limit"
    }

    object Condition {
        const val FIELD = "field"
        const val OPERATOR = "operator"
        const val VALUE = "value"
        const val CHILDREN = "children"
        const val OPTIONS = "options"
    }

    object Projection {
        const val INCLUDE = "include"
        const val EXCLUDE = "exclude"
    }

    object Sort {
        const val FIELD = "field"
        const val DIRECTION = "direction"
    }

    object Pagination {
        const val INDEX = "index"
        const val SIZE = "size"
    }
}

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

enum class Operator {
    AND, OR,
    EMPTY,
    EQ,
    NE,
    GT,
    LT,
    GTE,
    LTE,
    LIKE,
    IN,
    NOT_IN,
    BETWEEN,
    ALL,
    STATS_WITH,
    ELEM_MATCH,
    NULL,
    NOT_NULL
}

data class Condition(
    val field: String,
    val operator: Operator,
    val value: Any = EMPTY_VALUE,
    /**
     * When `operator` is `AND` or `OR`, `children` cannot be empty.
     */
    val children: List<Condition> = emptyList()
) {
    companion object {
        const val EMPTY_VALUE = ""
        val EMPTY = Condition(EMPTY_VALUE, Operator.EMPTY, EMPTY_VALUE)

        fun and(vararg conditions: Condition) = Condition(EMPTY_VALUE, Operator.AND, children = conditions.toList())
        fun and(conditions: List<Condition>) = Condition(EMPTY_VALUE, Operator.AND, children = conditions)
        fun or(vararg conditions: Condition) = Condition(EMPTY_VALUE, Operator.OR, children = conditions.toList())
        fun or(conditions: List<Condition>) = Condition(EMPTY_VALUE, Operator.OR, children = conditions)
        fun empty() = EMPTY
        fun eq(field: String, value: Any) = Condition(field, Operator.EQ, value)
        fun ne(field: String, value: Any) = Condition(field, Operator.NE, value)
        fun gt(field: String, value: Any) = Condition(field, Operator.GT, value)
        fun lt(field: String, value: Any) = Condition(field, Operator.LT, value)
        fun gte(field: String, value: Any) = Condition(field, Operator.GTE, value)
        fun lte(field: String, value: Any) = Condition(field, Operator.LTE, value)
        fun like(field: String, value: Any) = Condition(field, Operator.LIKE, value)
        fun isIn(field: String, value: List<Any>) = Condition(field, Operator.IN, value)
        fun notIn(field: String, value: List<Any>) = Condition(field, Operator.NOT_IN, value)
        fun <V> between(field: String, start: V, end: V) = Condition(field, Operator.BETWEEN, listOf(start, end))
        fun all(field: String, value: List<Any>) = Condition(field, Operator.ALL, value)
        fun startsWith(field: String, value: Any) = Condition(field, Operator.STATS_WITH, value)
        fun elemMatch(field: String, value: Condition) = Condition(field, Operator.ELEM_MATCH, children = listOf(value))
        fun isNull(field: String) = Condition(field, Operator.NULL)
        fun notNull(field: String) = Condition(field, Operator.NOT_NULL)
    }
}

data class Sort(val field: String, val direction: Direction) {
    enum class Direction {
        ASC, DESC
    }
}

data class Pagination(val index: Int, val size: Int) {
    companion object {
        val DEFAULT_PAGINATION = Pagination(1, 10)
        fun offset(index: Int, size: Int) = (index - 1) * size
    }

    fun offset() = offset(index, size)
}

data class Projection(val include: List<String> = emptyList(), val exclude: List<String> = emptyList()) {
    companion object {
        val ALL = Projection()
    }

    fun isEmpty() = include.isEmpty() && exclude.isEmpty()
}

interface IQuery {
    val condition: Condition
    val sort: List<Sort>
    val limit: Int
}

data class Query(
    override val condition: Condition,
    override val sort: List<Sort> = emptyList(),
    override val limit: Int = Int.MAX_VALUE
) : IQuery

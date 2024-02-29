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

package me.ahoo.wow.query

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
        val EMPTY = Condition("", Operator.EMPTY, "")
        val EMPTY_VALUE = Any()
    }
}

data class Sort(val field: String, val direction: Direction) {
    enum class Direction {
        ASC, DESC
    }
}

data class Pagination(val index: Int, val size: Int) {
    companion object {
        const val DEFAULT_PAGE_INDEX = 1
        const val DEFAULT_PAGE_SIZE = 10
        val DEFAULT_PAGINATION = Pagination(DEFAULT_PAGE_INDEX, DEFAULT_PAGE_SIZE)
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
}

data class Query(
    override val condition: Condition,
    override val sort: List<Sort> = emptyList()
) : IQuery

interface IPagedQuery : IQuery {
    val pagination: Pagination
}

data class PagedQuery(
    override val condition: Condition,
    override val sort: List<Sort> = emptyList(),
    override val pagination: Pagination = Pagination.DEFAULT_PAGINATION
) : IPagedQuery

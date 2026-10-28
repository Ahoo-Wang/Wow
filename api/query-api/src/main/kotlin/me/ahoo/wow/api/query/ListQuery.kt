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

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * Data class representing a query for retrieving a list of items with optional filtering, projection, sorting, and limiting.
 *
 * It supports all standard query operations ([Queryable]) and limits how many items it returns.
 *
 * @property filter The filter expression to apply to the query.
 * @property projection The field projection to control which fields are included in the results.
 * @property sort The sorting criteria to order the results.
 * @property limit The most items to return, at least 1; `null`, the default, takes the entry's default list size.
 * Only trusted code that dropped the budget (`trusted().unbounded()`) lists every match with `null`; a stream
 * (`stream`) reads every match in chunks.
 *
 * ```
 * val query = ListQuery(
 *     filter = EqualFilter(QueryField("status"), value),
 *     projection = Projection(listOf(QueryField("name"), QueryField("email"))),
 *     sort = listOf(Sort(QueryField("name"), Direction.ASC)),
 *     limit = 50
 * )
 * ```
 */
class ListQuery @InternalWowApi constructor(
    override val filter: FilterExpression,
    override val projection: Projection = Projection.ALL,
    override val sort: List<Sort> = emptyList(),
    val limit: Int? = null,
) : Queryable<ListQuery> {
    init {
        require(limit == null || limit >= 1) {
            "limit must be at least 1."
        }
        require(sort.size <= Sort.MAX_FIELDS) {
            "sort must contain at most ${Sort.MAX_FIELDS} fields."
        }
    }

    /**
     * Creates a new ListQuery with the specified filter.
     *
     * @param newFilter The new filter to apply.
     * @return A new ListQuery with the updated filter.
     */
    override fun withFilter(newFilter: FilterExpression): ListQuery = ListQuery(newFilter, projection, sort, limit)

    /**
     * Creates a new ListQuery with the specified projection.
     *
     * @param newProjection The new projection to apply.
     * @return A new ListQuery with the updated projection.
     */
    override fun withProjection(newProjection: Projection): ListQuery = ListQuery(filter, newProjection, sort, limit)

    override fun equals(other: Any?): Boolean =
        this === other || other is ListQuery && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ListQuery(filter=$filter, projection=$projection, sort=$sort, limit=$limit)"

    private fun valueFields(): List<Any?> = listOf(filter, projection, sort, limit)

    companion object {
        /** A [ListQuery] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            filter: FilterExpression,
            projection: Projection = Projection.ALL,
            sort: List<Sort> = emptyList(),
            limit: Int? = null,
        ): ListQuery = ListQuery(filter, projection, sort, limit)
    }
}

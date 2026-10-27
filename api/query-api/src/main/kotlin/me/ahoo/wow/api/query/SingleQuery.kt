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
 * Data class representing a query for retrieving a single item with optional filtering, projection, and sorting.
 *
 * It supports all standard query operations ([Queryable]) and expects at most one result, or none.
 *
 * @property filter The filter expression to apply to the query.
 * @property projection The field projection to control which fields are included in the result.
 * @property sort The sorting criteria to order results (useful when multiple matches exist).
 *
 * ```
 * val query = SingleQuery(
 *     filter = EqualFilter(QueryField("id"), value),
 *     projection = Projection(include = listOf(QueryField("name"), QueryField("email"))),
 *     sort = listOf(Sort(QueryField("createdDate"), Direction.DESC))
 * )
 * ```
 */
class SingleQuery @InternalWowApi constructor(
    override val filter: FilterExpression,
    override val projection: Projection = Projection.ALL,
    override val sort: List<Sort> = emptyList()
) : Queryable<SingleQuery> {
    init {
        require(sort.size <= Sort.MAX_FIELDS) {
            "sort must contain at most ${Sort.MAX_FIELDS} fields."
        }
    }

    /**
     * Creates a new SingleQuery with the specified filter.
     *
     * @param newFilter The new filter to apply.
     * @return A new SingleQuery with the updated filter.
     */
    override fun withFilter(newFilter: FilterExpression): SingleQuery = SingleQuery(newFilter, projection, sort)

    /**
     * Creates a new SingleQuery with the specified projection.
     *
     * @param newProjection The new projection to apply.
     * @return A new SingleQuery with the updated projection.
     */
    override fun withProjection(newProjection: Projection): SingleQuery = SingleQuery(filter, newProjection, sort)

    override fun equals(other: Any?): Boolean =
        this === other || other is SingleQuery && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "SingleQuery(filter=$filter, projection=$projection, sort=$sort)"

    private fun valueFields(): List<Any?> = listOf(filter, projection, sort)

    companion object {
        /** A [SingleQuery] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            filter: FilterExpression,
            projection: Projection = Projection.ALL,
            sort: List<Sort> = emptyList(),
        ): SingleQuery = SingleQuery(filter, projection, sort)
    }
}

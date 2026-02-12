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

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Interface for list queries that retrieve multiple items with a limit.
 *
 * This interface extends [Queryable] and adds a limit parameter to control
 * the maximum number of items returned by the query.
 */
interface IListQuery : Queryable<IListQuery> {
    /**
     * The maximum number of items to return in the query result.
     * This limits the size of the result set.
     */
    @get:Schema(defaultValue = "10")
    val limit: Int
}

/**
 * Data class representing a query for retrieving a list of items with optional filtering, projection, sorting, and limiting.
 *
 * This class implements [IListQuery] and provides a concrete implementation for list-based queries.
 * It supports all standard query operations including conditions, projections, sorting, and result limiting.
 *
 * @property condition The filtering condition to apply to the query.
 * @property projection The field projection to control which fields are included in the results.
 * @property sort The sorting criteria to order the results.
 * @property limit The maximum number of items to return. Defaults to the standard pagination size.
 *
 * @sample
 * ```
 * val query = ListQuery(
 *     condition = Condition.eq("status", "active"),
 *     projection = Projection(listOf("name", "email")),
 *     sort = listOf(Sort("name", Direction.ASC)),
 *     limit = 50
 * )
 * ```
 */
data class ListQuery(
    override val condition: Condition,
    override val projection: Projection = Projection.ALL,
    override val sort: List<Sort> = emptyList(),
    override val limit: Int = Pagination.DEFAULT.size
) : IListQuery {
    /**
     * Creates a new ListQuery with the specified condition.
     *
     * @param newCondition The new condition to apply.
     * @return A new ListQuery with the updated condition.
     */
    override fun withCondition(newCondition: Condition): IListQuery = copy(condition = newCondition)

    /**
     * Creates a new ListQuery with the specified projection.
     *
     * @param newProjection The new projection to apply.
     * @return A new ListQuery with the updated projection.
     */
    override fun withProjection(newProjection: Projection): IListQuery = copy(projection = newProjection)
}

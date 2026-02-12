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
 * Interface for paginated queries that retrieve items in pages.
 *
 * This interface extends [Queryable] and adds pagination support to control
 * which page of results to retrieve and how many items per page.
 */
interface IPagedQuery : Queryable<IPagedQuery> {
    /**
     * The pagination settings for this query.
     */
    val pagination: Pagination
}

/**
 * Data class representing a paginated query with filtering, projection, sorting, and pagination.
 *
 * This class implements [IPagedQuery] and provides a concrete implementation for
 * queries that need to retrieve results in pages with support for all standard query operations.
 *
 * @property condition The filtering condition to apply to the query.
 * @property projection The field projection to control which fields are included in the results.
 * @property sort The sorting criteria to order the results.
 * @property pagination The pagination settings to control which page and how many items to return.
 *
 * @sample
 * ```
 * val query = PagedQuery(
 *     condition = Condition.eq("status", "active"),
 *     projection = Projection(listOf("name", "email")),
 *     sort = listOf(Sort("name", Direction.ASC)),
 *     pagination = Pagination(index = 2, size = 20)
 * )
 * ```
 */
data class PagedQuery(
    override val condition: Condition,
    override val projection: Projection = Projection.ALL,
    override val sort: List<Sort> = emptyList(),
    override val pagination: Pagination = Pagination.DEFAULT
) : IPagedQuery {
    /**
     * Creates a new PagedQuery with the specified condition.
     *
     * @param newCondition The new condition to apply.
     * @return A new PagedQuery with the updated condition.
     */
    override fun withCondition(newCondition: Condition): IPagedQuery = copy(condition = newCondition)

    /**
     * Creates a new PagedQuery with the specified projection.
     *
     * @param newProjection The new projection to apply.
     * @return A new PagedQuery with the updated projection.
     */
    override fun withProjection(newProjection: Projection): IPagedQuery = copy(projection = newProjection)
}

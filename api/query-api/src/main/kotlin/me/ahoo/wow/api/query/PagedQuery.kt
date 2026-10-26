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

    /**
     * How the total of [PagedList] is counted.
     */
    val total: TotalMode
}

/**
 * Data class representing a paginated query with filtering, projection, sorting, and pagination.
 *
 * This class implements [IPagedQuery] and provides a concrete implementation for
 * queries that need to retrieve results in pages with support for all standard query operations.
 *
 * @property filter The filter expression to apply to the query.
 * @property projection The field projection to control which fields are included in the results.
 * @property sort The sorting criteria to order the results.
 * @property pagination The pagination settings to control which page and how many items to return.
 * @property total How the total is counted; [TotalMode.BOUNDED], the default, stops at the budget's count limit.
 *
 * ```
 * val query = PagedQuery(
 *     filter = EqualFilter(QueryField("status"), value),
 *     projection = Projection(listOf(QueryField("name"), QueryField("email"))),
 *     sort = listOf(Sort(QueryField("name"), Direction.ASC)),
 *     pagination = Pagination(index = 2, size = 20)
 * )
 * ```
 */
class PagedQuery @InternalWowApi constructor(
    override val filter: FilterExpression,
    override val projection: Projection = Projection.ALL,
    override val sort: List<Sort> = emptyList(),
    override val pagination: Pagination = Pagination.DEFAULT,
    override val total: TotalMode = TotalMode.BOUNDED,
) : IPagedQuery {
    /**
     * Creates a new PagedQuery with the specified filter.
     *
     * @param newFilter The new filter to apply.
     * @return A new PagedQuery with the updated filter.
     */
    override fun withFilter(
        newFilter: FilterExpression
    ): IPagedQuery = PagedQuery(newFilter, projection, sort, pagination, total)

    /**
     * Creates a new PagedQuery with the specified projection.
     *
     * @param newProjection The new projection to apply.
     * @return A new PagedQuery with the updated projection.
     */
    override fun withProjection(
        newProjection: Projection
    ): IPagedQuery = PagedQuery(filter, newProjection, sort, pagination, total)

    override fun equals(other: Any?): Boolean =
        this === other || other is PagedQuery && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "PagedQuery(filter=$filter, projection=$projection, sort=$sort, pagination=$pagination, total=$total)"

    private fun valueFields(): List<Any?> = listOf(filter, projection, sort, pagination, total)

    companion object {
        /** A [PagedQuery] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            filter: FilterExpression,
            projection: Projection = Projection.ALL,
            sort: List<Sort> = emptyList(),
            pagination: Pagination = Pagination.DEFAULT,
            total: TotalMode = TotalMode.BOUNDED,
        ): PagedQuery = PagedQuery(filter, projection, sort, pagination, total)
    }
}

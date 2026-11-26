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
 * A paged query: one page of the records its filter matches, in its order, with its projection.
 *
 * It supports all standard query operations ([Queryable]) and retrieves one page of the results.
 *
 * @property filter The filter expression to apply to the query; [MatchAllFilter], every record, by default.
 * @property projection The field projection to control which fields are included in the results.
 * @param sort The order of the results; the query keeps a copy.
 * @property pagination The pagination settings to control which page and how many items to return.
 * @property total How the total is counted; [TotalMode.BOUNDED], the default, stops at the budget's count limit.
 *
 * ```
 * val query = PagedQuery.of(
 *     filter = EqualFilter.of(QueryField("status"), value),
 *     projection = Projection.of(listOf(QueryField("name"), QueryField("email"))),
 *     sort = listOf(Sort.of(QueryField("name"), Sort.Direction.ASC)),
 *     pagination = Pagination.of(index = 2, size = 20),
 * )
 * ```
 */
class PagedQuery @InternalWowApi constructor(
    override val filter: FilterExpression = MatchAllFilter,
    override val projection: Projection = Projection.ALL,
    sort: List<Sort> = emptyList(),
    val pagination: Pagination = Pagination.DEFAULT,
    val total: TotalMode = TotalMode.BOUNDED,
) : Queryable<PagedQuery> {
    /**
     * The sorting criteria to order the results; a copy, so a list its caller changes later changes neither this query
     * nor its hash.
     */
    override val sort: List<Sort> = sort.copied()

    init {
        require(sort.size <= Sort.MAX_FIELDS) {
            "sort must contain at most ${Sort.MAX_FIELDS} fields."
        }
    }

    /**
     * Creates a new PagedQuery with the specified filter.
     *
     * @param newFilter The new filter to apply.
     * @return A new PagedQuery with the updated filter.
     */
    override fun withFilter(
        newFilter: FilterExpression
    ): PagedQuery = PagedQuery(newFilter, projection, sort, pagination, total)

    /**
     * Creates a new PagedQuery with the specified projection.
     *
     * @param newProjection The new projection to apply.
     * @return A new PagedQuery with the updated projection.
     */
    override fun withProjection(
        newProjection: Projection
    ): PagedQuery = PagedQuery(filter, newProjection, sort, pagination, total)

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
            filter: FilterExpression = MatchAllFilter,
            projection: Projection = Projection.ALL,
            sort: List<Sort> = emptyList(),
            pagination: Pagination = Pagination.DEFAULT,
            total: TotalMode = TotalMode.BOUNDED,
        ): PagedQuery = PagedQuery(filter, projection, sort, pagination, total)
    }
}

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

package me.ahoo.wow.api.query.dsl

import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TotalMode

/**
 * The parts every query kind but `count` and `aggregate` has: its [filter] (every record by default), [projection]
 * and [sort].
 */
@QueryDslMarker
abstract class QueryableDsl<Q> {
    protected var projection: Projection = Projection.ALL
    protected var filter: FilterExpression = MatchAllFilter
    protected var sort: List<Sort> = emptyList()

    fun projection(projection: Projection) {
        this.projection = projection
    }

    fun projection(block: ProjectionDsl.() -> Unit) = projection(ProjectionDsl().apply(block).build())

    fun filter(filter: FilterExpression) {
        this.filter = filter
    }

    fun filter(block: FilterDsl.() -> Unit) = filter(me.ahoo.wow.api.query.dsl.filter(block))

    fun sort(sort: List<Sort>) {
        this.sort = sort
    }

    fun sort(block: SortDsl.() -> Unit) = sort(SortDsl().apply(block).build())

    abstract fun build(): Q
}

/** Builds a `list` query: up to [limit] records, `0` (the default) for the entry's default list size. */
@QueryDslMarker
class ListQueryDsl : QueryableDsl<ListQuery>() {
    private var limit: Int = 0

    fun limit(limit: Int) {
        this.limit = limit
    }

    override fun build(): ListQuery = ListQuery(filter, projection, sort, limit)
}

/** Builds a `paged` query: the page of [pagination], with its total counted as [total] says. */
@QueryDslMarker
class PagedQueryDsl : QueryableDsl<PagedQuery>() {
    private var pagination: Pagination = Pagination.DEFAULT
    private var total: TotalMode = TotalMode.BOUNDED

    fun pagination(pagination: Pagination) {
        this.pagination = pagination
    }

    fun pagination(block: PaginationDsl.() -> Unit) = pagination(PaginationDsl().apply(block).build())

    /** How the page's total is counted: [TotalMode.BOUNDED] (the default), [TotalMode.EXACT] or [TotalMode.NONE]. */
    fun total(total: TotalMode) {
        this.total = total
    }

    override fun build(): PagedQuery = PagedQuery(filter, projection, sort, pagination, total)
}

/** Builds a `single` query: the first record by [sort], if any matches. */
@QueryDslMarker
class SingleQueryDsl : QueryableDsl<SingleQuery>() {
    override fun build(): SingleQuery = SingleQuery(filter, projection, sort)
}

/** Builds a `cursor` query: [size] records after [cursor], the `nextCursor` of the previous page (none at first). */
@QueryDslMarker
class CursorQueryDsl : QueryableDsl<CursorQuery>() {
    private var size: Int = CursorQuery.DEFAULT_SIZE
    private var cursor: String? = null

    fun size(value: Int) {
        size = value
    }

    fun cursor(value: String?) {
        cursor = value
    }

    override fun build(): CursorQuery = CursorQuery(filter, projection, sort, size, cursor)
}

/** Builds a `count` query: the matches of [filter], counted up to [limit] (the budget's limit by default). */
@QueryDslMarker
class CountQueryDsl {
    private var filter: FilterExpression = MatchAllFilter
    private var limit: Int? = null

    fun filter(filter: FilterExpression) {
        this.filter = filter
    }

    fun filter(block: FilterDsl.() -> Unit) = filter(me.ahoo.wow.api.query.dsl.filter(block))

    fun limit(limit: Int?) {
        this.limit = limit
    }

    fun build(): CountQuery = CountQuery(filter, limit)
}

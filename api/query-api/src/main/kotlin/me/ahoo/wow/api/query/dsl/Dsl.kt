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

@file:JvmName("QueryDsl")

package me.ahoo.wow.api.query.dsl

import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort

/*
 * The Kotlin DSL of the query protocol: each function builds the query of one `kind` of the query route, the same
 * value its JSON reads as, so a query built here and sent over HTTP is the query the route runs.
 *
 * ```kotlin
 * val query = pagedQuery {
 *     filter {
 *         "state.categoryId" eq "books"
 *         "state.price" between 10..50
 *     }
 *     sort { "state.price".asc() }
 *     pagination { size(20) }
 * }
 * ```
 */

/** A `single` query: the first match by its sort. */
fun singleQuery(block: SingleQueryDsl.() -> Unit): SingleQuery = SingleQueryDsl().apply(block).build()

/** A `list` query: up to its limit of matches. */
fun listQuery(block: ListQueryDsl.() -> Unit): ListQuery = ListQueryDsl().apply(block).build()

/** A `paged` query: one page of the matches, with their total. */
fun pagedQuery(block: PagedQueryDsl.() -> Unit): PagedQuery = PagedQueryDsl().apply(block).build()

/** A `cursor` query: the matches after a cursor, with the cursor of the next page. */
fun cursorQuery(block: CursorQueryDsl.() -> Unit): CursorQuery = CursorQueryDsl().apply(block).build()

/** A `count` query: the number of matches, bounded. */
fun countQuery(block: CountQueryDsl.() -> Unit): CountQuery = CountQueryDsl().apply(block).build()

/** An `aggregate` query: grouped metrics of the matches. Experimental, as the aggregation protocol is. */
@ExperimentalWowApi
fun aggregation(block: AggregationQueryDsl.() -> Unit): AggregationQuery = AggregationQueryDsl().apply(block).build()

fun projection(block: ProjectionDsl.() -> Unit): Projection = ProjectionDsl().apply(block).build()

fun pagination(block: PaginationDsl.() -> Unit): Pagination = PaginationDsl().apply(block).build()

fun sort(block: SortDsl.() -> Unit): List<Sort> = SortDsl().apply(block).build()

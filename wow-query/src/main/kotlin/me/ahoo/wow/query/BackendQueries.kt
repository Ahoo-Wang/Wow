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

@file:JvmName("BackendQueries")

package me.ahoo.wow.query

import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.aggregation.AggregationPlan
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.SupportMode
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

/*
 * Every query shape, derived by the core from the four backend primitives (design §5.5). The gateway calls these
 * after admission; low-level callers (backend conformance tests, tools) call them with a QueryAdmission result.
 */

/** The first record of [query]: `page(Offset(0, 1, withTotal = false))`. */
fun QueryBackend.single(query: AdmittedQuery<ISingleQuery>): Mono<ObjectNode> =
    page(query, PageWindow.Offset(0, 1, withTotal = false)).flatMap { Mono.justOrEmpty(it.rows.firstOrNull()) }

/** The records of [query], streamed: at most its limit, or all of them when the limit is `0`. */
fun QueryBackend.list(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> = Flux.defer {
    if (query.query.limit == 0 && query.schema.storage.paging.unboundedStream == SupportMode.NONE) {
        throw QuerySchemaValidationException("Storage does not support listing without a limit.")
    }
    stream(query)
}

/** One page of [query] with the total: `page(Offset(offset, size, withTotal = true))`. */
fun QueryBackend.paged(query: AdmittedQuery<IPagedQuery>): Mono<PagedList<ObjectNode>> {
    val pagination = query.query.pagination
    return page(query, PageWindow.Offset(pagination.offset(), pagination.size, withTotal = true)).map { page ->
        PagedList(checkNotNull(page.total) { "Backend page must carry the total it was asked for." }, page.rows)
    }
}

/**
 * One cursor page of [query]: `page(Keyset)` for one row more than the page, the look-ahead that decides whether a
 * next page exists. The next token encodes the native position of the page's last row, never a value from the
 * rows, so masking cannot leak into it. A token that does not decode for this model and effective sort is rejected
 * as `Invalid cursor.` before any I/O.
 */
fun QueryBackend.cursor(query: AdmittedQuery<ICursorQuery>): Mono<CursorPage<ObjectNode>> = Mono.defer {
    if (query.schema.storage.paging.keyset == SupportMode.NONE) {
        throw QuerySchemaValidationException("Storage does not support cursor queries.")
    }
    val cursor = query.query
    val sort = cursor.sort.map { Sort(query.field(it.field).logicalField, it.direction) }
    val after = cursor.cursor?.let { CursorTokens.decode(it, namedAggregate, query.schema, sort, cursorPositions) }
    page(query, PageWindow.Keyset(after, cursor.size + 1)).map { page ->
        val rows = page.rows.take(cursor.size)
        val next = if (page.rows.size > cursor.size) {
            val positions = checkNotNull(page.positions) { "Backend keyset page must carry each row's position." }
            CursorTokens.encode(positions[cursor.size - 1], namedAggregate, query.schema, sort, cursorPositions)
        } else {
            null
        }
        CursorPage(rows, next)
    }
}

/**
 * The groups of [query]. The core plans the residual operators the storage declares
 * [RESIDUAL][SupportMode.RESIDUAL]: it removes them from the query it sends down, asks for every group when an
 * operator needs them all, and applies dense fill, HAVING and top-N (or the limit) to the rows that come back.
 * When it reads every group, [budget]'s [QueryBudget.maxResidualGroups] bounds how many it accepts.
 */
@JvmOverloads
fun QueryBackend.aggregate(
    query: AdmittedQuery<AggregationQuery>,
    budget: QueryBudget? = null,
): Flux<ObjectNode> = Flux.defer {
    val plan = AggregationPlan.of(query.query, query.schema.storage.aggregation)
    var rows = aggregate(if (plan.adjusted) query.withQuery(plan.native) else query, plan.window)
    val maxGroups = budget?.maxResidualGroups ?: 0
    if (plan.window == GroupWindow.All && maxGroups > 0) {
        rows = rows.index().map { indexed ->
            require(indexed.t1 < maxGroups) {
                "${budget!!.label} aggregation reads more than [$maxGroups] groups to compute HAVING or a metric " +
                    "sort in the query service; narrow the filter."
            }
            indexed.t2
        }
    }
    plan.dense?.let { rows = it.fill(rows) }
    if (plan.having) rows = rows.filter(plan::applyHaving)
    if (plan.topN) {
        rows.collect(plan::topRows) { top, row -> top.add(row) }.flatMapIterable { it.result() }
    } else {
        rows.take(plan.rowsLimit().toLong())
    }
}

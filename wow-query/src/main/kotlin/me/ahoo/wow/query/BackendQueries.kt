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

import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.aggregation.AggregationPlan
import me.ahoo.wow.query.aggregation.EmptyAggregationValues
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.serialization.JsonSerializer
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/*
 * Every query shape, derived by the core from the four backend primitives (design §5.5). The gateway calls these
 * after admission; low-level callers (backend conformance tests, tools) call them with a QueryAdmission result.
 * Every row a backend returns is checked here to be a standard JSON tree with finite numbers, so no backend repeats
 * the check.
 */

/** The first record of [query]: `page(Offset(0, 1, withTotal = false))`. */
fun QueryBackend.single(query: AdmittedQuery<ISingleQuery>): Mono<ObjectNode> =
    page(query, PageWindow.Offset(0, 1, withTotal = false)).flatMap { page ->
        Mono.justOrEmpty(page.rows.firstOrNull()?.requireStandardRecord())
    }

/** The records of [query], streamed: at most its limit, or all of them when the limit is `0`. */
fun QueryBackend.list(query: AdmittedQuery<IListQuery>): Flux<ObjectNode> = Flux.defer {
    if (query.query.limit == 0 && query.schema.storage.paging.unboundedStream == SupportMode.NONE) {
        throw QuerySchemaValidationException("Storage does not support listing without a limit.")
    }
    stream(query).map(ObjectNode::requireStandardRecord)
}

/** One page of [query] with the total: `page(Offset(offset, size, withTotal = true))`. */
fun QueryBackend.paged(query: AdmittedQuery<IPagedQuery>): Mono<PagedList<ObjectNode>> {
    val pagination = query.query.pagination
    return page(query, PageWindow.Offset(pagination.offset(), pagination.size, withTotal = true)).map { page ->
        val total = checkNotNull(page.total) { "Backend page must carry the total it was asked for." }
        PagedList(total, page.rows.map(ObjectNode::requireStandardRecord))
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
        val rows = page.rows.take(cursor.size).map(ObjectNode::requireStandardRecord)
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
 * An aggregation without groups always has its summary row: when the backend emits none, because no record
 * matched, the core emits the empty summary ([EmptyAggregationValues]).
 * When it reads every group, [budget]'s [QueryBudget.maxResidualGroups] bounds how many it processes, dense fill
 * rows included: HAVING can discard every fill row, so without counting them a sparse fine-grained dense histogram
 * would generate rows until the idle timeout.
 */
@JvmOverloads
fun QueryBackend.aggregate(
    query: AdmittedQuery<AggregationQuery>,
    budget: QueryBudget? = null,
): Flux<ObjectNode> = Flux.defer {
    val plan = AggregationPlan.of(query.query, query.schema.storage.aggregation)
    val aggregation = query.query
    val metrics = aggregation.metrics.mapTo(hashSetOf(), AggregationMetric::alias)
    var rows = aggregate(if (plan.adjusted) query.withQuery(plan.native) else query, plan.window)
        .map { row -> row.requireStandardAggregation(metrics) }
    if (aggregation.groupBy.isEmpty()) {
        rows = rows.switchIfEmpty(Flux.defer { Flux.just(emptySummary(aggregation.metrics)) })
    }
    plan.dense?.let { rows = it.fill(rows) }
    val maxGroups = budget?.maxResidualGroups ?: 0
    if (plan.window == GroupWindow.All && maxGroups > 0) {
        rows = rows.index().map { indexed ->
            require(indexed.t1 < maxGroups) {
                "${budget!!.label} aggregation processes more than [$maxGroups] groups, dense fill included, to " +
                    "compute HAVING or a metric sort in the query service; narrow the filter or the period."
            }
            indexed.t2
        }
    }
    if (plan.having) rows = rows.filter(plan::applyHaving)
    if (plan.topN) {
        rows.collect(plan::topRows) { top, row -> top.add(row) }.flatMapIterable { it.result() }
    } else {
        rows.take(plan.rowsLimit().toLong())
    }
}

private fun emptySummary(metrics: List<AggregationMetric>): ObjectNode =
    JsonSerializer.valueToTree(EmptyAggregationValues.values(metrics))

private fun ObjectNode.requireStandardRecord(): ObjectNode = requireStandardJson { path -> "Query result [$path]" }

private fun ObjectNode.requireStandardAggregation(metrics: Set<String>): ObjectNode = requireStandardJson { path ->
    val alias = path.substringBefore('.')
    if (alias in metrics) "Aggregation metric [$path]" else "Aggregation group [$path]"
}

/**
 * Checks that this backend row holds only standard JSON values: objects, arrays, strings, booleans, nulls, integers
 * and finite decimals. Drivers convert their own values; a `NaN`, an infinity, a POJO or binary node that slips
 * through fails the query here instead of reaching the wire. [subject] names the offending path in the error.
 */
internal fun ObjectNode.requireStandardJson(subject: (String) -> String): ObjectNode {
    requireStandardJson("", subject)
    return this
}

private fun JsonNode.requireStandardJson(path: String, subject: (String) -> String) {
    when {
        isObject -> properties().forEach { (name, value) ->
            value.requireStandardJson(if (path.isEmpty()) name else "$path.$name", subject)
        }
        isArray -> forEach { it.requireStandardJson(path, subject) }
        isString || isBoolean || isNull || isIntegralNumber || isBigDecimal -> Unit
        isFloat || isDouble -> require(doubleValue().isFinite()) { "${subject(path)} must be finite." }
        else -> throw IllegalArgumentException("${subject(path)} must be a standard JSON value.")
    }
}

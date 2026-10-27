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

package me.ahoo.wow.query.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.aggregation.AggregationPlan
import me.ahoo.wow.query.aggregation.EmptyAggregationValues
import me.ahoo.wow.query.aggregation.PreviousStep
import me.ahoo.wow.query.error.QueryError
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.error.QueryStorageUnavailableException
import me.ahoo.wow.query.error.QueryTimeoutException
import me.ahoo.wow.query.error.checkExecution
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

/*
 * Every query shape, derived by the core from the four backend primitives. The gateway calls these
 * after admission; low-level callers (backend conformance tests, tools) call them with a QueryAdmission result.
 * Every row a backend returns is checked here to be a standard JSON tree with finite numbers, so no backend repeats
 * the check; a row that fails it is a server fault ([QueryExecutionException]). What the storage cannot run was
 * rejected at admission, so a storage error that is not already a query error is a server fault too ([storageFault]).
 * Every call runs within the admitted query's timeout: past it the query fails with
 * [QueryTimeoutException].
 */

/**
 * [error] of a backend primitive as the caller sees it: a query error (a [QueryViolation] rejection, a
 * [QueryExecutionException]) as is, a storage that is unavailable ([StorageUnavailableException]) as a
 * [QueryStorageUnavailableException], anything else as `Query storage failed.`; [error] is kept as the server-side
 * cause, since driver text can carry query values.
 */
internal fun storageFault(error: Throwable): Throwable = when (error) {
    is QueryError -> error
    is StorageUnavailableException -> QueryStorageUnavailableException(error)
    else -> QueryExecutionException("Query storage failed.", error)
}

/**
 * Runs [block] within [timeout] (none when `null`), a storage failure reported as [storageFault]. Only this timeout
 * becomes a [QueryTimeoutException]: the caller's own cancellation, an enclosing timeout included, stays one.
 */
internal suspend fun <T> executing(timeout: Duration?, block: suspend () -> T): T {
    if (timeout == null) return guarded(block)
    val done = withTimeoutOrNull(timeout.toMillis()) { Done(guarded(block)) } ?: throw QueryTimeoutException(timeout)
    return done.value
}

/** A result that may itself be `null`, told apart from the `null` of a timeout. */
private class Done<T>(val value: T)

@Suppress("TooGenericExceptionCaught")
private suspend fun <T> guarded(block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    throw storageFault(error)
}

/**
 * [this] flow within [timeout] as a whole (none when `null`), a storage failure reported as [storageFault]. The timeout
 * bounds the stream from its start to its last row, the time the consumer takes included.
 */
@Suppress("ThrowsCount")
internal fun <T> Flow<T>.executing(timeout: Duration?): Flow<T> {
    val guarded = catch { error -> if (error is CancellationException) throw error else throw storageFault(error) }
    if (timeout == null) return guarded
    return flow {
        withTimeoutOrNull(timeout.toMillis()) { emitAll(guarded) } ?: throw QueryTimeoutException(timeout)
    }
}

/**
 * The number of records matching [query], bounded by its limit: [QueryBackend.count], a storage
 * failure reported as [storageFault], a negative answer as a server fault. A count above the limit is the limit,
 * not exact: "at least `limit`".
 */
suspend fun QueryBackend.countBounded(query: AdmittedQuery<CountQuery>): CountResult {
    val count = executing(query.timeout) { count(query) }
    checkExecution(count >= 0) { "Backend count must be greater than or equal to 0." }
    return bounded(count, query.query.limit)
}

private fun bounded(count: Long, limit: Int?): CountResult =
    if (limit != null && count > limit) CountResult(limit.toLong(), exact = false) else CountResult(count, exact = true)

/** The first record of [query]: `page(Offset(0, 1, withTotal = false))`. */
suspend fun QueryBackend.single(query: AdmittedQuery<SingleQuery>): ObjectNode? {
    val page = executing(query.timeout) { page(query, PageWindow.Offset(0, 1, withTotal = false)) }
    return page.rows.firstOrNull()?.requireStandardRecord()
}

/** The records of [query], streamed: at most its limit, or all of them when the limit is `0`. */
fun QueryBackend.list(query: AdmittedQuery<ListQuery>): Flow<ObjectNode> =
    flow { emitAll(stream(query)) }.executing(query.timeout).map(ObjectNode::requireStandardRecord)

/**
 * One page of [query] with its total as [PagedQuery.total] asks: `page(Offset(offset, size))` with the
 * exact total for [TotalMode.EXACT], the total bounded by [totalLimit] for [TotalMode.BOUNDED] (exact when
 * [totalLimit] is `null`), and none for [TotalMode.NONE].
 */
suspend fun QueryBackend.paged(query: AdmittedQuery<PagedQuery>, totalLimit: Int? = null): PagedList<ObjectNode> =
    paged(query, totalLimit, known = null)

/**
 * One page of [query] as [paged] reads it, except that a [known] total, the count cache's, is the
 * page's total without counting again.
 */
internal suspend fun QueryBackend.paged(
    query: AdmittedQuery<PagedQuery>,
    totalLimit: Int?,
    known: CountResult?,
): PagedList<ObjectNode> {
    val pagination = query.query.pagination
    val mode = query.query.total
    val bound = if (mode == TotalMode.BOUNDED) totalLimit else null
    val counts = mode != TotalMode.NONE && known == null
    val window = PageWindow.Offset(pagination.offset(), pagination.size, counts, if (counts) bound else null)
    val page = executing(query.timeout) { page(query, window) }
    val rows = page.rows.map(ObjectNode::requireStandardRecord)
    if (mode == TotalMode.NONE) {
        return PagedList(null, rows)
    }
    if (known != null) {
        return PagedList(known.count, rows, known.exact)
    }
    val total = page.total ?: throw QueryExecutionException("Backend page must carry the total it was asked for.")
    val counted = bounded(total, bound)
    return PagedList(counted.count, rows, counted.exact)
}

/**
 * One cursor page of [query]: `page(Keyset)` for one row more than the page, the look-ahead that decides whether a
 * next page exists. The next token encodes the native position of the page's last row, never a value from the
 * rows, so no value of a row can leak into it. A token that does not decode for this model and effective sort is
 * rejected as `Invalid cursor.` before any I/O.
 */
suspend fun QueryBackend.cursor(query: AdmittedQuery<CursorQuery>): CursorPage<ObjectNode> {
    val cursor = query.query
    val sort = cursor.sort.map { Sort(query.field(it.field).logicalField, it.direction) }
    val after = cursor.cursor?.let { CursorTokens.decode(it, aggregateType, query.schema, sort, cursorPositions) }
    val page = executing(query.timeout) { page(query, PageWindow.Keyset(after, cursor.size + 1)) }
    val rows = page.rows.take(cursor.size).map(ObjectNode::requireStandardRecord)
    val next = if (page.rows.size > cursor.size) {
        val positions = page.positions
            ?: throw QueryExecutionException("Backend keyset page must carry each row's position.")
        CursorTokens.encode(positions[cursor.size - 1], aggregateType, query.schema, sort, cursorPositions)
    } else {
        null
    }
    return CursorPage(rows, next)
}

/**
 * The groups of [query]. The core plans the residual operators the storage declares
 * [RESIDUAL][me.ahoo.wow.query.schema.SupportMode.RESIDUAL]: it removes them from the query it sends down, asks for
 * every group when an operator needs them all, and applies dense fill, windows, HAVING and top-N (or the limit) to
 * the rows that come back.
 * An aggregation without groups always has its summary row: when the backend emits none, because no record
 * matched, the core emits the empty summary ([EmptyAggregationValues]). Such a query has no HAVING (the protocol
 * requires a group for one, `AggregationQuery` rejects it at construction), so a storage's native HAVING can never be
 * what removed a summary row; with groups, rows a HAVING removes are not replaced.
 * When it reads every group, [budget]'s [QueryBudget.maxResidualGroups] bounds how many it processes, dense fill
 * rows included: HAVING can discard every fill row, so without counting them a sparse fine-grained dense histogram
 * would generate rows until the timeout.
 */
fun QueryBackend.aggregate(
    query: AdmittedQuery<AggregationQuery>,
    budget: QueryBudget? = null,
): Flow<ObjectNode> = flow {
    val plan = AggregationPlan.of(query.query, query.schema.storage.aggregation)
    val aggregation = query.query
    var rows = groups(if (plan.adjusted) query.withQuery(plan.native) else query, plan, budget)
    if (aggregation.groupBy.isEmpty()) {
        rows = rows.orEmptySummary(aggregation.metrics)
    }
    plan.windows?.let { windows ->
        val grouped = rows
        rows = flow {
            val current = grouped.toList()
            val earlier = earlierGroups(query, plan, windows.previousSteps, current, budget)
            windows.apply(current, earlier).forEach { emit(it) }
        }
    }
    if (plan.having) rows = rows.filter(plan::applyHaving)
    if (plan.topN) {
        val top = plan.topRows()
        rows.collect(top::add)
        top.result().forEach { emit(it) }
    } else {
        emitAll(rows.take(plan.rowsLimit()))
    }
}.executing(query.timeout)

private fun Flow<ObjectNode>.orEmptySummary(metrics: List<AggregationMetric>): Flow<ObjectNode> {
    val rows = this
    return flow {
        var empty = true
        rows.collect {
            empty = false
            emit(it)
        }
        if (empty) emit(emptySummary(metrics))
    }
}

/**
 * The groups [sent] reads, checked, dense filled and, when the plan reads every group, bounded by [budget]'s
 * [QueryBudget.maxResidualGroups], fill rows included.
 */
@Suppress("ThrowsCount")
private fun QueryBackend.groups(
    sent: AdmittedQuery<AggregationQuery>,
    plan: AggregationPlan,
    budget: QueryBudget?,
): Flow<ObjectNode> {
    val metrics = plan.query.metrics.mapTo(hashSetOf(), AggregationMetric::alias)
    var rows = flow { emitAll(aggregate(sent, plan.window)) }
        .catch { error -> if (error is CancellationException) throw error else throw storageFault(error) }
        .map { row -> row.requireStandardAggregation(metrics) }
    plan.dense?.let { rows = it.fill(rows) }
    val maxGroups = budget?.maxResidualGroups ?: 0
    if (plan.window == GroupWindow.All && maxGroups > 0) {
        val counted = rows
        rows = flow {
            var index = 0L
            counted.collect { row ->
                if (index++ >= maxGroups) {
                    throw QueryViolation.ResidualGroupsExceeded(checkNotNull(budget).label, maxGroups).rejection()
                }
                emit(row)
            }
        }
    }
    return rows
}

/**
 * The groups each PREVIOUS step reads: the query sent down with its range moved back by the step, or [current]
 * when the filter has no range to move. Admission proved every range movable.
 */
private suspend fun QueryBackend.earlierGroups(
    query: AdmittedQuery<AggregationQuery>,
    plan: AggregationPlan,
    steps: List<PreviousStep>,
    current: List<ObjectNode>,
    budget: QueryBudget?,
): Map<PreviousStep, List<ObjectNode>> = steps.associateWith { step ->
    val filter = plan.native.filter
    val shifted = checkNotNull(step.shift(filter, query.field(step.field).temporal))
    if (shifted == filter) {
        current
    } else {
        groups(query.withQuery(plan.native.copy(filter = shifted)), plan, budget).toList()
    }
}

private fun emptySummary(metrics: List<AggregationMetric>): ObjectNode =
    WowJson.mapper.valueToTree(EmptyAggregationValues.values(metrics))

internal fun ObjectNode.requireStandardRecord(): ObjectNode = requireStandardJson { path -> "Query result [$path]" }

private fun ObjectNode.requireStandardAggregation(metrics: Set<String>): ObjectNode = requireStandardJson { path ->
    val alias = path.substringBefore('.')
    if (alias in metrics) "Aggregation metric [$path]" else "Aggregation group [$path]"
}

/**
 * Checks that this backend row holds only standard JSON values: objects, arrays, strings, booleans, nulls, integers
 * and finite decimals. Drivers convert their own values; a `NaN`, an infinity, a POJO or binary node that slips
 * through fails the query here, as a server fault, instead of reaching the wire. [subject] names the offending path
 * in the error. The path is built only when a value fails: a valid row costs one walk and no strings.
 */
internal fun ObjectNode.requireStandardJson(subject: (String) -> String): ObjectNode {
    requireStandardJson(ArrayList(), subject)
    return this
}

/** [path] holds the property names from the row to this node; arrays add none. */
private fun JsonNode.requireStandardJson(path: ArrayList<String>, subject: (String) -> String) {
    when {
        isObject -> properties().forEach { (name, value) ->
            path.add(name)
            value.requireStandardJson(path, subject)
            path.removeAt(path.lastIndex)
        }
        isArray -> forEach { it.requireStandardJson(path, subject) }
        isString || isBoolean || isNull || isIntegralNumber || isBigDecimal -> Unit
        isFloat || isDouble -> checkExecution(doubleValue().isFinite()) { "${subject(path.render())} must be finite." }
        else -> throw QueryExecutionException("${subject(path.render())} must be a standard JSON value.")
    }
}

/** Joins the names as the per-node concatenation did: a name after an empty prefix starts the path again. */
private fun List<String>.render(): String =
    fold("") { prefix, name -> if (prefix.isEmpty()) name else "$prefix.$name" }

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

@file:OptIn(me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class)

package me.ahoo.wow.elasticsearch.query.planned

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.Hit
import co.elastic.clients.elasticsearch.core.search.ResponseBody
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.BackendPageConsistency
import me.ahoo.wow.query.backend.BackendPageQueryPlan
import me.ahoo.wow.query.backend.BackendRecord
import me.ahoo.wow.query.backend.BackendTotalRelation
import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendExecutionOptions
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.util.Collections
import java.util.IdentityHashMap

internal class ElasticsearchPitPageExecutor(
    private val client: ReactiveElasticsearchClient,
    private val binding: ElasticsearchPreparedQueryBinding,
    private val compiler: ElasticsearchRecordQueryCompiler,
    private val mapper: ElasticsearchSnapshotRecordMapper,
    private val clock: Clock,
) {
    fun execute(
        plan: BackendPageQueryPlan,
        options: QueryBackendExecutionOptions,
    ): Mono<BackendPage> {
        val compiled = compiler.compile(plan)
        if (compiled.sort.isEmpty()) {
            unsupported()
        }
        return Mono.usingWhen(
            openPit(),
            { lease -> collectPage(lease, plan, compiled, options) },
            ::closePit,
            { lease, error -> closeAfterError(lease, error) },
            ::closeAfterCancel,
        ).onErrorMap(::mapPitLifecycleError)
    }

    private fun openPit(): Mono<PitLease> = client.openPointInTime(
        OpenPointInTimeRequest.of { request ->
            request.index(binding.indexName).keepAlive { keepAlive -> keepAlive.time(PIT_KEEP_ALIVE) }
        },
    ).switchIfEmpty(
        Mono.error(QueryBackendException(QueryBackendFailureKind.UNAVAILABLE)),
    ).map { response ->
        if (response.id().isBlank() || response.shards().failed() != 0) {
            incomplete()
        }
        PitLease(response.id())
    }

    private fun collectPage(
        lease: PitLease,
        plan: BackendPageQueryPlan,
        compiled: ElasticsearchCompiledRecordQuery,
        options: QueryBackendExecutionOptions,
    ): Mono<BackendPage> {
        val state = PageState(
            lease,
            plan,
            compiled,
            options,
            options.maxCursorPages ?: DEFAULT_MAX_CURSOR_PAGES,
        )
        return fetchNext(state).then(Mono.fromSupplier { state.toPage() })
    }

    private fun fetchNext(state: PageState): Mono<Void> = Mono.defer {
        if (state.isComplete()) {
            return@defer Mono.empty()
        }
        if (state.cursorPages >= state.maxCursorPages) {
            budgetExceeded()
        }
        val batchSize = state.nextBatchSize()
        client.search(searchRequest(state, batchSize), Map::class.java)
            .onErrorMap(::mapPitSearchError)
            .switchIfEmpty(Mono.error(QueryBackendException(QueryBackendFailureKind.INCOMPLETE_RESULT)))
            .flatMap { response ->
                state.accept(response)
                fetchNext(state)
            }
    }

    private fun searchRequest(state: PageState, batchSize: Int): SearchRequest = SearchRequest.of { request ->
        request.pit { pit ->
            pit.id(state.lease.id).keepAlive { keepAlive -> keepAlive.time(PIT_KEEP_ALIVE) }
        }.query(state.compiled.query)
            .size(batchSize)
            .allowPartialSearchResults(false)
            .trackTotalHits { total -> total.enabled(true) }
            .sort(state.compiled.sort)
            .also { builder ->
                state.compiled.sourceFilter?.let { sourceFilter ->
                    builder.source { source -> source.filter(sourceFilter) }
                }
                state.searchAfter?.let(builder::searchAfter)
                state.options.remainingMillis()?.let { remaining -> builder.timeout("${remaining}ms") }
            }
    }

    private fun closePit(lease: PitLease): Mono<Void> = client.closePointInTime(
        ClosePointInTimeRequest.of { request -> request.id(lease.id) },
    )
        .switchIfEmpty(Mono.error(QueryBackendException(QueryBackendFailureKind.INCOMPLETE_RESULT)))
        .flatMap { response ->
            if (!response.succeeded()) {
                Mono.error(QueryBackendException(QueryBackendFailureKind.INCOMPLETE_RESULT))
            } else {
                Mono.empty()
            }
        }

    private fun closeAfterError(lease: PitLease, original: Throwable): Mono<Void> =
        closePit(lease).onErrorResume { closeError ->
            original.addSuppressed(closeError)
            Mono.empty()
        }

    private fun closeAfterCancel(lease: PitLease): Mono<Void> = closePit(lease).onErrorResume { Mono.empty() }

    private fun QueryBackendExecutionOptions.remainingMillis(): Long? {
        val currentDeadline = deadline ?: return null
        val now = clock.instant()
        val remaining = try {
            Duration.between(now, currentDeadline).toMillis()
        } catch (error: ArithmeticException) {
            if (currentDeadline.isAfter(now)) {
                Long.MAX_VALUE
            } else {
                throw QueryBackendException(QueryBackendFailureKind.TIMEOUT, error)
            }
        }
        if (remaining <= 0) {
            throw QueryBackendException(QueryBackendFailureKind.TIMEOUT)
        }
        return remaining
    }

    private inner class PageState(
        val lease: PitLease,
        val plan: BackendPageQueryPlan,
        val compiled: ElasticsearchCompiledRecordQuery,
        val options: QueryBackendExecutionOptions,
        val maxCursorPages: Int,
    ) {
        val records = mutableListOf<BackendRecord>()
        var total: Long? = null
        var consumed: Long = 0
        var cursorPages: Int = 0
        var searchAfter: List<FieldValue>? = null

        fun isComplete(): Boolean = records.size == plan.page.size || total?.let { consumed >= it } == true

        fun nextBatchSize(): Int {
            val endExclusive = Math.addExact(plan.page.offset, plan.page.size.toLong())
            return minOf(PIT_BATCH_SIZE.toLong(), endExclusive - consumed).toInt().coerceAtLeast(1)
        }

        fun accept(response: ResponseBody<Map<*, *>>) {
            validateResponse(response)
            response.pitId()?.takeIf(String::isNotBlank)?.let(lease::update)
            val currentTotal = response.hits().total() ?: incomplete()
            if (currentTotal.relation() != TotalHitsRelation.Eq) {
                incomplete()
            }
            if (total != null && total != currentTotal.value()) {
                incomplete()
            }
            total = currentTotal.value()
            val hits = response.hits().hits()
            hits.forEachIndexed { index, hit ->
                val position = consumed + index
                if (position >= plan.page.offset && records.size < plan.page.size) {
                    records += hit.toRecord(plan)
                }
            }
            consumed += hits.size
            cursorPages++
            if (!isComplete()) {
                val last = hits.lastOrNull() ?: incomplete()
                if (last.sort().isEmpty()) {
                    incomplete()
                }
                searchAfter = last.sort()
            }
        }

        fun toPage(): BackendPage {
            val exactTotal = total ?: incomplete()
            val expected = minOf(
                plan.page.size.toLong(),
                (exactTotal - plan.page.offset).coerceAtLeast(0),
            )
            if (records.size.toLong() != expected) {
                incomplete()
            }
            return BackendPage(
                records,
                exactTotal,
                BackendTotalRelation.EXACT,
                BackendPageConsistency.SAME_INPUT,
            )
        }
    }

    private fun validateResponse(response: ResponseBody<*>) {
        if (response.timedOut()) {
            throw QueryBackendException(QueryBackendFailureKind.TIMEOUT)
        }
        if (response.shards().failed() != 0) {
            incomplete()
        }
    }

    private fun mapPitSearchError(error: Throwable): Throwable = when {
        error is QueryBackendException -> error
        error.isMissingElasticsearchSearchContext() ->
            QueryBackendException(QueryBackendFailureKind.INCOMPLETE_RESULT, error)
        else -> error
    }

    private fun mapPitLifecycleError(error: Throwable): Throwable {
        val classified = error.findBackendFailure() ?: return error
        if (classified === error) return error
        return QueryBackendException(classified.kind, error)
    }

    private fun Throwable.findBackendFailure(): QueryBackendException? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = this
        while (current != null && visited.add(current)) {
            if (current is QueryBackendException) return current
            current = current.cause
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun Hit<*>.toRecord(plan: BackendPageQueryPlan): BackendRecord {
        if (ignored().isNotEmpty()) {
            incomplete()
        }
        val source = source() as? Map<String, Any?> ?: incomplete()
        val identity = id() ?: incomplete()
        return mapper.map(identity, source, plan.projection)
    }

    private fun unsupported(): Nothing = throw QueryBackendException(QueryBackendFailureKind.UNSUPPORTED)

    private fun budgetExceeded(): Nothing = throw QueryBackendException(QueryBackendFailureKind.BUDGET_EXCEEDED)

    private fun incomplete(): Nothing = throw QueryBackendException(QueryBackendFailureKind.INCOMPLETE_RESULT)

    private class PitLease(initialId: String) {
        var id: String = initialId
            private set

        fun update(newId: String) {
            id = newId
        }
    }

    private companion object {
        const val PIT_KEEP_ALIVE = "1m"
        const val PIT_BATCH_SIZE = 1_000
        const val DEFAULT_MAX_CURSOR_PAGES = 1_024
    }
}

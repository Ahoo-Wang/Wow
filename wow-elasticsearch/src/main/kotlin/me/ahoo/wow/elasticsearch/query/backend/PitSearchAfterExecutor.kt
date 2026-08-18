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

package me.ahoo.wow.elasticsearch.query.backend

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch.core.SearchRequest
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

internal data class PitSearchHit<T : Any>(
    val source: T,
    val sort: List<FieldValue>,
)

internal data class PitSearchPage<T : Any>(
    val hits: List<PitSearchHit<T>>,
    val pitId: String? = null,
    val terminalError: QueryException? = null,
)

internal interface PitSearchAfterTransport<T : Any> {
    fun open(index: String): Mono<String>

    fun search(request: SearchRequest): Mono<PitSearchPage<T>>

    fun close(pitId: String): Mono<Void>
}

internal class PitSearchAfterExecutor<T : Any>(
    private val transport: PitSearchAfterTransport<T>,
    private val index: String,
    private val pageSize: Int,
    private val prefetchFirstPitPage: Boolean = false,
    private val prefetchBarrier: (() -> Mono<Void>)? = null,
    private val now: () -> Instant = Instant::now,
    private val deadlineScheduler: Scheduler = Schedulers.parallel(),
    private val requestDecorator: (SearchRequest) -> SearchRequest,
) {
    init {
        require(pageSize > 0) { "pageSize must be positive." }
    }

    fun execute(maxResults: Long?, deadline: Instant? = null): Flux<PitSearchHit<T>> =
        execute(requestLimit = null, maxResults = maxResults, deadline = deadline)

    fun execute(
        requestLimit: Long?,
        maxResults: Long?,
        deadline: Instant? = null,
    ): Flux<PitSearchHit<T>> {
        val execution = Flux.usingWhen(
            transport.open(index).map(::AtomicReference).switchIfEmpty(Mono.error(backendFailure())),
            { session -> search(session, emptyList(), 0, requestLimit, maxResults, prefetchFirstPitPage) },
            { session -> close(session.get()) },
            { session, _ -> close(session.get()) },
            { session -> close(session.get()) },
        ).onErrorMap(::sanitize)
        if (deadline == null) {
            return execution
        }
        val remaining = Duration.between(now(), deadline)
        if (remaining.isZero || remaining.isNegative) {
            return Flux.error(deadlineExceeded())
        }
        return execution.timeout(
            remaining,
            Flux.error(deadlineExceeded()),
            deadlineScheduler,
        )
    }

    private fun search(
        session: AtomicReference<String>,
        searchAfter: List<FieldValue>,
        emitted: Long,
        requestLimit: Long?,
        maxResults: Long?,
        prefetchNext: Boolean = false,
    ): Flux<PitSearchHit<T>> = Flux.defer {
        val window = searchWindow(emitted, requestLimit, maxResults) ?: return@defer Flux.empty()
        val request = searchRequest(session.get(), searchAfter, window.requestedSize)
        transport.search(request).flatMapMany { page ->
            page.pitId?.let(session::set)
            page.terminalError?.let { error -> return@flatMapMany Flux.error(error) }
            emitPage(session, searchAfter, emitted, requestLimit, maxResults, window, page, prefetchNext)
        }
    }

    private fun searchWindow(emitted: Long, requestLimit: Long?, maxResults: Long?): SearchWindow? {
        val remainingRequest = requestLimit?.minus(emitted)
        if (remainingRequest != null && remainingRequest <= 0) {
            return null
        }
        val remainingBudget = maxResults?.minus(emitted)
        if (remainingBudget != null && remainingBudget < 0) {
            throw budgetExceeded()
        }
        val budgetWindow = remainingBudget?.let { if (it == Long.MAX_VALUE) it else it + 1 }
        val requestedSize = listOfNotNull(pageSize.toLong(), remainingRequest, budgetWindow).min().toInt()
        return SearchWindow(remainingRequest, remainingBudget, requestedSize)
    }

    private fun searchRequest(pitId: String, searchAfter: List<FieldValue>, requestedSize: Int): SearchRequest =
        SearchRequest.of { builder ->
            builder
                .pit { pit -> pit.id(pitId).keepAlive { keepAlive -> keepAlive.time(PIT_KEEP_ALIVE) } }
                .size(requestedSize)
                .apply {
                    if (searchAfter.isNotEmpty()) {
                        searchAfter(searchAfter)
                    }
                }
        }.let(requestDecorator)

    private fun emitPage(
        session: AtomicReference<String>,
        searchAfter: List<FieldValue>,
        emitted: Long,
        requestLimit: Long?,
        maxResults: Long?,
        window: SearchWindow,
        page: PitSearchPage<T>,
        prefetchNext: Boolean,
    ): Flux<PitSearchHit<T>> {
        if (page.hits.isEmpty()) {
            return Flux.empty()
        }
        validateSort(page.hits, searchAfter)
        val allowed = listOfNotNull(
            page.hits.size.toLong(),
            window.remainingRequest,
            window.remainingBudget?.coerceAtLeast(0),
        ).min().toInt()
        val current = page.hits.take(allowed)
        val currentPage = Flux.fromIterable(current)
        val followingPages = nextPage(session, emitted, requestLimit, maxResults, window, page, current.size)
        return if (prefetchNext) {
            Flux.merge(
                followingPages,
                currentPage.delaySubscription(prefetchBarrier?.invoke() ?: Mono.empty()),
            )
        } else {
            currentPage.concatWith(followingPages)
        }
    }

    private fun nextPage(
        session: AtomicReference<String>,
        emitted: Long,
        requestLimit: Long?,
        maxResults: Long?,
        window: SearchWindow,
        page: PitSearchPage<T>,
        currentSize: Int,
    ): Flux<PitSearchHit<T>> {
        val budgetExceeded = window.remainingBudget != null &&
            page.hits.size.toLong() > window.remainingBudget &&
            (window.remainingRequest == null || window.remainingBudget < window.remainingRequest)
        val requestSatisfied = window.remainingRequest != null &&
            currentSize.toLong() >= window.remainingRequest
        return when {
            budgetExceeded -> Flux.error(budgetExceeded())
            requestSatisfied || page.hits.size < window.requestedSize -> Flux.empty()
            else -> search(
                session,
                page.hits.last().sort,
                emitted + currentSize,
                requestLimit,
                maxResults,
                prefetchNext = false,
            )
        }
    }

    private fun validateSort(hits: List<PitSearchHit<T>>, previous: List<FieldValue>) {
        val signatures = hits.map { hit ->
            if (hit.sort.isEmpty()) {
                invalidSort()
            }
            hit.sort.map(::fieldValueSignature)
        }
        val duplicateInPage = signatures.toSet().size != signatures.size
        val repeatsPrevious = previous.isNotEmpty() && signatures.first() == previous.map(::fieldValueSignature)
        if (duplicateInPage || repeatsPrevious) {
            invalidSort()
        }
    }

    private fun invalidSort(): Nothing = throw invalidResult()

    private fun fieldValueSignature(value: FieldValue): String = when (value._kind()) {
        FieldValue.Kind.String -> "s:${value.stringValue()}"
        FieldValue.Kind.Long -> "l:${value.longValue()}"
        FieldValue.Kind.Double -> "d:${value.doubleValue()}"
        FieldValue.Kind.Boolean -> "b:${value.booleanValue()}"
        FieldValue.Kind.Null -> "n:"
        FieldValue.Kind.Any -> throw invalidResult()
    }

    private fun budgetExceeded() = QueryException(
        QueryErrorCode.BUDGET_EXCEEDED,
        QueryStage.EXECUTION,
        QueryErrorReason.BUDGET_LIMIT_REACHED,
    )

    private fun invalidResult() = QueryException(
        QueryErrorCode.RESULT_VALIDATION_FAILED,
        QueryStage.EXECUTION,
        QueryErrorReason.RESULT_INVALID,
    )

    private fun deadlineExceeded() = QueryException(
        QueryErrorCode.DEADLINE_EXCEEDED,
        QueryStage.EXECUTION,
        QueryErrorReason.DEADLINE_REACHED,
    )

    private fun backendFailure() = QueryException(
        QueryErrorCode.BACKEND_FAILURE,
        QueryStage.EXECUTION,
        QueryErrorReason.BACKEND_EXECUTION_FAILED,
    )

    private fun close(pitId: String): Mono<Void> = transport.close(pitId)
        .onErrorMap { error ->
            Exceptions.throwIfFatal(error)
            if (error is QueryException) error else backendFailure()
        }

    private fun sanitize(error: Throwable): Throwable {
        Exceptions.throwIfFatal(error)
        return when {
            error is QueryException -> error
            error.cause is QueryException -> error.cause!!
            else -> backendFailure()
        }
    }

    companion object {
        private const val PIT_KEEP_ALIVE = "1m"
    }

    private data class SearchWindow(
        val remainingRequest: Long?,
        val remainingBudget: Long?,
        val requestedSize: Int,
    )
}

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
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.time.Instant
import kotlin.math.min

internal data class PitSearchHit<T : Any>(
    val source: T,
    val sort: List<FieldValue>,
)

internal data class PitSearchPage<T : Any>(
    val hits: List<PitSearchHit<T>>,
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
    private val now: () -> Instant = Instant::now,
    private val deadlineScheduler: Scheduler = Schedulers.parallel(),
    private val requestDecorator: (SearchRequest) -> SearchRequest,
) {
    init {
        require(pageSize > 0) { "pageSize must be positive." }
    }

    fun execute(maxResults: Long?, deadline: Instant? = null): Flux<PitSearchHit<T>> {
        val execution = Flux.usingWhen(
            transport.open(index).switchIfEmpty(Mono.error(backendFailure())),
            { pitId -> search(pitId, emptyList(), 0, maxResults) },
            ::close,
            { pitId, _ -> close(pitId) },
            ::close,
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
        pitId: String,
        searchAfter: List<FieldValue>,
        emitted: Long,
        maxResults: Long?,
    ): Flux<PitSearchHit<T>> = Flux.defer {
        val remaining = maxResults?.minus(emitted)
        if (remaining != null && remaining < 0) {
            return@defer Flux.error(budgetExceeded())
        }
        val requestedSize = remaining
            ?.let { min(pageSize.toLong(), if (it == Long.MAX_VALUE) it else it + 1).toInt() }
            ?: pageSize
        val request = SearchRequest.of { builder ->
            builder
                .pit { pit -> pit.id(pitId).keepAlive { keepAlive -> keepAlive.time(PIT_KEEP_ALIVE) } }
                .size(requestedSize)
                .apply {
                    if (searchAfter.isNotEmpty()) {
                        searchAfter(searchAfter)
                    }
                }
        }.let(requestDecorator)

        transport.search(request).flatMapMany { page ->
            if (page.hits.isEmpty()) {
                return@flatMapMany Flux.empty()
            }
            validateSort(page.hits, searchAfter)
            val allowed = remaining?.coerceAtLeast(0)?.coerceAtMost(page.hits.size.toLong())?.toInt()
                ?: page.hits.size
            val current = page.hits.take(allowed)
            val terminal = when {
                allowed < page.hits.size -> Flux.error(budgetExceeded())
                page.hits.size < requestedSize -> Flux.empty()
                else -> search(pitId, page.hits.last().sort, emitted + current.size, maxResults)
            }
            Flux.fromIterable(current).concatWith(terminal)
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
        .onErrorMap { error -> if (error is QueryException) error else backendFailure() }

    private fun sanitize(error: Throwable): Throwable = when {
        error is QueryException -> error
        error.cause is QueryException -> error.cause!!
        else -> backendFailure()
    }

    companion object {
        private const val PIT_KEEP_ALIVE = "1m"
    }
}

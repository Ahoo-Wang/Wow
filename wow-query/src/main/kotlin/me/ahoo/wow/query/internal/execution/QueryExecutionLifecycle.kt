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

package me.ahoo.wow.query.internal.execution

import me.ahoo.wow.query.backend.QueryBackendException
import me.ahoo.wow.query.backend.QueryBackendFailureKind
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.policy.QueryExecutionRequest
import me.ahoo.wow.query.internal.rejection.QueryRejectedException
import me.ahoo.wow.query.internal.rejection.QueryRejection
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import reactor.core.scheduler.Scheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class QueryErrorBoundary {
    fun normalize(error: Throwable): Throwable =
        when (error) {
            is QueryRejectedException -> error
            is QueryBackendException -> error.toRejectedException()
            else -> QueryRejectedException(
                QueryRejection(
                    QueryRejectionCategory.INTERNAL_FAILURE,
                    QueryRejectionPath.ROOT,
                    QueryRejectionCode.UNEXPECTED_QUERY_FAILURE,
                ),
                error,
            )
        }

    /** Normalizes failures emitted by an untrusted backend without allowing it to spoof gateway-stage rejections. */
    fun normalizeBackend(error: Throwable): Throwable =
        when (error) {
            is QueryBackendException -> error.toRejectedException()
            else -> QueryRejectedException(
                QueryRejection(
                    QueryRejectionCategory.INTERNAL_FAILURE,
                    QueryRejectionPath.ROOT.property("backend"),
                    QueryRejectionCode.BACKEND_EXECUTION_FAILED,
                ),
                error,
            )
        }

    private fun QueryBackendException.toRejectedException(): QueryRejectedException {
        val (category, path, code) =
            when (kind) {
                QueryBackendFailureKind.UNAVAILABLE -> Triple(
                    QueryRejectionCategory.BACKEND_UNAVAILABLE,
                    QueryRejectionPath.ROOT.property("backend"),
                    QueryRejectionCode.BACKEND_EXECUTION_FAILED,
                )

                QueryBackendFailureKind.TIMEOUT -> Triple(
                    QueryRejectionCategory.BACKEND_TIMEOUT,
                    QueryRejectionPath.ROOT.property("backend"),
                    QueryRejectionCode.BACKEND_TIMEOUT,
                )

                QueryBackendFailureKind.BUDGET_EXCEEDED -> Triple(
                    QueryRejectionCategory.BUDGET_EXCEEDED,
                    QueryRejectionPath.ROOT.property("executionContext").property("budget"),
                    QueryRejectionCode.BACKEND_BUDGET_EXCEEDED,
                )

                QueryBackendFailureKind.INCOMPLETE_RESULT -> Triple(
                    QueryRejectionCategory.INCOMPLETE_RESULT,
                    QueryRejectionPath.ROOT.property("backend").property("result"),
                    QueryRejectionCode.INCOMPLETE_RESULT,
                )

                QueryBackendFailureKind.MAPPING_FAILURE -> Triple(
                    QueryRejectionCategory.MAPPING_FAILURE,
                    QueryRejectionPath.ROOT.property("result"),
                    QueryRejectionCode.RESULT_MAPPING_FAILED,
                )

                QueryBackendFailureKind.UNSUPPORTED -> Triple(
                    QueryRejectionCategory.UNSUPPORTED_FEATURE,
                    QueryRejectionPath.ROOT.property("backend"),
                    QueryRejectionCode.BACKEND_OPERATION_UNSUPPORTED,
                )
            }
        return QueryRejectedException(QueryRejection(category, path, code), this)
    }
}

internal data class QueryLifecycleDescriptor(
    val request: QueryExecutionRequest,
    val operation: QueryOperation,
)

internal enum class QueryTerminationKind {
    COMPLETE,
    ERROR,
    CANCEL,
}

internal data class QueryLifecycleTerminal(
    val descriptor: QueryLifecycleDescriptor,
    val kind: QueryTerminationKind,
    val emitted: Long,
    val error: QueryRejectedException?,
)

internal interface QueryLifecycleObserver {
    fun onStart(descriptor: QueryLifecycleDescriptor) = Unit

    fun onTerminal(terminal: QueryLifecycleTerminal) = Unit

    companion object {
        val NONE: QueryLifecycleObserver = object : QueryLifecycleObserver {}
    }
}

internal class QueryLifecycleMonitor(
    private val observer: QueryLifecycleObserver = QueryLifecycleObserver.NONE,
) {
    fun <T : Any> observeMono(descriptor: QueryLifecycleDescriptor, source: Mono<T>): Mono<T> = Mono.defer {
        observeStart(descriptor)
        val state = ObservationState(descriptor)
        source
            .doOnNext { state.emitted.incrementAndGet() }
            .doOnError(state.error::set)
            .doFinally { signal -> state.terminate(signal) }
    }

    fun <T : Any> observeFlux(descriptor: QueryLifecycleDescriptor, source: Flux<T>): Flux<T> = Flux.defer {
        observeStart(descriptor)
        val state = ObservationState(descriptor)
        source
            .doOnNext { state.emitted.incrementAndGet() }
            .doOnError(state.error::set)
            .doFinally { signal -> state.terminate(signal) }
    }

    private fun observeStart(descriptor: QueryLifecycleDescriptor) {
        try {
            observer.onStart(descriptor)
        } catch (_: RuntimeException) {
            // Observability cannot alter query behavior.
        }
    }

    private inner class ObservationState(
        private val descriptor: QueryLifecycleDescriptor,
    ) {
        val emitted = AtomicLong()
        val error = AtomicReference<Throwable?>()
        private val terminated = AtomicBoolean()

        fun terminate(signal: SignalType) {
            if (!terminated.compareAndSet(false, true)) {
                return
            }
            val kind =
                when (signal) {
                    SignalType.ON_COMPLETE -> QueryTerminationKind.COMPLETE
                    SignalType.CANCEL -> QueryTerminationKind.CANCEL
                    else -> QueryTerminationKind.ERROR
                }
            val normalizedError = error.get() as? QueryRejectedException
            try {
                observer.onTerminal(QueryLifecycleTerminal(descriptor, kind, emitted.get(), normalizedError))
            } catch (_: RuntimeException) {
                // Observability cannot replace the terminal signal.
            }
        }
    }
}

internal class QueryDeadlineEnforcer(
    private val clock: Clock,
    private val scheduler: Scheduler,
) {
    fun cappedDeadline(deadline: Instant?, maximumDuration: Duration): Instant {
        require(!maximumDuration.isZero && !maximumDuration.isNegative) {
            "Maximum deadline duration must be positive."
        }
        val maximumDeadline = clock.instant().plus(maximumDuration)
        return if (deadline != null && deadline.isBefore(maximumDeadline)) deadline else maximumDeadline
    }

    fun <T : Any> enforceMono(deadline: Instant?, source: () -> Mono<T>): Mono<T> = Mono.defer {
        val remaining = remaining(deadline) ?: return@defer Mono.defer(source)
        Mono.defer(source).timeout(remaining, Mono.error(deadlineExpired()), scheduler)
    }

    fun <T : Any> enforceFlux(deadline: Instant?, source: () -> Flux<T>): Flux<T> = Flux.defer {
        val remaining = remaining(deadline) ?: return@defer Flux.defer(source)
        enforceDeadline(Flux.defer(source), remaining)
    }

    private fun remaining(deadline: Instant?): Duration? {
        deadline ?: return null
        val remaining = Duration.between(clock.instant(), deadline)
        if (remaining.isZero || remaining.isNegative) {
            throw deadlineExpired()
        }
        return remaining
    }

    private fun <T : Any> enforceDeadline(source: Flux<T>, remaining: Duration): Flux<T> = Flux.defer {
        val expired = AtomicBoolean()
        val timeout = Mono.delay(remaining, scheduler).doOnNext { expired.set(true) }
        source.takeUntilOther(timeout).concatWith(
            Flux.defer {
                if (expired.get()) {
                    Flux.error(deadlineExpired())
                } else {
                    Flux.empty()
                }
            },
        )
    }

    private fun deadlineExpired(): QueryRejectedException = QueryRejectedException(
        QueryRejection(
            QueryRejectionCategory.BUDGET_EXCEEDED,
            QueryRejectionPath.ROOT.property("executionContext").property("deadline"),
            QueryRejectionCode.DEADLINE_EXPIRED,
        ),
    )
}

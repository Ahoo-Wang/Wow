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

package me.ahoo.wow.query.invocation

import me.ahoo.wow.api.query.error.QueryStage
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

internal class QueryDeadlineGuard(
    private val frozenInstant: Instant,
    private val anchorNanos: Long,
    private val scheduler: Scheduler,
    private val maximumTimerSlice: Duration
) {
    init {
        require(!maximumTimerSlice.isNegative && !maximumTimerSlice.isZero) {
            "maximumTimerSlice must be positive."
        }
        require(maximumTimerSlice <= MAX_REACTOR_TIMER_SLICE) {
            "maximumTimerSlice exceeds Reactor's nanosecond timer range."
        }
    }

    fun <T : Any> enforce(
        publisher: Mono<T>,
        absoluteDeadline: Instant?,
        stage: QueryStage
    ): Mono<T> {
        if (absoluteDeadline == null) {
            return publisher
        }
        return Mono.defer {
            val sliceAnchorNanos = scheduler.now(TimeUnit.NANOSECONDS)
            val remaining = initialRemaining(absoluteDeadline, sliceAnchorNanos)
            if (remaining.isZero) {
                return@defer Mono.error(QueryDeadlineExceededException(stage))
            }
            publisher.timeout(deadlineSignal(remaining, stage, sliceAnchorNanos))
        }
    }

    private fun deadlineSignal(
        remaining: Duration,
        stage: QueryStage,
        sliceAnchorNanos: Long
    ): Mono<Void> {
        val timerSlice = minOf(remaining, maximumTimerSlice, SAFE_TIMER_SLICE)
        return Mono.delay(timerSlice, scheduler)
            .then(
                Mono.defer {
                    val currentNanos = scheduler.now(TimeUnit.NANOSECONDS)
                    val sliceElapsedNanos = currentNanos - sliceAnchorNanos
                    if (sliceElapsedNanos < 0) {
                        return@defer Mono.error(QueryDeadlineExceededException(stage))
                    }
                    val sliceElapsed = Duration.ofNanos(sliceElapsedNanos)
                    val nextRemaining = if (sliceElapsed >= remaining) {
                        Duration.ZERO
                    } else {
                        remaining.minus(sliceElapsed)
                    }
                    if (nextRemaining.isZero) {
                        Mono.error(QueryDeadlineExceededException(stage))
                    } else {
                        deadlineSignal(nextRemaining, stage, currentNanos)
                    }
                }
            )
    }

    private fun initialRemaining(absoluteDeadline: Instant, currentNanos: Long): Duration {
        val initialBudget = Duration.between(frozenInstant, absoluteDeadline)
        if (initialBudget.isNegative || initialBudget.isZero) {
            return Duration.ZERO
        }
        val elapsedNanos = currentNanos - anchorNanos
        if (elapsedNanos < 0) {
            return Duration.ZERO
        }
        if (elapsedNanos == 0L) {
            return initialBudget
        }
        val elapsed = Duration.ofNanos(elapsedNanos)
        return if (elapsed >= initialBudget) Duration.ZERO else initialBudget.minus(elapsed)
    }

    companion object {
        private val MAX_REACTOR_TIMER_SLICE: Duration = Duration.ofNanos(Long.MAX_VALUE)
        // Keep each local ticker delta far below the signed half-range so wrap remains unambiguous.
        private val SAFE_TIMER_SLICE: Duration = Duration.ofDays(365)

        fun anchor(
            frozenInstant: Instant,
            scheduler: Scheduler,
            maximumTimerSlice: Duration = MAX_REACTOR_TIMER_SLICE
        ): QueryDeadlineGuard = QueryDeadlineGuard(
            frozenInstant = frozenInstant,
            anchorNanos = scheduler.now(TimeUnit.NANOSECONDS),
            scheduler = scheduler,
            maximumTimerSlice = maximumTimerSlice
        )
    }
}

internal class QueryDeadlineExceededException(
    val stage: QueryStage
) : RuntimeException(null, null, false, false)

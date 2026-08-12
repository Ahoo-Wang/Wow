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
    private val scheduler: Scheduler
) {
    fun <T : Any> enforce(
        publisher: Mono<T>,
        absoluteDeadline: Instant?,
        stage: QueryStage
    ): Mono<T> {
        if (absoluteDeadline == null) {
            return publisher
        }
        val remaining = remaining(absoluteDeadline)
        if (remaining.isZero) {
            return Mono.error(QueryDeadlineExceededException(stage))
        }
        return publisher.timeout(remaining, Mono.error(QueryDeadlineExceededException(stage)), scheduler)
    }

    private fun remaining(absoluteDeadline: Instant): Duration {
        val initialBudget = Duration.between(frozenInstant, absoluteDeadline)
        if (initialBudget.isNegative || initialBudget.isZero) {
            return Duration.ZERO
        }
        val elapsedNanos = try {
            Math.subtractExact(scheduler.now(TimeUnit.NANOSECONDS), anchorNanos)
        } catch (_: ArithmeticException) {
            return Duration.ZERO
        }
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
        fun anchor(frozenInstant: Instant, scheduler: Scheduler): QueryDeadlineGuard = QueryDeadlineGuard(
            frozenInstant,
            scheduler.now(TimeUnit.NANOSECONDS),
            scheduler
        )
    }
}

internal class QueryDeadlineExceededException(
    val stage: QueryStage
) : RuntimeException(null, null, false, false)

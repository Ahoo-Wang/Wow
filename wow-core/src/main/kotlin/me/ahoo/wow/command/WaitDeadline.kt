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

package me.ahoo.wow.command

import me.ahoo.wow.command.wait.WaitPlan
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The gateway's one deadline: an absolute end-to-end bound, armed once at subscription, that fails the wait with a
 * [TimeoutException] whose message says what was waited for and how long ([waitTimeoutMessage]); a timeout the
 * bounded publisher raises itself passes through unchanged.
 *
 * The deadline is one task on the gateway's own [timer], so unrelated work on shared schedulers cannot delay it, and it
 * is delivered on [Schedulers.parallel], so a slow timeout observer cannot occupy the timer thread. A stream is bounded
 * as a whole: every element waits on the same expiry, nothing is re-scheduled per element, so a deadline armed
 * before the gateway closed keeps working after it. The timer task is cancelled when the bounded publisher terminates.
 */
internal class WaitDeadline(private val timer: Scheduler) {
    /** A single result arms the deadline once, so it needs no shared expiry: the timeout's own delay is it. */
    fun <T : Any> bound(mono: Mono<T>, timeout: Duration, waited: String): Mono<T> =
        mono.timeout(
            Mono.delay(timeout, timer).publishOn(Schedulers.parallel()),
            expired(timeout, waited),
        )

    fun <T : Any> bound(flux: Flux<T>, timeout: Duration, waited: String): Flux<T> =
        Flux.defer {
            val deadline = Deadline(timeout)
            flux.timeout(deadline.signal, { deadline.signal }, expired(timeout, waited))
                .doFinally { deadline.cancel() }
        }

    private fun <T : Any> expired(timeout: Duration, waited: String): Mono<T> =
        Mono.error { TimeoutException(waitTimeoutMessage(waited, timeout)) }

    private inner class Deadline(timeout: Duration) {
        private val expired = Sinks.one<Long>()
        private val task = timer.schedule(
            { expired.tryEmitValue(0L) },
            timeout.toNanos().coerceAtLeast(0),
            TimeUnit.NANOSECONDS,
        )

        // Cancellation and user error callbacks must not run on the timer thread.
        val signal: Mono<Long> = expired.asMono().publishOn(Schedulers.parallel())

        fun cancel() = task.dispose()
    }
}

/**
 * The message of a gateway wait that ran out of time, as `RequestTimeout`'s `errorMsg` carries it to the caller:
 * `Waiting for stage [PROJECTED] timed out after 10000ms.`
 */
internal fun waitTimeoutMessage(waited: String, timeout: Duration): String =
    "Waiting for $waited timed out after ${timeout.toMillis()}ms."

/** What a plan waits for, as a timeout names it: the stage, and the function when the plan names one. */
internal fun WaitPlan.waited(): String {
    val function = target.function
        ?.let { listOf(it.processorName, it.name).filter(String::isNotBlank).joinToString(".") }
        ?.takeIf { it.isNotEmpty() }
        ?.let { " of function [$it]" }
        .orEmpty()
    return "stage [${target.stage}]$function"
}

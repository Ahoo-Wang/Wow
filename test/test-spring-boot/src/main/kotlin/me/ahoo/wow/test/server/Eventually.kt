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

package me.ahoo.wow.test.server

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How long [eventually] polls by default: a saga across a few commands on a loaded build machine, and then some. */
val EVENTUALLY_TIMEOUT: Duration = 20.seconds

/** How long [eventually] sleeps between two polls by default. */
val EVENTUALLY_POLL: Duration = 100.milliseconds

/**
 * Polls [condition] until it holds, every [poll], for at most [timeout], and fails saying [what] was not reached, with
 * [detail] then, when one is given (the services' output, the state seen last), and the last failure of [condition]
 * as its cause: a condition that throws, an assertion among them, has not held yet.
 *
 * A wait plan waits for what a command causes, to the end of a saga's chain (`CommandClient.wait`, `stream`), so a
 * test waits so first. This is for what no wait plan sees: a call a fake peer received, a scheduled command that
 * fires, a projection another service reads.
 *
 * ```kotlin
 * eventually("the order is cancelled") {
 *     server.queries(alice).snapshots(OrderState::class.java).state(orderId)?.state?.status == OrderStatus.CANCELLED
 * }
 * ```
 *
 * It is `inline`, so [condition] runs in the caller's context: a blocking test's or a coroutine's, which may call the
 * typed clients' `suspend` functions in it. It sleeps the thread between polls, as a test's real time passes.
 */
@Suppress("TooGenericExceptionCaught")
inline fun eventually(
    what: String,
    timeout: Duration = EVENTUALLY_TIMEOUT,
    poll: Duration = EVENTUALLY_POLL,
    noinline detail: (() -> String)? = null,
    condition: () -> Boolean,
) {
    require(timeout.isPositive() && poll.isPositive()) { "The timeout and the poll of eventually are positive." }
    val deadline = TimeSource.Monotonic.markNow() + timeout
    var failure: Throwable? = null
    while (true) {
        val held = try {
            condition()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Exception) {
            failure = thrown
            false
        } catch (thrown: AssertionError) {
            failure = thrown
            false
        }
        if (held) return
        if (deadline.hasPassedNow()) {
            throw AssertionError("Not reached in $timeout: $what." + detail?.let { " ${it()}" }.orEmpty(), failure)
        }
        Thread.sleep(poll.inWholeMilliseconds)
    }
}

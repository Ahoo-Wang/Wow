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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.FailureClass
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/**
 * An entry's handling waiting before its attempt, behind the partition's throttle or pause, is given up by its worker's
 * stop: the handler is never called, and the handling leaves the stop's count at once.
 */
class EntryHandlingStopTest {
    private val attempts = AtomicInteger()
    private val subscription = Subscription("projection", SubscriptionFilter("shop")) { attempts.incrementAndGet() }
    private val options = testOptions()
    private val pause = PartitionPause("group", "projection", 0, options.systemic, Clock.systemUTC())
    private val throttle = PartitionThrottle("group", "projection", 0, 2, options.backpressure, Clock.systemUTC())
    private val running = RunningHandlings()
    private val handling = EntryHandling(
        "group",
        subscription,
        0,
        LogSource(InMemoryEventLog(partitions = 1)),
        options,
        options.retry,
        RuntimeFailures.DEFAULT,
        pause,
        throttle,
        Observer.NONE,
        Random(0),
        0L,
        running,
    )
    private val entry = SourcedEntry(
        LogPosition(0, 1),
        EventStreamRecord(
            cart("x"),
            1,
            "c-1",
            "r-1",
            "shop.cart.Act",
            0L,
            listOf(EventRecord(ADDED, 1, "{}")),
            Scope.NONE,
        ),
        null,
    )

    /** A stop that never comes. */
    private val never = CompletableDeferred<Unit>()

    @Test
    fun `a handling waiting for room under the throttle is given up by the stop`(): Unit = runBlocking {
        // Certain as every step here: one thread, coroutines in start order. The throttle's room is one and held, so
        // the handling waits for it from its start, and only the stop ends that wait.
        throttle.backpressure(cart("y"))
        throttle.acquire(never).assert().isTrue()
        running.started()
        val handled = async { handling.handle(entry) }
        repeat(WAITS) { yield() }
        running.value.assert().isEqualTo(1)
        running.stop()
        repeat(WAITS) { yield() }
        running.value.assert().isEqualTo(0)
        attempts.get().assert().isEqualTo(0)
        handled.cancelAndJoin()
        // It took no room: the one held is the throttle's only one, given back.
        throttle.release()
        throttle.acquire(never).assert().isTrue()
    }

    @Test
    fun `a handling waiting on a paused partition is given up by the stop`(): Unit = runBlocking {
        // Certain as every step here: one thread, coroutines in start order. The pause's probe backoff is a minute,
        // so the handling waits there from its start, and only the stop ends that wait.
        val paused = PartitionPause(
            "group",
            "projection",
            0,
            SystemicFailureOptions(initialBackoff = 1.minutes, maxBackoff = 1.minutes),
            Clock.systemUTC(),
        )
        paused.failed(cart("y"), FailureClass.SYSTEMIC, IllegalStateException("down"), probing = false)
        val behind = EntryHandling(
            "group",
            subscription,
            0,
            LogSource(InMemoryEventLog(partitions = 1)),
            options,
            options.retry,
            RuntimeFailures.DEFAULT,
            paused,
            throttle,
            Observer.NONE,
            Random(0),
            0L,
            running,
        )
        running.started()
        val handled = async { behind.handle(entry) }
        repeat(WAITS) { yield() }
        running.value.assert().isEqualTo(1)
        running.stop()
        repeat(WAITS) { yield() }
        running.value.assert().isEqualTo(0)
        attempts.get().assert().isEqualTo(0)
        handled.cancelAndJoin()
    }

    private companion object {
        const val WAITS = 20
    }
}

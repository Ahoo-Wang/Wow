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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.timer.TestClock
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How many parked entries a worker runs at once: at most its in-flight bound, each holding a retry permit of its own
 * from before it is claimed, apart from the permits its live entries hold, so neither keeps the other waiting.
 */
class ParkedRetriesBoundTest {
    /** One partition, so one worker holds every entry. */
    private val inner = InMemoryEventLog(partitions = 1)
    private val ledger = InMemoryFailureLedger()
    private val clock = TestClock()
    private val id = SubscriptionId("shop", NAME)

    /** While a retry is wanted, the handler fails no more: a first attempt of each aggregate parks it. */
    private val retrying = AtomicReference(false)

    /** While set, a handled entry of an aggregate it names waits for it. */
    private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val handled = ConcurrentHashMap.newKeySet<String>()
    private val entered = ConcurrentHashMap.newKeySet<String>()

    /** While set, a retry's read of the aggregate it names waits for it. */
    private val reads = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** A retry reads its aggregate's entries with [EventLog.readEntries]: that read is the one held. */
    private val log = object : EventLog by inner {
        override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> =
            inner.readEntries(ref, fromVersion, toVersion).onStart { reads[ref.id]?.await() }
    }
    private val appender = Appender(log)

    private val subscription = Subscription(NAME, SubscriptionFilter("shop")) { entry ->
        if (!retrying.get()) error("${entry.ref.id} fails")
        entered += entry.ref.id
        gates[entry.ref.id]?.await()
        handled += entry.ref.id
    }

    private val runtime = SubscriptionRuntime(
        "group",
        log,
        InMemoryCheckpointStore(),
        ledger,
        listOf(subscription),
        // Several aggregates failing at once is no outage here: the partition is not paused for it.
        testOptions(maxInFlight = IN_FLIGHT, maxAttempts = 1).copyWith(
            parked = ParkedRetryOptions(SPEC, scanInterval = 5.milliseconds),
            systemic = SystemicFailureOptions(failingAggregates = Int.MAX_VALUE),
        ),
        clock,
    )

    @AfterEach
    fun stop(): Unit = runBlocking { runtime.stop() }

    /**
     * Parks [ids], each on its first attempt and one after another, as an outage parks them over time, then makes their
     * retries due unless [due] is false.
     */
    private suspend fun parked(vararg ids: String, due: Boolean = true) {
        runtime.start(setOf(0))
        ids.forEachIndexed { index, ref ->
            appender.append(ref)
            eventually { ledger.list(id).size == index + 1 }
        }
        retrying.set(true)
        if (due) {
            clock.advance(SPEC.initialBackoff)
        }
    }

    private suspend fun retries(ref: String): Int = ledger.parked(id, cart(ref))?.retries ?: -1

    @Test
    fun `a scan should start no more parked entries than the in-flight bound, leaving the others unclaimed`(): Unit =
        runBlocking {
            val ids = listOf("a", "b", "c", "d", "e")
            ids.forEach { gates[it] = CompletableDeferred() }
            parked(*ids.toTypedArray())

            eventually { ids.count { retries(it) == 1 } == IN_FLIGHT }
            // Several scans later still: the others wait as read, no retry spent and no claim running.
            repeat(SCANS) { delay(5) }
            ids.count { retries(it) == 1 }.assert().isEqualTo(IN_FLIGHT)
            ids.count { retries(it) == 0 }.assert().isEqualTo(ids.size - IN_FLIGHT)

            ids.forEach { gates.getValue(it).complete(Unit) }
            eventually { ledger.list(id).isEmpty() }
            handled.assert().containsAll(ids)
        }

    @Test
    fun `live entries holding every live permit should not keep a due retry from starting`(): Unit = runBlocking {
        parked("p", due = false)
        // Live entries of other aggregates, held in their handler: every live permit is taken.
        listOf("q", "r").forEach { gates[it] = CompletableDeferred() }
        appender.append("q")
        appender.append("r")
        eventually { entered.containsAll(listOf("q", "r")) }

        // Only now due: the retry starts and claims, its handling waiting behind the live ones under the throttle.
        clock.advance(SPEC.initialBackoff)
        eventually { retries("p") == 1 }

        listOf("q", "r").forEach { gates.getValue(it).complete(Unit) }
        eventually { ledger.list(id).isEmpty() && handled.containsAll(listOf("p", "q", "r")) }
    }

    @Test
    fun `retries holding every retry permit should not delay a live entry's admission`(): Unit = runBlocking {
        reads["a"] = CompletableDeferred()
        reads["b"] = CompletableDeferred()
        parked("a", "b")
        eventually { retries("a") == 1 && retries("b") == 1 }

        // Both retries are in hand, held in their reads; a live entry is admitted and handled all the same.
        appender.append("live")
        eventually { "live" in handled }

        reads.values.forEach { it.complete(Unit) }
        eventually { ledger.list(id).isEmpty() }
    }

    private companion object {
        const val NAME = "bounded"
        const val IN_FLIGHT = 2
        const val SCANS = 10
        val SPEC = ParkedRetrySpec(maxRetries = 3, initialBackoff = 1.seconds, claimTtl = 10.seconds)
    }
}

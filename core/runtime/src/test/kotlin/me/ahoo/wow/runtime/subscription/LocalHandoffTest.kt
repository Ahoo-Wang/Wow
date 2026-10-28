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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.log.HandoffLog
import me.ahoo.wow.runtime.log.HandoffOptions
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The subscriptions of a node over a [HandoffLog] (decision D249): its own commits reach them from memory. */
class LocalHandoffTest {
    private val heads = HeadsLog(InMemoryEventLog(partitions = PARTITIONS))
    private val store = CountingLog(heads)
    private val reads = TailReads()
    private val foreign = Appender(store)
    private val checkpoints = RecordingCheckpoints()

    /**
     * By default a gap or an idle partition waits so long that only a real one reads the store, however loaded the
     * machine is: the tests count the reads memory could not spare.
     */
    private fun handoff(gapWait: Duration = 10.seconds, probeInterval: Duration = 10.seconds) =
        HandoffLog(store, "shop", HandoffOptions(gapWait = gapWait, probeInterval = probeInterval), reads)

    private fun runtime(log: EventLog, vararg handled: Handled) = SubscriptionRuntime(
        "group",
        log,
        checkpoints,
        InMemoryFailureLedger(),
        handled.mapIndexed { i, h -> Subscription("s$i", SubscriptionFilter("shop")) { h.record(it) } },
        testOptions(),
    )

    /** Waits until every partition's tail has read its head: whatever is appended next is handed to it. */
    private suspend fun awaitTailsOpen() = eventually { heads.read.size >= PARTITIONS }

    private suspend fun appendAcross(appender: Appender, count: Int) = repeat(count) { appender.append("a${it % 16}") }

    @Test
    fun `in the steady state the node's own commits cause no read of the store`(): Unit = runBlocking {
        val log = handoff()
        val first = Handled()
        val second = Handled()
        val runtime = runtime(log, first, second)
        runtime.start(setOf(0, 1, 2, 3))
        awaitTailsOpen()
        appendAcross(Appender(log), 200)
        eventually { first.size == 200 && second.size == 200 }
        runtime.stop()

        reads.reasons.assert().isEmpty()
        store.tails.get().assert().isEqualTo(0)
        log.storeReads.assert().isEqualTo(0L)
        listOf(first, second).forEach { handled ->
            (0 until 16).forEach { id ->
                handled.versionsOf("a$id").assert().isEqualTo((1..handled.versionsOf("a$id").size).toList())
            }
        }
    }

    @Test
    fun `commits made while the tails open are read from the store at most once each, then come from memory`(): Unit =
        runBlocking {
            val log = handoff()
            val handled = Handled()
            val runtime = runtime(log, handled)
            // A tail that opens after a commit reads it from the store (its start); none reads for any other reason.
            runtime.start(setOf(0, 1, 2, 3))
            appendAcross(Appender(log), 200)
            eventually { handled.size == 200 }
            runtime.stop()

            reads.reasons.toSet().assert().isSubsetOf(setOf(ObservedEvent.TailRead.Reason.START))
            reads.reasons.size.assert().isLessThanOrEqualTo(PARTITIONS)
            handled.entries.map { it.first to it.second }.toSet().size.assert().isEqualTo(200)
        }

    @Test
    fun `a backlog is read from the store once per partition, then the commits come from memory`(): Unit =
        runBlocking {
            val log = handoff()
            val own = Appender(log)
            appendAcross(own, 100)
            val handled = Handled()
            val runtime = runtime(log, handled)
            runtime.start(setOf(0, 1, 2, 3))
            appendAcross(own, 100)
            eventually { handled.size == 200 }
            runtime.stop()

            reads.reasons.assert().isEqualTo(List(PARTITIONS) { ObservedEvent.TailRead.Reason.START })
            store.tails.get().assert().isEqualTo(PARTITIONS)
            handled.entries.map { it.first to it.second }.toSet().size.assert().isEqualTo(200)
        }

    @Test
    fun `another node's appends among the node's own are handled in order, nothing lost or twice`(): Unit =
        runBlocking {
            val log = handoff(gapWait = 20.milliseconds, probeInterval = 50.milliseconds)
            val own = Appender(log)
            val handled = Handled()
            val runtime = runtime(log, handled)
            runtime.start(setOf(0, 1, 2, 3))
            repeat(50) {
                own.append("a${it % 16}")
                if (it % 10 == 0) {
                    foreign.append("f${it % 4}", BASKET)
                }
            }
            eventually { handled.size == 55 }
            // The last ones another node appended, after the node's own: found by the probe of the head.
            repeat(4) { foreign.append("g$it", BASKET) }
            eventually { handled.size == 59 }
            runtime.stop()

            handled.entries.map { it.first to it.second }.toSet().size.assert().isEqualTo(59)
            (0 until 16).forEach { id ->
                handled.versionsOf("a$id").assert().isEqualTo((1..handled.versionsOf("a$id").size).toList())
            }
            log.storeReads.assert().isGreaterThan(0L)
        }

    private companion object {
        const val PARTITIONS = 4
        val BASKET = AggregateType("shop", "basket")
    }
}

/** A log that records the partitions whose head was read, once the read returned. */
private class HeadsLog(private val delegate: EventLog) : EventLog by delegate {
    val read: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    override suspend fun head(partition: Int): Long? = delegate.head(partition).also { read += partition }
}

/** The reason of every read of the store a tail made. */
private class TailReads : Observer {
    val reasons = CopyOnWriteArrayList<ObservedEvent.TailRead.Reason>()

    override fun observed(event: ObservedEvent) {
        when (event) {
            is ObservedEvent.TailRead -> reasons += event.reason
            else -> Unit
        }
    }
}

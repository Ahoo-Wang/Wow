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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.health.NodeFatals
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.observation.ObservedEvent.Fatal.Component
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * A lane whose item meets what it does not handle, an `Error` of the worker's own work (RT-5): the lane ends as a loop
 * of the node does, the node's liveness `DOWN`, and an operator's action waiting on it is answered with the error.
 * Before, the lane stopped unseen, still active, and an action other than a fatal error's waited for good.
 */
class LaneEndTest {
    private val inner = InMemoryEventLog(partitions = 1)
    private val id = SubscriptionId("shop", NAME)
    private val fatals = NodeFatals("node-1")

    /** The aggregates whose park fails with an error no retry takes. */
    @Volatile
    private var parkFails: Set<String> = emptySet()

    /** The aggregates whose retry's read fails with an error no retry takes. */
    @Volatile
    private var readFails: Set<String> = emptySet()

    private val shared = InMemoryFailureLedger()
    private val ledger = object : FailureLedger by shared {
        override suspend fun park(entry: ParkedAggregate): Boolean {
            if (entry.ref.id in parkFails) {
                throw NotImplementedError("Parking ${entry.ref.id} is not written yet.")
            }
            return shared.park(entry)
        }
    }

    private val log = object : EventLog by inner {
        override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> =
            inner.readEntries(ref, fromVersion, toVersion).onStart {
                if (ref.id in readFails) {
                    throw NotImplementedError("Reading ${ref.id} again is not written yet.")
                }
            }
    }
    private val appender = Appender(log)
    private val handled = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var failing: Set<String> = emptySet()

    private val subscription = Subscription(NAME, SubscriptionFilter("shop")) { entry ->
        if (entry.ref.id in failing) error("${entry.ref.id} fails")
        handled += entry.ref.id
    }

    private val runtime = SubscriptionRuntime(
        "group",
        log,
        InMemoryCheckpointStore(),
        ledger,
        listOf(subscription),
        testOptions(maxAttempts = 1),
        fatals = fatals,
    )

    @AfterEach
    fun stop(): Unit = runBlocking { runtime.stop() }

    @Test
    fun `a lane whose item meets an error should end as a loop of the node does, the other lanes going on`(): Unit =
        runBlocking {
            failing = setOf("a")
            parkFails = setOf("a")
            runtime.start(setOf(0))

            appender.append("a")
            eventually { fatals.endedLoop != null }
            appender.append("b")
            eventually { "b" in handled }

            fatals.endedLoop!!.let {
                it.component.assert().isEqualTo(Component.SUBSCRIPTION)
                it.loop.assert().isEqualTo("lanes of $NAME partition 0")
                it.error.assert().isEqualTo(NotImplementedError::class.java.name)
            }
            fatals.isFatal.assert().isFalse()
        }

    @Test
    fun `an operator's retry whose run meets an error should be answered with it, not wait for good`(): Unit =
        runBlocking {
            failing = setOf("a")
            runtime.start(setOf(0))
            appender.append("a")
            eventually { ledger.parked(id, cart("a")) != null }

            failing = emptySet()
            readFails = setOf("a")

            val thrown = runCatching { withTimeout(10.seconds) { runtime.retry(NAME, cart("a")) } }.exceptionOrNull()

            thrown.assert().isInstanceOf(NotImplementedError::class.java)
            eventually { fatals.endedLoop != null }
        }

    private companion object {
        const val NAME = "lanes"
    }
}

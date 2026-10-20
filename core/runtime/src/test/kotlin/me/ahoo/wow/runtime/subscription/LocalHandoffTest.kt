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
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/** The subscriptions of a node over a [HandoffLog] (decision D249): its own commits reach them from memory. */
class LocalHandoffTest {
    private val store = CountingLog(InMemoryEventLog(partitions = 4))
    private val log =
        HandoffLog(store, "shop", HandoffOptions(gapWait = 20.milliseconds, probeInterval = 50.milliseconds))
    private val own = Appender(log)
    private val foreign = Appender(store)
    private val checkpoints = RecordingCheckpoints()

    private fun runtime(vararg handled: Handled) = SubscriptionRuntime(
        "group",
        log,
        checkpoints,
        InMemoryFailureLedger(),
        handled.mapIndexed { i, h -> Subscription("s$i", SubscriptionFilter("shop")) { h.record(it) } },
        testOptions(),
    )

    private suspend fun appendAcross(appender: Appender, count: Int, prefix: String = "a") =
        repeat(count) { appender.append("$prefix${it % 16}") }

    @Test
    fun `in the steady state the node's own commits cause no read of the store`(): Unit = runBlocking {
        val first = Handled()
        val second = Handled()
        val runtime = runtime(first, second)
        runtime.start(setOf(0, 1, 2, 3))
        appendAcross(own, 200)
        eventually { first.size == 200 && second.size == 200 }
        runtime.stop()

        store.tails.get().assert().isEqualTo(0)
        log.storeReads.assert().isEqualTo(0L)
        listOf(first, second).forEach { handled ->
            (0 until 16).forEach { id ->
                handled.versionsOf("a$id").assert().isEqualTo((1..handled.versionsOf("a$id").size).toList())
            }
        }
    }

    @Test
    fun `a backlog is read from the store once per partition, then the commits come from memory`(): Unit =
        runBlocking {
            appendAcross(own, 100)
            val handled = Handled()
            val runtime = runtime(handled)
            runtime.start(setOf(0, 1, 2, 3))
            appendAcross(own, 100)
            eventually { handled.size == 200 }
            runtime.stop()

            store.tails.get().assert().isEqualTo(4)
            handled.entries.map { it.first to it.second }.toSet().size.assert().isEqualTo(200)
        }

    @Test
    fun `another node's appends among the node's own are handled in order, nothing lost or twice`(): Unit =
        runBlocking {
            val handled = Handled()
            val runtime = runtime(handled)
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
        val BASKET = AggregateType("shop", "basket")
    }
}

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

package me.ahoo.wow.runtime.wait

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.subscription.RunHandled
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * The waits on the entries of a run of a projection made [Subscription.batching] (decision D413, rows 3 and 12 of the
 * runtime's design note): each entry the run handled is signalled at its own hop, and each entry behind the one it
 * parked at is signalled as parked.
 */
class RunWaitsTest {
    data class Place(val items: Int = 1)
    data class Add(val items: Int = 1)
    data class Placed(val items: Int)
    data class Added(val items: Int)

    private val order = aggregate<Any>("shop", "order") {
        initial { Any() }
        decide<Place>(Creation.ALLOWED) { _, body -> Decision.events(Placed(body.items)) }
        decide<Add>(Creation.NONE) { _, body -> Decision.events(Added(body.items)) }
        evolve<Placed> { }
        evolve<Added> { }
    }
    private val ref = order.type.ref("t", "o-1")
    private val log = InMemoryEventLog(partitions = 1)

    /** The order's versions the tail handed to the workers, once they took each: they take an entry inline. */
    private val taken = CopyOnWriteArrayList<Int>()
    private val watched = object : EventLog by log {
        override fun tail(partition: Int, after: Long?): Flow<LogEntry> = log.tail(partition, after).transform {
            emit(it)
            taken += it.stream.version
        }
    }

    /** The versions of each run, in order; the first run waits for [held]. */
    private val runs = CopyOnWriteArrayList<List<Int>>()
    private val held = CompletableDeferred<Unit>()

    /** The version a run, and its handling alone, fail at; 0 for none. */
    @Volatile
    private var failAt = 0

    private val sink = Subscription.batching(
        SINK,
        SubscriptionFilter("shop", setOf("order")),
        maxRun = 16,
        runHandler = { entries ->
            runs += entries.map { it.entry.version }
            if (runs.size == 1) held.await()
            val at = entries.indexOfFirst { it.entry.version == failAt }
            if (at < 0) RunHandled(entries.size, null) else RunHandled(at, IllegalStateException("o-1@$failAt fails"))
        },
    ) { entry ->
        check(entry.version != failAt) { "o-1@$failAt fails alone" }
    }
    private val registry = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val signals = LocalSignalSender(origin.node, registry)
    private val runtime = SubscriptionRuntime(
        "shop",
        watched,
        InMemoryCheckpointStore(),
        InMemoryFailureLedger(),
        listOf(sink),
        testOptions(maxAttempts = 1),
        signals = signals,
    )
    private val coordinator = WaitCoordinator(Executor(Kernel(log)), registry, runtime, signals, origin, WaitOptions())

    @AfterEach
    fun stop(): Unit = runBlocking {
        held.complete(Unit)
        runtime.stop()
    }

    @Test
    fun `each entry of a run is signalled at its own hop`(): Unit = runBlocking {
        runtime.start(setOf(0))
        coordinator.execute(order, Command("c-1", "r-1", ref, Place(), wait = WaitPlan.COMMITTED))
        eventually { runs.isNotEmpty() }
        val waits = (2..3).map { version ->
            val command = Command("c-$version", "r-$version", ref, Add(), wait = WaitPlan.of(Hop.handled(SINK)))
            async { coordinator.execute(order, command) }.also { eventually { version in taken } }
        }
        held.complete(Unit)
        waits.forEach {
            val result = withTimeout(5.seconds) { it.await() }
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.hop.assert().isEqualTo(Hop.handled(SINK, "shop"))
        }
        runs.assert().containsExactly(listOf(1), listOf(2, 3))
    }

    @Test
    fun `the entries of a run behind its parked entry are signalled as parked`(): Unit = runBlocking {
        failAt = 3
        runtime.start(setOf(0))
        coordinator.execute(order, Command("c-1", "r-1", ref, Place(), wait = WaitPlan.COMMITTED))
        eventually { runs.isNotEmpty() }
        val waits = (2..4).map { version ->
            val command = Command("c-$version", "r-$version", ref, Add(), wait = WaitPlan.of(Hop.handled(SINK)))
            async { coordinator.execute(order, command) }.also { eventually { version in taken } }
        }
        held.complete(Unit)
        val (second, third, fourth) = waits.map { withTimeout(5.seconds) { it.await() } }
        second.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        listOf(third to 3, fourth to 4).forEach { (result, version) ->
            result.status.assert().isEqualTo(CommandResult.Status.FAILED)
            result.error!!.code.assert().isEqualTo(ResultCodes.SUBSCRIPTION_PARKED)
            result.error!!.message.assert().startsWith("$ref@$version is parked by $SINK")
        }
        runs.assert().containsExactly(listOf(1), listOf(2, 3, 4))
    }

    private companion object {
        const val SINK = "order.sink"
    }
}

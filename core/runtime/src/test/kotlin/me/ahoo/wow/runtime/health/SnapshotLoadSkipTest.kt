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

package me.ahoo.wow.runtime.health

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.PlacedStores
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * A command's snapshot load on a store that stopped answering (X-128): after three loads of the store in a row failed or
 * timed out, with no answer between them, its loads are skipped, so a command replays its aggregate's log at once
 * instead of waiting out the load's timeout with one of the node's in-flight slots; the store's health probe answering
 * resumes them. The node's health reads what it read before: the store's probe alone.
 */
class SnapshotLoadSkipTest : NodeHealthFixtures() {
    /** The snapshot store on the placed store [SNAPSHOTS], counting its loads, which fail or hang on demand. */
    private class Snapshots(private val store: SnapshotStore = InMemorySnapshotStore()) : SnapshotStore by store {
        val loads = AtomicInteger()

        @Volatile
        var failing = false

        @Volatile
        var hanging = false

        override suspend fun load(ref: AggregateRef): SnapshotRecord? {
            loads.incrementAndGet()
            if (hanging) awaitCancellation()
            if (failing) throw StorageUnavailableException("The snapshot store does not answer.")
            return store.load(ref)
        }
    }

    private val snapshots = Snapshots()
    private val placed = ProbedStore(SNAPSHOTS, "elasticsearch")

    /** A node whose aggregates' snapshots are on the placed store [SNAPSHOTS], apart from their log. */
    private fun snapshotNode(options: ServiceNodeOptions = fastNodeOptions()): ServiceNode = node(
        options = options,
        backends = BackendFactory {
            backends.backends(it).copyWith(
                snapshots = SnapshotTargets.single(snapshots, SNAPSHOTS),
                stores = PlacedStores(setOf(placed)),
            )
        },
    )

    /** A deposit into a new account: a cache miss, which loads its snapshot. */
    private suspend fun ServiceNode.depositInto(id: String) =
        gateway.execute(deposit(id)).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)

    @Test
    fun `the third failed load in a row skips the store's loads, and its probe answering resumes them`(): Unit =
        runBlocking {
            val node = snapshotNode()
            val health = health(node)
            node.start()
            health.sample()
            snapshots.failing = true
            (1..5).forEach { node.depositInto("a-$it") }
            // Three loads failed in a row: the next two commands replay without calling the store.
            snapshots.loads.get().assert().isEqualTo(3)
            // The node's health reads the store's probe alone, which answered: no load made it degraded.
            health.health().component(NodeHealth.STORES)!!.component("store:$SNAPSHOTS")!!.status.assert()
                .isEqualTo(HealthStatus.UP)

            snapshots.failing = false
            health.sample()
            node.depositInto("a-6")
            snapshots.loads.get().assert().isEqualTo(4)
        }

    @Test
    fun `a load that answers between failures keeps the store's loads`(): Unit = runBlocking {
        val node = snapshotNode()
        node.start()
        snapshots.failing = true
        node.depositInto("a-1")
        node.depositInto("a-2")
        snapshots.failing = false
        node.depositInto("a-3")
        snapshots.failing = true
        node.depositInto("a-4")
        node.depositInto("a-5")
        // Two in a row since the answer: the store is still called.
        snapshots.loads.get().assert().isEqualTo(5)
        node.depositInto("a-6")
        node.depositInto("a-7")
        // The third in a row skips the next.
        snapshots.loads.get().assert().isEqualTo(6)
    }

    @Test
    fun `loads that time out count as failed ones, and a skipped load waits for none`(): Unit = runBlocking {
        val timeout = 500.milliseconds
        val node = snapshotNode(
            ServiceNodeOptions(
                cluster = fastNodeOptions().cluster,
                subscription = fastNodeOptions().subscription,
                snapshots = SnapshotOptions(loadTimeout = timeout),
            ),
        )
        node.start()
        snapshots.hanging = true
        (1..3).forEach { node.depositInto("a-$it") }
        snapshots.loads.get().assert().isEqualTo(3)
        val started = System.nanoTime()
        node.depositInto("a-4")
        val took = (System.nanoTime() - started) / 1_000_000
        snapshots.loads.get().assert().isEqualTo(3)
        // Skipped, it replays at once rather than waiting out the load's timeout.
        took.assert().isLessThan(timeout.inWholeMilliseconds)
    }

    @Test
    fun `the store's probe failing skips its loads, and the probe answering resumes them`(): Unit = runBlocking {
        val node = snapshotNode()
        val health = health(node)
        node.start()
        placed.down = true
        health.sample()
        node.depositInto("a-1")
        snapshots.loads.get().assert().isZero()

        placed.down = false
        health.sample()
        node.depositInto("a-2")
        snapshots.loads.get().assert().isEqualTo(1)
    }

    private companion object {
        const val SNAPSHOTS = "search"
    }
}

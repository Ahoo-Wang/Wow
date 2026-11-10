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
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.snapshot.SnapshotSubscription
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A coalescing subscription's batch that holds an entry of an aggregate parked meanwhile skips it, and the wait on
 * that entry fails with where the aggregate parked: the snapshot subscription, the default wait's hop, takes an
 * order's next entry into its lane while the write of the order's first fails and parks it.
 */
class BatchedParkedWaitTest {
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

    /** Completes once the tail has handed the order's version 2 to the workers: they take an entry inline. */
    private val taken = CompletableDeferred<Unit>()
    private val watched = object : EventLog by log {
        override fun tail(partition: Int, after: Long?): Flow<LogEntry> = log.tail(partition, after).transform {
            emit(it)
            if (it.stream.ref == ref && it.stream.version == 2) {
                taken.complete(Unit)
            }
        }
    }

    /** Completes when the first write of the order's snapshot begins, which fails once [release] completes. */
    private val writing = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private val store = object : SnapshotStore by InMemorySnapshotStore() {
        override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
            writing.complete(Unit)
            release.await()
            error("The snapshot store refuses ${snapshots.map { it.ref }}.")
        }
    }
    private val ledger = InMemoryFailureLedger()
    private val registry = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val signals = LocalSignalSender(origin.node, registry)
    private val kernel = Kernel(log)
    private val snapshots = SnapshotSubscription("shop", listOf(order), store, kernel, watched)
    private val runtime = SubscriptionRuntime(
        "shop",
        watched,
        InMemoryCheckpointStore(),
        ledger,
        listOf(snapshots.subscription),
        testOptions(maxAttempts = 1),
        signals = signals,
    )
    private val coordinator = WaitCoordinator(Executor(kernel), registry, runtime, signals, origin, WaitOptions())

    @AfterEach
    fun stop(): Unit = runBlocking {
        release.complete(Unit)
        runtime.stop()
    }

    @Test
    fun `an entry taken into the snapshot lane before its aggregate parks is skipped, and its wait fails parked`(): Unit =
        runBlocking {
            runtime.start(setOf(0))
            coordinator.execute(order, Command("c-1", "r-1", ref, Place(), wait = WaitPlan.COMMITTED))
            withTimeout(5.seconds) { writing.await() }
            // The order's next entry, waited for at the snapshot, while the write of its first is held.
            val waited =
                async { coordinator.execute(order, Command("c-2", "r-2", ref, Add(), wait = WaitPlan.SNAPSHOT)) }
            withTimeout(5.seconds) { taken.await() }
            // In the lane behind the first entry: its write fails now, and parks the order.
            release.complete(Unit)
            val result = withTimeout(5.seconds) { waited.await() }
            result.status.assert().isEqualTo(CommandResult.Status.FAILED)
            result.error!!.code.assert().isEqualTo(ResultCodes.SUBSCRIPTION_PARKED)
            result.error!!.message.assert()
                .isEqualTo("$ref@2 is parked by wow.snapshot; the failure ledger has the cause.")
            result.hop.assert().isEqualTo(Hop.handled(Hop.SNAPSHOT_SUBSCRIPTION, "shop"))
            ledger.parked(SubscriptionId("shop", Hop.SNAPSHOT_SUBSCRIPTION), ref)!!.fromVersion.assert().isEqualTo(1)
        }
}

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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.SnapshotLoadSkippedException
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.snapshot.SnapshotPlacement
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * The gate of one placed store's snapshot loads ([SkippingSnapshots]): which outcomes count against the store, and
 * that skipping one store's loads leaves another store's alone.
 */
class SkippingSnapshotsTest {
    /** A store whose loads run [load], counted. */
    private class Loads(private val load: suspend () -> SnapshotRecord?) : SnapshotStore by InMemorySnapshotStore() {
        val calls = AtomicInteger()

        override suspend fun load(ref: AggregateRef): SnapshotRecord? {
            calls.incrementAndGet()
            return load()
        }
    }

    private val ref = AggregateType("bank", "account").ref("t", "a-1")

    /** Loads of [store] through a gate on [activity], each cut at a short bound as the kernel's load is. */
    private suspend fun timedOut(store: SnapshotStore, activity: StoreActivity) {
        val gate = SkippingSnapshots(store, activity)
        withTimeoutOrNull(50.milliseconds) { gate.load(ref) }.assert().isNull()
    }

    @Test
    fun `a load cut at the kernel's bound counts, a blocking driver's as a suspending one's`(): Unit = runBlocking {
        // MongoDB's and PostgreSQL's loads block in runInterruptible; Elasticsearch's suspend on the client's answer.
        val blocking = StoreActivity(StoreActivity.STORE, "postgres")
        val hanging = Loads {
            runInterruptible(Dispatchers.IO) { Thread.sleep(10_000) }
            null
        }
        repeat(StoreActivity.LOADS_TO_SKIP) { timedOut(hanging, blocking) }
        blocking.loadsSkipped.assert().isTrue()

        val suspending = StoreActivity(StoreActivity.STORE, "elasticsearch")
        val awaiting = Loads { awaitCancellation() }
        repeat(StoreActivity.LOADS_TO_SKIP) { timedOut(awaiting, suspending) }
        suspending.loadsSkipped.assert().isTrue()
    }

    @Test
    fun `a caller's own cancellation is no failure of the store`(): Unit = runBlocking {
        val activity = StoreActivity(StoreActivity.STORE, "search")
        val gate = SkippingSnapshots(Loads { awaitCancellation() }, activity)
        repeat(StoreActivity.LOADS_TO_SKIP) {
            val load = async { gate.load(ref) }
            yield()
            load.cancel()
            load.join()
        }
        activity.loadsSkipped.assert().isFalse()
    }

    @Test
    fun `a skipped load does not call the store, and its failures leave the health's record as it was`(): Unit =
        runBlocking {
            val activity = StoreActivity(StoreActivity.STORE, "search")
            val store = Loads { error("The store does not answer.") }
            val gate = SkippingSnapshots(store, activity)
            repeat(StoreActivity.LOADS_TO_SKIP) { assertThrows<IllegalStateException> { gate.load(ref) } }
            assertThrows<SnapshotLoadSkippedException> { gate.load(ref) }
            store.calls.get().assert().isEqualTo(StoreActivity.LOADS_TO_SKIP)
            // The health reads the probe alone: no load failure is one of the store's.
            activity.failingSince.assert().isEqualTo(StoreActivity.NONE)
            activity.failuresWithin(kotlin.time.Duration.INFINITE).assert().isZero()
        }

    @Test
    fun `a healthy store's types keep loading while another store's are skipped`(): Unit = runBlocking {
        val account = AggregateType("bank", "account")
        val order = AggregateType("bank", "order")
        val healthy = Loads { null }
        val paused = Loads { null }
        val activities = listOf("main", "search").associateWith { StoreActivity(StoreActivity.STORE, it) }
        val targets = SnapshotTargets(
            "main",
            mapOf("main" to healthy, "search" to paused),
            mapOf(order to SnapshotPlacement("search")),
        ).withLoads { name, store -> SkippingSnapshots(store, activities.getValue(name)) }
        activities.getValue("search").probeFailed()

        targets.loads.load(account.ref("t", "a-1")).assert().isNull()
        assertThrows<SnapshotLoadSkippedException> { targets.loads.load(order.ref("t", "o-1")) }
        healthy.calls.get().assert().isEqualTo(1)
        paused.calls.get().assert().isZero()
        // The state reads and the subscriptions read the stores as they are.
        targets.primary.load(order.ref("t", "o-1")).assert().isNull()
        paused.calls.get().assert().isEqualTo(1)
    }
}

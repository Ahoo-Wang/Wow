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

package me.ahoo.wow.runtime.snapshot

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.RestoreAggregate
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.api.lifecycle.TransferSpace
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.SystemSubscriptions
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.subscription.eventually
import org.junit.jupiter.api.Test

/**
 * The shared state against the log and the store: written without reading either, its deletion, metadata and scope,
 * and without one, the snapshot and the log's tail giving the state.
 */
class SnapshotSharedStateTest : SnapshotSubscriptionFixtures() {
    private fun CommandOutcome.rejection(): String = (this as CommandOutcome.Rejected).rejection.code

    @Test
    fun `without a shared state the snapshot and the log's tail give the state`(): Unit = runBlocking {
        // Commands run outside the executor that records views: as on another node, or before an ownership change.
        val elsewhere = Executor(kernel)
        repeat(2) { elsewhere.execute(counter, add("a", WaitPlan.COMMITTED).copyWith(operator = ALICE)) }
        runtime.start(setOf(0))
        eventually { store.load(ref("a"))?.version == 2 }
        runtime.stop()
        repeat(3) { elsewhere.execute(counter, add("a", WaitPlan.COMMITTED).copyWith(operator = BOB)) }
        val fifth = log.read(ref("a"), 5, 5).toList().single()
        log.reads.clear()

        val other = SnapshotSubscription("shop", listOf(counter), store, kernel, log, codec)
        val resumed = runtime(InMemoryCheckpointStore().also { it.save("shop", "wow.snapshot", 0, 2) }, other)
        resumed.start(setOf(0))
        eventually { resumed.hasHandled(SystemSubscriptions.SNAPSHOT, fifth) }
        // Evolved from the stored snapshot, never replayed from the first stream.
        log.reads.assert().isNotEmpty()
        log.reads.none { it == 1 }.assert().isTrue()
        val snapshot = store.load(ref("a"))!!
        snapshot.version.assert().isEqualTo(5)
        snapshot.state.assert().isEqualTo("""{"total":5}""")
        snapshot.firstEventTime.assert().isEqualTo(log.read(ref("a"), 1, 1).toList().single().commitTime)
        // The first operator comes from the stored snapshot too.
        snapshot.operator.assert().isEqualTo(BOB)
        snapshot.firstOperator.assert().isEqualTo(ALICE)
    }

    @Test
    fun `a shared state is written without reading the log or the store`(): Unit = runBlocking {
        runtime.start(setOf(0))
        coordinator.execute(counter, add("a")).hop.assert().isEqualTo(Hop.snapshot("shop"))
        runtime.stop()
        // The view is recorded as the command commits, before the stopped runtime reads the entry.
        val committed = executor.execute(counter, add("a", WaitPlan.COMMITTED)) as CommandOutcome.Committed
        snapshots.views.get(ref("a"), 2).assert().isNotNull()
        log.reads.clear()
        val loads = store.loads.get()

        val resumed = runtime(InMemoryCheckpointStore().also { it.save("shop", "wow.snapshot", 0, 1) })
        resumed.start(setOf(0))
        eventually { resumed.hasHandled(SystemSubscriptions.SNAPSHOT, committed.stream) }
        store.loads.get().assert().isEqualTo(loads)
        log.reads.assert().isEmpty()
        // Its only reader passed it: the earlier view is evicted, the latest kept as the next commit's base.
        snapshots.views.get(ref("a"), 1).assert().isNull()
        snapshots.views.size.assert().isEqualTo(1)
        store.load(ref("a"))!!.lastEventTime.assert().isEqualTo(committed.stream.commitTime)
    }

    @Test
    fun `a deletion written from its shared state reloads as deleted until it is restored`(): Unit = runBlocking {
        runtime.start(setOf(0))
        coordinator.execute(counter, add("a")).hop.assert().isEqualTo(Hop.snapshot("shop"))
        runtime.stop()
        // The view is recorded as the deletion commits, before the stopped runtime reads the entry.
        val deleted = executor.execute(counter, command("a", DeleteAggregate())) as CommandOutcome.Committed
        snapshots.views.get(ref("a"), 2)!!.deleted.assert().isTrue()
        log.reads.clear()
        val loads = store.loads.get()

        val resumed = runtime(InMemoryCheckpointStore().also { it.save("shop", "wow.snapshot", 0, 1) })
        resumed.start(setOf(0))
        eventually { resumed.hasHandled(SystemSubscriptions.SNAPSHOT, deleted.stream) }
        // Written from the view: neither the store nor the log was read.
        store.loads.get().assert().isEqualTo(loads)
        log.reads.assert().isEmpty()
        val snapshot = store.load(ref("a"))!!
        snapshot.version.assert().isEqualTo(2)
        snapshot.deleted.assert().isTrue()

        // The kernel caches no state, as after an eviction: it reloads from the snapshot, whose tail is empty.
        executor.execute(
            counter,
            add("a", WaitPlan.COMMITTED)
        ).rejection().assert().isEqualTo(FrameworkCodes.AGGREGATE_DELETED)
        log.reads.assert().containsExactly(2)

        val restored = executor.execute(counter, command("a", RestoreAggregate())) as CommandOutcome.Committed
        eventually { resumed.hasHandled(SystemSubscriptions.SNAPSHOT, restored.stream) }
        store.load(ref("a"))!!.deleted.assert().isFalse()
        val added = executor.execute(counter, add("a", WaitPlan.COMMITTED)) as CommandOutcome.Committed
        added.stream.version.assert().isEqualTo(4)
    }

    @Test
    fun `a shared state a new subscription has not written before keeps the view's metadata`(): Unit = runBlocking {
        runtime.start(setOf(0))
        coordinator.execute(counter, add("a")).hop.assert().isEqualTo(Hop.snapshot("shop"))
        runtime.stop()
        Executor(kernel).execute(counter, command("a", TransferSpace("space-b")))
        // A subscription that has written nothing yet, as after a restart: its views are recorded before it runs.
        val other = SnapshotSubscription("shop", listOf(counter), store, kernel, log, codec)
        val deleted = Executor(kernel, views = other.views)
            .execute(counter, command("a", DeleteAggregate())) as CommandOutcome.Committed
        deleted.first.assert().isNotNull()
        log.reads.clear()
        val loads = store.loads.get()

        val resumed = runtime(InMemoryCheckpointStore().also { it.save("shop", "wow.snapshot", 0, 2) }, other)
        resumed.start(setOf(0))
        eventually { resumed.hasHandled(SystemSubscriptions.SNAPSHOT, deleted.stream) }
        // The view carries what the kernel loaded of the first stream (work package B.O-4): neither the stored
        // snapshot nor the log is read.
        store.loads.get().assert().isEqualTo(loads)
        log.reads.assert().isEmpty()
        other.views.size.assert().isEqualTo(1)
        val snapshot = store.load(ref("a"))!!
        snapshot.version.assert().isEqualTo(3)
        snapshot.deleted.assert().isTrue()
        snapshot.scope.spaceId.assert().isEqualTo("space-b")
        snapshot.firstEventTime.assert().isEqualTo(log.read(ref("a"), 1, 1).toList().single().commitTime)
    }

    @Test
    fun `the snapshot holds the scope the lifecycle commands gave`(): Unit = runBlocking {
        runtime.start(setOf(0))
        coordinator.execute(counter, add("a").copyWith(scope = Scope("alice", "space-a")))
        listOf(TransferOwner("bob"), TransferSpace("space-b")).forEach { body ->
            coordinator.execute(counter, command("a", body, WaitPlan.SNAPSHOT)).hop
                .assert().isEqualTo(Hop.snapshot("shop"))
            store.load(ref("a"))!!.scope.assert().isEqualTo(log.read(ref("a")).toList().last().scope)
        }
        val snapshot = store.load(ref("a"))!!
        snapshot.version.assert().isEqualTo(3)
        snapshot.scope.assert().isEqualTo(Scope("bob", "space-b"))
        snapshot.deleted.assert().isFalse()

        // Reloaded from the snapshot alone, the aggregate has them: applying them again changes nothing.
        log.reads.clear()
        executor.execute(counter, command("a", TransferOwner("bob"))).assert().isEqualTo(CommandOutcome.Unchanged(3))
        executor.execute(counter, command("a", TransferSpace("space-b")))
            .assert().isEqualTo(CommandOutcome.Unchanged(3))
        log.reads.assert().containsExactly(3, 3)
    }

    @Test
    fun `without a shared state the snapshot holds the deleted mark and the scope`(): Unit = runBlocking {
        // Commands run outside the executor that records views: as on another node, or before an ownership change.
        val elsewhere = Executor(kernel)
        elsewhere.execute(counter, command("a", Add(), scope = Scope("alice", "space-a")))
        listOf(TransferOwner("bob"), DeleteAggregate()).forEach {
            elsewhere.execute(counter, command("a", it)).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        }
        // No snapshot is stored yet: the log is replayed.
        runtime.start(setOf(0))
        eventually { store.load(ref("a"))?.version == 3 }
        runtime.stop()
        val deleted = store.load(ref("a"))!!
        deleted.deleted.assert().isTrue()
        deleted.scope.assert().isEqualTo(Scope("bob", "space-a"))

        log.reads.clear()
        elsewhere.execute(
            counter,
            add("a", WaitPlan.COMMITTED)
        ).rejection().assert().isEqualTo(FrameworkCodes.AGGREGATE_DELETED)
        log.reads.assert().containsExactly(3)

        // The restore is written from the stored snapshot evolved with the log's tail.
        val restored = elsewhere.execute(counter, command("a", RestoreAggregate())) as CommandOutcome.Committed
        log.reads.clear()
        val other = SnapshotSubscription("shop", listOf(counter), store, kernel, log, codec)
        val resumed = runtime(InMemoryCheckpointStore().also { it.save("shop", "wow.snapshot", 0, 3) }, other)
        resumed.start(setOf(0))
        eventually { resumed.hasHandled(SystemSubscriptions.SNAPSHOT, restored.stream) }
        log.reads.assert().isNotEmpty()
        log.reads.none { it == 1 }.assert().isTrue()
        val snapshot = store.load(ref("a"))!!
        snapshot.version.assert().isEqualTo(4)
        snapshot.deleted.assert().isFalse()
        snapshot.scope.assert().isEqualTo(Scope("bob", "space-a"))
    }
}

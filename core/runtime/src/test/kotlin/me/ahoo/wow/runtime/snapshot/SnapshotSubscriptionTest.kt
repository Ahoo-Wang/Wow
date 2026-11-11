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

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The built-in snapshot subscription (design 03 §3, roadmap 4.6) and the default wait it completes (D69).
 */
class SnapshotSubscriptionTest : SnapshotSubscriptionFixtures() {
    @Test
    fun `the default wait answers once the snapshot holds the commit`(): Unit = runBlocking {
        runtime.start(setOf(0))
        val owned = Scope("alice", "space")
        val first = coordinator.execute(counter, add("a").copyWith(scope = owned, operator = ALICE))
        first.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        first.hop.assert().isEqualTo(Hop.snapshot("shop"))
        val created = store.load(ref("a"))!!
        created.version.assert().isEqualTo(1)
        created.state.assert().isEqualTo("""{"total":1}""")
        created.scope.assert().isEqualTo(owned)
        created.schemaFingerprint.assert().isEqualTo(codec.stateSchemaFingerprint(counter))
        created.operator.assert().isEqualTo(ALICE)
        created.firstOperator.assert().isEqualTo(ALICE)

        coordinator.execute(counter, add("a").copyWith(operator = BOB)).hop.assert().isEqualTo(Hop.snapshot("shop"))
        val updated = store.load(ref("a"))!!
        updated.version.assert().isEqualTo(2)
        updated.state.assert().isEqualTo("""{"total":2}""")
        // The scope is the stream's, carried forward from the creating command (D56).
        updated.scope.assert().isEqualTo(owned)
        val streams = log.read(ref("a")).toList()
        updated.firstEventTime.assert().isEqualTo(streams[0].commitTime)
        updated.lastEventTime.assert().isEqualTo(streams[1].commitTime)
        // The last stream's operator, and the creator's (decision D163).
        updated.operator.assert().isEqualTo(BOB)
        updated.firstOperator.assert().isEqualTo(ALICE)
        coordinator.execute(counter, add("a")).hop.assert().isEqualTo(Hop.snapshot("shop"))
        store.load(ref("a"))!!.run {
            operator.assert().isNull()
            firstOperator.assert().isEqualTo(ALICE)
        }
    }

    @Test
    fun `an aggregate with snapshots disabled waits for COMMITTED by default`(): Unit = runBlocking {
        runtime.start(setOf(0))
        val ref = note.type.ref("t", "n")
        val result = coordinator.execute(note, Command("c-note", "r-note", ref, Write("hi")))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.assert().isEqualTo(Hop.committed("shop"))
        eventually { probed.contains("n" to 1) }
        store.load(ref).assert().isNull()
        store.saves.assert().isEmpty()
        snapshots.views.size.assert().isEqualTo(0)
    }

    @Test
    fun `a replay never moves a snapshot back, nor rebuilds what the store holds`(): Unit = runBlocking {
        runtime.start(setOf(0))
        repeat(3) { coordinator.execute(counter, add("a")) }
        store.load(ref("a"))!!.version.assert().isEqualTo(3)
        runtime.stop()
        val saved = store.saves.size
        val third = log.read(ref("a"), 3, 3).toList().single()
        log.reads.clear()

        // A new checkpoint replays the partition from the beginning.
        val replay = runtime(InMemoryCheckpointStore())
        replay.start(setOf(0))
        eventually { replay.hasHandled(Hop.SNAPSHOT_SUBSCRIPTION, third) }
        store.saves.size.assert().isEqualTo(saved)
        store.load(ref("a"))!!.version.assert().isEqualTo(3)
        // No state was rebuilt from the log.
        log.reads.assert().isEmpty()
    }

    @Test
    fun `a shared state recorded after its entry was written is never written again`(): Unit = runBlocking {
        runtime.start(setOf(0))
        repeat(2) { coordinator.execute(counter, add("a")) }
        // Version 3's append returns only once the subscription has written it without a view: the executor records
        // the view of version 3 afterwards, as when the subscription wins the race to the entry.
        log.afterAppend = { stream -> eventually { store.load(stream.ref)?.version == stream.version } }
        // The subscription is done with version 3 only once its save returns (finding F17).
        store.afterSave = { delay(200) }
        val third = executor.execute(counter, add("a", WaitPlan.COMMITTED)) as CommandOutcome.Committed
        log.afterAppend = null
        third.stream.version.assert().isEqualTo(3)
        snapshots.views.get(ref("a"), 3).assert().isNotNull()
        // Version 2's view stays until the subscription passes version 3, once its save returned: then only the
        // late view of version 3 is held.
        eventually { snapshots.views.size == 1 }
        store.afterSave = null
        runtime.stop()
        val saved = store.saves.size

        // A replay finds the late view of a version the store already holds: passed, never saved again. It stays only
        // as the base of the aggregate's next commit.
        val replay = runtime(InMemoryCheckpointStore())
        replay.start(setOf(0))
        eventually { replay.hasHandled(Hop.SNAPSHOT_SUBSCRIPTION, third.stream) }
        store.saves.size.assert().isEqualTo(saved)
        snapshots.views.size.assert().isEqualTo(1)
        store.load(ref("a"))!!.state.assert().isEqualTo("""{"total":3}""")
    }

    @Test
    fun `a duplicate request is answered from the snapshot subscription's checkpoint`(): Unit = runBlocking {
        runtime.start(setOf(0))
        coordinator.execute(counter, add("a", requestId = "same")).hop.assert().isEqualTo(Hop.snapshot("shop"))
        val again = coordinator.execute(counter, add("a", requestId = "same"))
        again.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        again.duplicate.assert().isTrue()
        again.hop.assert().isEqualTo(Hop.snapshot("shop"))
    }

    @Test
    fun `a shared state that fails to serialize never fails the committed command`(): Unit = runBlocking {
        val subscription = SnapshotSubscription("shop", listOf(fragile), store, kernel, log, codec)
        val executor = Executor(kernel, views = subscription.views)
        val ref = fragile.type.ref("t", "f")
        fun add() = sequence.incrementAndGet().let { Command("c-$it", "r-$it", ref, Add(), wait = WaitPlan.COMMITTED) }

        executor.execute(fragile, add()).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        subscription.views.size.assert().isEqualTo(1)
        // Version 2 cannot be serialized: still committed, without a view; the view of version 1 stays, right for it.
        val second = executor.execute(fragile, add()) as CommandOutcome.Committed
        second.stream.version.assert().isEqualTo(2)
        subscription.views.get(ref, 2).assert().isNull()
        subscription.views.size.assert().isEqualTo(1)

        val ledger = InMemoryFailureLedger()
        val running = SubscriptionRuntime(
            "shop",
            log,
            InMemoryCheckpointStore(),
            ledger,
            listOf(subscription.subscription),
            testOptions(),
        ).also { runtimes += it }
        running.start(setOf(0))
        // The subscription rebuilds the state instead; version 2 fails to serialize there too, so the aggregate
        // parks: from version 2, or from 1 when both entries ran as one batch. Nothing past version 1 is written.
        val snapshot = SubscriptionId(ref.type.context, Hop.SNAPSHOT_SUBSCRIPTION)
        eventually { ledger.isParked(snapshot, ref) }
        (ledger.parked(snapshot, ref)!!.fromVersion in 1..2).assert().isTrue()
        (store.load(ref)?.version ?: 1).assert().isEqualTo(1)
    }

    @Test
    fun `a snapshot subscription names the aggregate types it snapshots, all of its context`() {
        runtime.covers(Hop.SNAPSHOT_SUBSCRIPTION, counter.type).assert().isTrue()
        runtime.covers(Hop.SNAPSHOT_SUBSCRIPTION, note.type).assert().isFalse()
        snapshots.subscription.name.assert().isEqualTo(Hop.SNAPSHOT_SUBSCRIPTION)
        assertThrows<IllegalArgumentException> {
            SnapshotSubscription("other", listOf(counter), store, kernel, log)
        }
        assertThrows<IllegalArgumentException> {
            SnapshotSubscription("shop", emptyList(), store, kernel, log)
        }
    }
}

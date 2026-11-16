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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.sink.SinkWrites
import me.ahoo.wow.runtime.snapshot.SnapshotSaveAbandoned
import me.ahoo.wow.runtime.snapshot.SnapshotWrites
import me.ahoo.wow.runtime.source.orderRecord
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test

/**
 * The lead's hand-over shared by the snapshot and the sink writes: a request withdrawn, in whichever state, is never
 * handed the lead.
 *
 * Every test runs on [runTest]'s one thread: a coroutine started undispatched runs until it suspends, and [runCurrent]
 * runs every coroutine ready until all wait. The store and the sink suspend only at [Harness.gate]. That is what makes
 * each exact batch and count below certain: the leader and the follower are one batch, held in the write, when the
 * test cancels them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LeaderBatchesTest {
    private class Harness(val kind: String, committers: Int = 1) {
        val entered = mutableListOf<List<String>>()
        val durable = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        private val backing = InMemorySnapshotStore()
        private val type = AggregateType("shop", "counter")
        private val store = object : SnapshotStore by backing {
            override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
                entered += snapshots.map { it.ref.id }
                gate.await()
                return backing.saveAll(snapshots).also { durable += snapshots.map { it.ref.id } }
            }
        }
        val snapshots = SnapshotWrites(store, maxBatch = 8, committers = committers)
        private val sink = SinkWrites(
            Sink { records ->
                entered += records.map { it.ref.id }
                gate.await()
                durable += records.map { it.ref.id }
            },
            8,
        )
        val waiting: Int get() = if (kind == SINK) sink.waiting else snapshots.waiting

        suspend fun write(id: String, partition: Int? = null) {
            if (kind == SINK) {
                val written = sink.write(listOf(orderRecord(id, 1)))
                written.done.assert().isEqualTo(1)
                written.failure.assert().isNull()
            } else {
                snapshots.save(snapshot(id), partition).assert().isTrue()
                backing.load(type.ref("t", id)).assert().isNotNull()
            }
        }

        fun snapshot(id: String) =
            SnapshotRecord(type.ref("t", id), 1, "f", "{}", firstEventTime = 0, lastEventTime = 0)
    }

    /** A leader and a follower taken into one batch, held in the write; the follower then, or not, the leader cancelled. */
    private fun TestScope.batchOfTwo(harness: Harness, partition: Int? = null) = listOf("leader", "follower").map {
        // Each caught, so a save that fails fails only its own deferred, not the test.
        async(start = CoroutineStart.UNDISPATCHED) { runCatching { harness.write(it, partition) } }
    }.also {
        runCurrent()
        harness.entered.assert().containsExactly(listOf("leader", "follower"))
        harness.waiting.assert().isEqualTo(0)
    }

    private fun cancelled(kind: String, committers: Int = 1, cancelFollower: Boolean = true) = runTest {
        val harness = Harness(kind, committers)
        val (leader, follower) = batchOfTwo(harness)
        if (cancelFollower) follower.cancelAndJoin()
        leader.cancelAndJoin()
        harness.gate.complete(Unit)
        // A surviving follower leads its own save again; a withdrawn one leads nothing, so the next save leads.
        val probe = async(start = CoroutineStart.UNDISPATCHED) { harness.write("probe") }
        runCurrent()
        probe.isCompleted.assert().isTrue()
        probe.await()
        harness.durable.assert().contains("probe")
        if (cancelFollower) harness.durable.assert().doesNotContain("follower") else follower.await().getOrThrow()
        harness.waiting.assert().isEqualTo(0)
    }

    @Test
    fun `a sink run taken into a batch and cancelled before its leader is not handed the lead`() =
        cancelled(SINK)

    @Test
    fun `a snapshot save taken into a batch and cancelled before its leader is not handed the lead`() =
        cancelled(SNAPSHOT)

    @Test
    fun `a cancelled snapshot save holds no leader's place when more may lead`() =
        cancelled(SNAPSHOT, committers = 4)

    @Test
    fun `a sink run still waiting when its leader is cancelled leads its write again`() =
        cancelled(SINK, cancelFollower = false)

    @Test
    fun `a snapshot save still waiting when its leader is cancelled leads its write again`() =
        cancelled(SNAPSHOT, cancelFollower = false)

    @Test
    fun `a snapshot save its leader would put back while its partition stops fails, and the next save leads`() =
        runTest {
            val harness = Harness(SNAPSHOT)
            val (leader, follower) = batchOfTwo(harness, partition = 0)
            harness.snapshots.abandon(setOf(0))
            leader.cancelAndJoin()
            runCurrent()
            follower.isCompleted.assert().isTrue()
            follower.await().exceptionOrNull().assert()
                .isInstanceOf(SnapshotSaveAbandoned::class.java)
            harness.waiting.assert().isEqualTo(0)
            harness.gate.complete(Unit)
            val probe = async(start = CoroutineStart.UNDISPATCHED) { harness.write("probe", partition = 1) }
            runCurrent()
            probe.isCompleted.assert().isTrue()
            probe.await()
            harness.durable.assert().containsExactly("probe")
        }

    private companion object {
        const val SINK = "sink"
        const val SNAPSHOT = "snapshot"
    }
}

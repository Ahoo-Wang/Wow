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

package me.ahoo.wow.runtime.node

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.snapshot.SnapshotPlacement
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A wait's `queryable` hop: the commit is in the store the aggregate type's snapshot queries read, answered by the
 * subscription of its copy where it has one, while `snapshot` is answered by its own store's; the same as `snapshot`
 * where its queries read its own store.
 */
class QueryableWaitTest : ServiceNodeFixtures() {
    /** While open, the copy's writes wait for it. */
    private val writing = CompletableDeferred<Unit>()

    private val own = InMemorySnapshotStore()
    private val copied = InMemorySnapshotStore()
    private val copy = object : SnapshotStore by copied {
        override suspend fun save(snapshot: SnapshotRecord): Boolean {
            writing.await()
            return copied.save(snapshot)
        }

        override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> {
            writing.await()
            return copied.saveAll(snapshots)
        }
    }

    @AfterEach
    fun open() {
        writing.complete(Unit)
    }

    private fun backends(placement: SnapshotPlacement): BackendFactory {
        val inMemory = InMemoryBackends()
        val targets = SnapshotTargets("own", mapOf("own" to own, "copy" to copy), fallback = placement)
        return BackendFactory { inMemory.backends(it).copyWith(snapshots = targets) }
    }

    @Test
    fun `a queryable wait should end once the copy the queries read holds the commit, after the snapshot wait`(): Unit =
        runBlocking {
            val node = node("node-1", backends = backends(SnapshotPlacement("own", copy = "copy")))
            node.start()

            // The type's own store holds it: the snapshot wait ends, while the copy's writes are held.
            val snapshot = node.gateway.execute(deposit(id = "a-1", wait = WaitPlan.SNAPSHOT.within(5.seconds)))
            snapshot.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            val queryable = async(Dispatchers.Default) {
                node.gateway.execute(deposit(id = "a-1", wait = WaitPlan.of(Hop.queryable()).within(5.seconds)))
            }
            delay(200.milliseconds)
            queryable.isCompleted.assert().isFalse()

            writing.complete(Unit)
            queryable.await().let {
                it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
                it.hop.assert().isEqualTo(Hop.queryable(BANK))
            }
            copied.load(snapshot.ref).assert().isNotNull()
        }

    @Test
    fun `a queryable wait should end as the snapshot wait does where the queries read the type's own store`(): Unit =
        runBlocking {
            val node = node("node-1", backends = backends(SnapshotPlacement("own")))
            node.start()

            node.gateway.execute(deposit(id = "a-1", wait = WaitPlan.of(Hop.queryable()).within(5.seconds))).let {
                it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
                it.hop.assert().isEqualTo(Hop.queryable(BANK))
            }
            own.load(AggregateType(BANK, "account").ref(TENANT, "a-1")).assert().isNotNull()
        }
}

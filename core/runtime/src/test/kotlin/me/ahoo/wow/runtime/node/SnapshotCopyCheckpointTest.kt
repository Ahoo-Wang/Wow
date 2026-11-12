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
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.snapshot.SnapshotPlacement
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The snapshot write checkpoint of a type whose queries read its copy (D423, MS-2): its copy's subscription's, which
 * writes the store a count of its snapshots is taken on, not its own store's, which may be ahead of it.
 */
class SnapshotCopyCheckpointTest : ServiceNodeFixtures() {
    private val account = AggregateType(BANK, "account")

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

    @Test
    fun `a type's snapshot checkpoint should be its copy's subscription's, behind its own store's while the copy lags`():
        Unit = runBlocking {
        val inMemory = InMemoryBackends()
        val targets = SnapshotTargets(
            "own",
            mapOf("own" to own, "copy" to copy),
            fallback = SnapshotPlacement("own", "copy")
        )
        val node = node("node-1", backends = BackendFactory { inMemory.backends(it).copyWith(snapshots = targets) })
        node.start()
        val bank = node.context(BANK)
        val group = bank.logGroups.first { account in it.types }
        suspend fun progressOf(subscription: String) =
            group.subscriptions.progress(subscription, group.log.partitions) ?: 0L

        node.gateway.execute(deposit(id = "a-1"))
        eventually { own.load(account.ref(TENANT, "a-1")) != null && progressOf("wow.snapshot") > 0 }

        // The own store's subscription moved; the copy's, its writes held, did not: a count of the copy is that old.
        (bank.snapshotCheckpoint(account) ?: 0L).assert().isEqualTo(progressOf("wow.snapshot:copy"))
        (bank.snapshotCheckpoint(account) ?: 0L).assert().isLessThan(progressOf("wow.snapshot"))

        writing.complete(Unit)
        eventually { (bank.snapshotCheckpoint(account) ?: 0L) >= progressOf("wow.snapshot") }
        copied.load(account.ref(TENANT, "a-1")).assert().isNotNull()
    }
}

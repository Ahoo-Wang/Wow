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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.snapshot.SnapshotPlacement
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.spi.StorageRefusedException
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotSaveOutcome
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * A snapshot the aggregate type's only snapshot store can never take, refused as a value
 * ([StorageRefusedException]), as every store refuses one: the aggregate is parked at once, not retried. Its commands
 * still commit, their default waits end failed with where it parked, and it has no newer snapshot, while the other
 * aggregates of its type go on.
 */
class SnapshotRefusalTest : ServiceNodeFixtures() {
    private val stored = InMemorySnapshotStore()

    /** How many times the store was asked to write the refused aggregate. */
    private val attempts = AtomicInteger()

    private val store = object : SnapshotStore by stored {
        override suspend fun save(snapshot: SnapshotRecord): Boolean {
            if (snapshot.ref.id == REFUSED) {
                attempts.incrementAndGet()
                throw StorageRefusedException("The store refused ${snapshot.ref}.")
            }
            return stored.save(snapshot)
        }

        override suspend fun saveAll(snapshots: List<SnapshotRecord>): List<SnapshotSaveOutcome> =
            snapshots.map { snapshot ->
                if (snapshot.ref.id == REFUSED) {
                    attempts.incrementAndGet()
                    SnapshotSaveOutcome.Failed(StorageRefusedException("The store refused ${snapshot.ref}."))
                } else {
                    SnapshotSaveOutcome.of(stored.save(snapshot))
                }
            }
    }

    private fun backends(): BackendFactory {
        val inMemory = InMemoryBackends()
        val targets = SnapshotTargets("only", mapOf("only" to store), fallback = SnapshotPlacement("only"))
        return BackendFactory { inMemory.backends(it).copyWith(snapshots = targets) }
    }

    @Test
    fun `a snapshot the only store refuses should park its aggregate at once, its commands still committing`(): Unit =
        runBlocking {
            val node = node("node-1", backends = backends())
            node.start()
            val wait = WaitPlan.SNAPSHOT.within(5.seconds)

            // 1. The refused aggregate commits; its default wait ends failed, where it parked, without retries.
            node.gateway.execute(deposit(id = REFUSED, wait = wait)).let {
                it.status.assert().isEqualTo(CommandResult.Status.FAILED)
                it.error!!.code.assert().isEqualTo(FrameworkCodes.SUBSCRIPTION_PARKED)
                it.version.assert().isEqualTo(1)
            }
            attempts.get().assert().isEqualTo(1)

            // 2. Its next command commits too, and its wait fails the same way: the aggregate stays parked.
            node.gateway.execute(deposit(id = REFUSED, wait = wait)).let {
                it.status.assert().isEqualTo(CommandResult.Status.FAILED)
                it.error!!.code.assert().isEqualTo(FrameworkCodes.SUBSCRIPTION_PARKED)
                it.version.assert().isEqualTo(2)
            }
            stored.load(AggregateType(BANK, "account").ref(TENANT, REFUSED)).assert().isNull()

            // 3. The other aggregates of its type go on.
            node.gateway.execute(deposit(id = "a-ok", wait = wait)).status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
            stored.load(AggregateType(BANK, "account").ref(TENANT, "a-ok")).assert().isNotNull()
        }

    private companion object {
        const val REFUSED = "a-refused"
    }
}

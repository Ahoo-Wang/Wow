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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.EventLog
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * The node's sampler reads the heads of a log group's partitions in one call of its log ([EventLog.heads]), not one
 * read a partition: on a store that answers each read with a round trip, an idle node's lag sampling costs one a group.
 */
class NodeSamplerHeadsTest : NodeHealthFixtures() {
    /** A log that records each `heads` call, by the partitions it asked for. */
    private class HeadsLog(private val delegate: EventLog) : EventLog by delegate {
        val calls = CopyOnWriteArrayList<Collection<Int>>()

        override suspend fun heads(partitions: Collection<Int>): Map<Int, Long?> {
            calls += partitions
            return delegate.heads(partitions)
        }
    }

    @Test
    fun `a sample reads each log group's heads in one call, for every partition the group has positions in`(): Unit =
        runBlocking {
            val counted = HeadsLog(InMemoryEventLog(PARTITIONS))
            val node = node(
                backends = BackendFactory {
                    ContextBackends(
                        log = counted,
                        checkpoints = InMemoryCheckpointStore(),
                        leases = leases,
                        ledger = InMemoryFailureLedger(),
                        snapshots = SnapshotTargets.single(InMemorySnapshotStore()),
                    )
                },
            )
            node.start()
            eventually { node.context(BANK).ownership.owned.size == PARTITIONS }
            val sampler = NodeSampler(node, 10.seconds, readTimeout = 1.seconds)
            val positions = node.context(BANK).logGroups.single().subscriptions.positions().map { it.partition }.toSet()
            counted.calls.clear()
            val sample = sampler.sample()
            counted.calls.map { it.toSet() }.assert().containsExactly(positions)
            sample.heads.keys.map { it.second }.toSet().assert().isEqualTo(positions)
            sample.heads.values.assert().allMatch { it == 0L }
            sampler.close()
        }
}

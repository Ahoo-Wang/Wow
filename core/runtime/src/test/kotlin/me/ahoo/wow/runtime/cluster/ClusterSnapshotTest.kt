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

package me.ahoo.wow.runtime.cluster

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** The default wait, SNAPSHOT, across two nodes: the owner writes the snapshot and signals the node waiting. */
class ClusterSnapshotTest {
    private val snapshots = InMemorySnapshotStore()

    /**
     * The test asserts that the owner decides the forwarded command, so the forward must not time out and degrade to
     * running here (D71): the first forward of a cold JVM (class loading, the codec's first use) under CPU load took
     * longer than the fixtures' one second. The command's own wait, ten seconds, still caps it. Nor may a stall of
     * the JVM expire the leases and leave the partition without a known owner ([FROZEN_LEASES]).
     */
    private val cluster = TestCluster(
        options = fastOptions(forwardTimeout = 10.seconds),
        snapshots = snapshots,
        leaseClock = FROZEN_LEASES,
    )

    @AfterEach
    fun close(): Unit = runBlocking {
        cluster.nodes.values.forEach { it.kill() }
        cluster.close()
    }

    @Test
    fun `a forwarded command's default wait completes when the owner's snapshot holds it`(): Unit = runBlocking {
        val (one, two) = cluster.start("node-1", "node-2")
        cluster.awaitStable()
        val names = cluster.running.map { it.name }
        val id = (0 until 64).map { "acc-$it" }.first { Hrw.owner(cluster.partitionOf(it), names) == two.name }

        val first = one.deposit(id, wait = WaitPlan.SNAPSHOT)
        first.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        first.hop.assert().isEqualTo(Hop.snapshot(CONTEXT))
        first.version.assert().isEqualTo(1)
        two.decided.assert().contains(first.commandId)
        one.decided.assert().doesNotContain(first.commandId)
        // The owner's decide function put a result: it came back with the forward's reply, and the snapshot's hop,
        // signalled from the owner, answers it (D49).
        first.results.assert().isEqualTo(mapOf(DECIDED_BY to two.name))
        // Answered after the write: the caller reads its own write.
        snapshots.load(TestNode.ref(id))!!.version.assert().isEqualTo(1)

        val second = one.deposit(id, wait = WaitPlan.SNAPSHOT)
        second.hop.assert().isEqualTo(Hop.snapshot(CONTEXT))
        val snapshot = snapshots.load(TestNode.ref(id))!!
        snapshot.version.assert().isEqualTo(2)
        snapshot.state.assert().isEqualTo("""{"balance":2}""")
    }
}

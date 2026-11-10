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

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.TestTimeSource

/** Starting and stopping an ownership's partitions on their own, with the gates map they alone write. */
class PartitionRunsTest {
    private val time = TestTimeSource()
    private val options = ClusterOptions()
    private val gates = ConcurrentHashMap<Int, PartitionGate>()
    private val runtime = mockk<SubscriptionRuntime>(relaxed = true)
    private val intake = mockk<SubscriptionRuntime>(relaxed = true)
    private val changed = CopyOnWriteArrayList<Int>()
    private val leases = InMemoryLeaseStore()
    private val runs = PartitionRuns(
        GROUP,
        NODE,
        leases,
        options,
        time,
        options.leaseTtl - options.renewInterval,
        gates,
        listOf(runtime),
        intake,
        emptyList(),
    ) { changed += it }

    @Test
    fun `a partition no gate holds is not handed off`(): Unit = runBlocking {
        runs.handOff(listOf(0))
        coVerify(exactly = 0) { runtime.stopPartition(any()) }
        coVerify(exactly = 0) { intake.stopPartition(any()) }
        changed.assert().isEmpty()
    }

    @Test
    fun `a partition whose lease was lost is stopped whole, its states dropped, and its gate removed`(): Unit =
        runBlocking {
            gates[0] = PartitionGate(time.markNow(), options.leaseTtl).also {
                it.started()
                it.lost = true
            }
            runs.dropLost()
            coVerify { intake.stopPartition(0) }
            coVerify { runtime.stopPartition(0) }
            changed.assert().containsExactly(0)
            gates.assert().isEmpty()
        }

    private companion object {
        const val NODE = "node-1"
        val GROUP = LeaseGroup("running-service", "running")
    }
}

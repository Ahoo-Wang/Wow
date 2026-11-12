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

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.timer.TimerRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
        partitionChanged = { changed += it },
    )

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

    @Test
    fun `a lease taken while the ownership is fenced is lost at once, never renewed nor opened`(): Unit = runBlocking {
        val steps = CopyOnWriteArrayList<Int>()
        val fenced = PartitionRuns(
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
            partitionChanged = { changed += it },
            progressed = { steps += 0 },
            fenced = { true },
        )
        fenced.acquire(0, null)
        val gate = gates.getValue(0)
        gate.lost.assert().isTrue()
        gate.open.assert().isFalse()
        // The acquisition is a step of the reconcile all the same.
        steps.assert().hasSize(1)
        // Once the reconcile moves again, it is dropped as any lost partition.
        fenced.dropLost()
        gates.assert().isEmpty()
        steps.assert().hasSize(2)
    }

    @Test
    fun `a start cancelled from within is stopped, released and forgotten, then the cancellation goes on up`(): Unit =
        runBlocking {
            // #5879: the cancellation was rethrown before the failed start's way out, and its gate stayed, never open.
            coEvery { runtime.startPartition(0, any()) } throws CancellationException("The runtime's scope is gone.")
            assertThrows<CancellationException> { runs.acquire(0, null) }
            gates.assert().isEmpty()
            leases.owners(GROUP.name).assert().isEmpty()
            coVerify { runtime.stopPartition(0) }
        }

    @Test
    fun `a start failing at a timer runner stops the intake and the runners that had started`(): Unit =
        runBlocking {
            val first = mockk<TimerRunner>(relaxed = true)
            val second = mockk<TimerRunner>(relaxed = true)
            coEvery { second.startPartition(0) } throws IllegalStateException("The timer store is gone.")
            val timed = PartitionRuns(
                GROUP,
                NODE,
                leases,
                options,
                time,
                options.leaseTtl - options.renewInterval,
                gates,
                listOf(runtime),
                intake,
                listOf(first, second),
                partitionChanged = { changed += it },
            )
            timed.acquire(0, null)
            coVerify { intake.startPartition(0, any()) }
            coVerify { first.startPartition(0) }
            // Nothing of the partition runs once its lease is released: not the intake, nor the runner that started.
            coVerify { first.stopPartition(0) }
            coVerify { second.stopPartition(0) }
            coVerify { intake.stopPartition(0) }
            coVerify { runtime.stopPartition(0) }
            gates.assert().isEmpty()
            leases.owners(GROUP.name).assert().isEmpty()
        }

    private companion object {
        const val NODE = "node-1"
        val GROUP = LeaseGroup("running-service", "running")
    }
}

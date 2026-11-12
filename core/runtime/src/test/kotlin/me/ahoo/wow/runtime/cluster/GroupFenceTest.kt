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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.timer.TimerRunner
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** The fence of a lease group on its own, on virtual time: when it falls, what it closes, and how it recovers. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupFenceTest {
    private val options = ClusterOptions()
    private val gates = ConcurrentHashMap<Int, PartitionGate>()
    private val membership = mockk<ServiceMembership>(relaxed = true)
    private val events = CopyOnWriteArrayList<ObservedEvent>()
    private val observer = object : Observer {
        override fun observed(event: ObservedEvent) {
            events += event
        }
    }
    private var woken = 0

    private fun TestScope.fence(timers: List<TimerRunner> = emptyList()) = GroupFence(
        GROUP,
        NODE,
        options,
        testScheduler.timeSource,
        gates,
        membership,
        timers,
        observer,
    ) { woken++ }

    private fun TestScope.openGate(partition: Int): PartitionGate =
        PartitionGate(testScheduler.timeSource.markNow(), options.leaseTtl).also {
            it.started()
            gates[partition] = it
        }

    @Test
    fun `a reconcile that finished no step for the reconcile timeout is fenced once, and recovers when it ends`(): Unit =
        runTest {
            val fence = fence()
            val gate = openGate(0)
            fence.reconcileStarted()
            delay(options.reconcileTimeout)
            fence.check(backgroundScope)
            fence.fenced.assert().isFalse()

            delay(1.seconds)
            fence.check(backgroundScope)
            fence.fenced.assert().isTrue()
            gate.open.assert().isFalse()
            runCurrent()
            gate.lost.assert().isTrue()
            coVerify(exactly = 1) { membership.stuck(GROUP.name, true) }
            // Checked again while still stuck: fenced once.
            fence.check(backgroundScope)
            events.filterIsInstance<ObservedEvent.OwnershipFenced>().assert().hasSize(1)

            fence.reconcileEnded()
            delay(5.seconds)
            fence.check(backgroundScope)
            fence.fenced.assert().isFalse()
            coVerify(exactly = 1) { membership.stuck(GROUP.name, false) }
            events.filterIsInstance<ObservedEvent.OwnershipRecovered>().single().fencedMillis.assert().isEqualTo(5_000L)
            woken.assert().isEqualTo(1)
        }

    @Test
    fun `a reconcile that finishes steps is not fenced, and a step outside a reconcile counts for none`(): Unit = runTest {
        val fence = fence()
        fence.stepFinished()
        delay(options.reconcileTimeout * 2)
        fence.check(backgroundScope)
        fence.fenced.assert().isFalse()

        fence.reconcileStarted()
        repeat(3) {
            delay(options.reconcileTimeout - 1.seconds)
            fence.stepFinished()
            fence.check(backgroundScope)
        }
        fence.fenced.assert().isFalse()
        events.assert().isEmpty()
    }

    @Test
    fun `without an upkeep scope the gates are lost at once`(): Unit = runTest {
        val fence = fence()
        val gate = openGate(0)
        fence.reconcileStarted()
        delay(options.reconcileTimeout + 1.seconds)
        fence.check(null)
        gate.lost.assert().isTrue()
        gate.open.assert().isFalse()
    }

    @Test
    fun `a lease is kept while its commands are in flight, and given up past the drain timeout all the same`(): Unit =
        runTest {
            val fence = fence()
            val gate = openGate(0)
            gate.inFlight.increment()
            fence.reconcileStarted()
            delay(options.reconcileTimeout + 1.seconds)
            fence.check(backgroundScope)
            gate.open.assert().isFalse()
            delay(options.drainTimeout - 1.seconds)
            gate.lost.assert().isFalse()
            delay(2.seconds)
            gate.lost.assert().isTrue()
        }

    @Test
    fun `the timers are stopped, a stop that hangs given up past the drain timeout`(): Unit = runTest {
        val stopped = mockk<TimerRunner>(relaxed = true)
        val hanging = mockk<TimerRunner>(relaxed = true)
        val failing = mockk<TimerRunner>(relaxed = true)
        coEvery { failing.stopPartition(any()) } throws IllegalStateException("The timer store is gone.")
        coEvery { hanging.stopPartition(any()) } coAnswers { awaitCancellation() }
        val fence = fence(listOf(stopped, failing, hanging))
        openGate(0)
        fence.reconcileStarted()
        delay(options.reconcileTimeout + 1.seconds)
        fence.check(backgroundScope)
        runCurrent()
        coVerify { stopped.stopPartition(0) }
        coVerify { failing.stopPartition(0) }
        coVerify { hanging.stopPartition(0) }
        delay(options.drainTimeout + 1.seconds)
        gates.getValue(0).lost.assert().isTrue()
    }

    @Test
    fun `a fence that falls while a partition starts sees its gate, and the start that ends after it opens nothing`(): Unit =
        runTest {
            // The race of the review: the gate is put before the fence is asked, so the fence's walk sees it.
            val starting = CompletableDeferred<Unit>()
            val runtime = mockk<SubscriptionRuntime>(relaxed = true)
            coEvery { runtime.startPartition(0, any()) } coAnswers { starting.await() }
            val leases = InMemoryLeaseStore()
            lateinit var fence: GroupFence
            val runs = PartitionRuns(
                GROUP,
                NODE,
                leases,
                options,
                testScheduler.timeSource,
                options.leaseTtl - options.renewInterval,
                gates,
                listOf(runtime),
                null,
                emptyList(),
                partitionChanged = {},
                progressed = { fence.stepFinished() },
                fenced = { fence.fenced },
            )
            fence = fence()
            fence.reconcileStarted()
            val acquiring = backgroundScope.async { runs.acquire(0, null) }
            runCurrent()
            val gate = gates.getValue(0)
            gate.starting.assert().isTrue()

            // The fence falls while the start hangs: the gate it walks is closed and lost.
            delay(options.reconcileTimeout + 1.seconds)
            fence.check(backgroundScope)
            runCurrent()
            gate.lost.assert().isTrue()

            // The start ends after the fence: the gate stays closed.
            starting.complete(Unit)
            acquiring.await()
            gate.starting.assert().isFalse()
            gate.open.assert().isFalse()
        }

    private companion object {
        const val NODE = "node-1"
        val GROUP = LeaseGroup("fenced-service", "fenced")
    }
}

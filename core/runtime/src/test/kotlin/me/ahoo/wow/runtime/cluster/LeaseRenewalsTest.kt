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
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** The renewals of an ownership's leases on their own, against a store that misbehaves at a chosen moment of a round. */
class LeaseRenewalsTest {
    private val time = TestTimeSource()
    private val options = ClusterOptions()
    private val limit = options.leaseTtl - options.renewInterval
    private val gates = ConcurrentHashMap<Int, PartitionGate>()
    private val rounds = CopyOnWriteArrayList<RenewalRound>()
    private var woken = 0

    private fun renewals(leases: LeaseStore, hook: (RenewalRound) -> Unit = { rounds += it }) =
        LeaseRenewals(GROUP, NODE, leases, options, time, gates, limit, hook) { woken++ }

    private fun held(partition: Int): PartitionGate =
        PartitionGate(time.markNow(), limit).also { it.started() }.also { gates[partition] = it }

    @Test
    fun `a lease that goes overdue while its round's renewal fails is lost in that round`(): Unit = runBlocking {
        val gate = held(0)
        val leases = mockk<LeaseStore> {
            coEvery { renew(any(), any(), any(), any()) } answers {
                // The store hangs past the margin, then fails: the lease may have expired meanwhile.
                time += limit + 1.seconds
                error("The lease store is unreachable.")
            }
        }
        renewals(leases).renew()
        gate.lost.assert().isTrue()
        gate.open.assert().isFalse()
        rounds.single().lost.assert().isEqualTo(1)
        rounds.single().failed.assert().isEqualTo(0)
        woken.assert().isEqualTo(1)
    }

    @Test
    fun `a partition handed off while its renewal runs is not lost`(): Unit = runBlocking {
        val gate = held(0)
        val leases = mockk<LeaseStore> {
            coEvery { renew(any(), any(), any(), any()) } answers {
                // Handed off and released meanwhile: the store no longer gives its lease back.
                gates.remove(0)
                emptyList()
            }
        }
        renewals(leases).renew()
        gate.lost.assert().isFalse()
        rounds.single().lost.assert().isEqualTo(0)
        woken.assert().isEqualTo(0)
    }

    @Test
    fun `a round's hook cancelled is not taken for a failure of the hook`(): Unit = runBlocking {
        held(0)
        val leases = mockk<LeaseStore> { coEvery { renew(any(), any(), any(), any()) } returns emptyList() }
        assertThrows<CancellationException> {
            renewals(leases) { throw CancellationException("The node stops.") }.renew()
        }
    }

    private companion object {
        const val NODE = "node-1"
        val GROUP = LeaseGroup("renewing-service", "renewing")
    }
}

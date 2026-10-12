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

package me.ahoo.wow.leases

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.kernel.InMemoryLeaseStore
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.PartitionOwnership
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Spike H6, the renewal round in isolation: one node holds every partition of an in-memory lease store whose `renew`
 * takes [RENEW_LATENCY], far below the lease TTL. `PartitionOwnership` renews its leases one after another, so a round
 * takes `owned × latency`; once that exceeds `leaseTtl − renewInterval` the last leases of the round expire before
 * they are renewed, and the node drops partitions nobody else wants. No other node, no load, no database.
 *
 * Runs with the spike (`-Dwow.spike.h6=true`); prints the renewals that found their lease lost.
 */
class RenewalRoundSpike {
    @Test
    fun `a slow renew loses leases once a round outlasts the TTL margin`() {
        assumeTrue(
            System.getProperty("wow.spike.h6") == "true" || System.getProperty("wow.spike.h6.quick") == "true",
            "Run with -Dwow.spike.h6=true",
        )
        runBlocking {
            for ((partitions, latency) in CASES) {
                val store = SlowRenewals(InMemoryLeaseStore(), latency)
                val ownership = PartitionOwnership(
                    LeaseGroup(SERVICE, "renewal"),
                    "only-node",
                    "only-node.internal:7070",
                    partitions,
                    store,
                    ClusterOptions(),
                )
                ownership.start()
                delay(OBSERVE)
                val held = ownership.owned.size
                ownership.stop()
                val round = latency * partitions
                LeaseSpike.log(
                    "renewal round: P=$partitions, renew ${latency.inWholeMilliseconds} ms, round $round " +
                        "(margin ${ClusterOptions().leaseTtl - ClusterOptions().renewInterval}): " +
                        "${store.lost.get()} renewals found the lease lost in $OBSERVE; held $held/$partitions at the end",
                )
            }
        }
    }

    /** A lease store whose renewals take [latency] and that counts those finding the lease lost. */
    private class SlowRenewals(
        private val delegate: LeaseStore,
        private val latency: Duration
    ) : LeaseStore by delegate {
        val lost = AtomicInteger()

        override suspend fun renew(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
            delay(latency)
            return delegate.renew(group, partition, owner, ttl).also {
                if (it == null) {
                    lost.incrementAndGet()
                }
            }
        }
    }

    private companion object {
        val OBSERVE = 30.seconds

        /** (owned partitions, renew latency): rounds of 3.2 s and 6.4 s stay inside the 7 s margin, 9.6 s does not. */
        val CASES = listOf(64 to 50.milliseconds, 64 to 100.milliseconds, 64 to 150.milliseconds)
    }
}

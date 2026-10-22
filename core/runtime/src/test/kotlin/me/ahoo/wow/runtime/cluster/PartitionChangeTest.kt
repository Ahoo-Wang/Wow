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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.LruStateCache
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Decision D261, C4: a node drops the cached states of a partition whenever it acquires, hands off or loses it, so the
 * recent requests a state carries are never behind the log while the node owns the partition.
 */
@OptIn(ExperimentalCoroutinesApi::class) // The scheduler's time source times the renewals.
class PartitionChangeTest {
    @Test
    fun `each partition acquired, then handed off, is reported both times`(): Unit = runTest(timeout = 1.minutes) {
        val changed = CopyOnWriteArrayList<Int>()
        val ownership = ownership(InMemoryLeaseStore(virtualClock()), changed)
        ownership.start()
        delay(1.seconds)
        changed.toSet().assert().isEqualTo((0 until PARTITIONS).toSet())
        changed.size.assert().isEqualTo(PARTITIONS)
        ownership.stop()
        changed.groupingBy { it }.eachCount().values.toSet().assert().containsExactly(2)
    }

    @Test
    fun `a partition whose lease is lost is reported once it stops`(): Unit = runTest(timeout = 1.minutes) {
        val delegate = InMemoryLeaseStore(virtualClock())
        var down = false
        val store = object : LeaseStore by delegate {
            override suspend fun renew(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
                check(!down) { "The lease store is unreachable." }
                return delegate.renew(group, partition, owner, ttl)
            }

            /** Through [renew], so that it fails too (decision D270: a round renews with one call). */
            override suspend fun renewAll(
                group: String,
                partitions: Collection<Int>,
                owner: String,
                ttl: Duration,
            ): List<Lease> = super.renewAll(group, partitions, owner, ttl)

            override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
                check(!down) { "The lease store is unreachable." }
                return delegate.acquire(group, partition, owner, ttl)
            }
        }
        val changed = CopyOnWriteArrayList<Int>()
        val ownership = ownership(store, changed)
        try {
            ownership.start()
            delay(1.seconds)
            changed.clear()
            down = true
            delay(ClusterOptions().leaseTtl + 2.seconds)
            ownership.owned.assert().isEmpty()
            changed.toSet().assert().isEqualTo((0 until PARTITIONS).toSet())
        } finally {
            down = false
            ownership.stop()
        }
    }

    @Test
    fun `the state cache drops the states of one partition`() {
        val type = AggregateType("ctx", "agg")
        val refs = (0 until 64).map { type.ref("t", "a-$it") }
        val cache = LruStateCache(capacity = 4_096)
        refs.forEach { cache.put(it, CachedState(it.id, 1)) }
        cache.invalidate(1, PARTITIONS)
        val (dropped, kept) = refs.partition { Partitioner.partitionOf(it, PARTITIONS) == 1 }
        dropped.assert().isNotEmpty()
        dropped.forEach { cache.take(it).assert().isNull() }
        kept.forEach { cache.take(it).assert().isNotNull() }
    }

    private fun TestScope.ownership(store: LeaseStore, changed: MutableList<Int>) = PartitionOwnership(
        GROUP,
        NODE,
        { "$NODE.internal:7070" },
        PARTITIONS,
        store,
        context = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
        partitionChanged = { changed += it },
    )

    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
    }

    private companion object {
        const val NODE = "node-1"
        const val PARTITIONS = 4
        val GROUP = LeaseGroup("changing-service", "changing")
    }
}

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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.LogGroupBackends
import me.ahoo.wow.runtime.subscription.Appender
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.log.readSnapshotTailApart
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What a node records of its stores' calls (review R3.5-3). */
class StoreActivityTest {
    private var now = 0L
    private val nanos = { now }

    @Test
    fun `failures should be counted within their window and a success should end the outage`() {
        val activity = StoreActivity(StoreActivity.LOG, "bank", nanos)
        activity.lastSuccess.assert().isEqualTo(StoreActivity.NONE)
        activity.failingSince.assert().isEqualTo(StoreActivity.NONE)
        now = 10
        activity.failed(StorageUnavailableException("down"))
        now = 20
        activity.failed(IllegalStateException("down"))
        activity.failingSince.assert().isEqualTo(10L)
        activity.lastFailure.assert().isEqualTo(20L)
        activity.lastError.assert().isEqualTo("IllegalStateException")
        activity.failuresWithin(1.minutes).assert().isEqualTo(2)
        now = 30
        activity.succeeded()
        activity.failingSince.assert().isEqualTo(StoreActivity.NONE)
        activity.lastSuccess.assert().isEqualTo(30L)
        now = 20 + 2.minutes.inWholeNanoseconds
        activity.failuresWithin(1.minutes).assert().isEqualTo(0)
        repeat(StoreActivity.MAX_RECENT + 10) { activity.failed(StorageUnavailableException("down")) }
        activity.failuresWithin(1.minutes).assert().isEqualTo(StoreActivity.MAX_RECENT)
        activity.probeStarted()
        activity.probing.assert().isEqualTo(now)
        activity.probeEnded()
        activity.probing.assert().isEqualTo(StoreActivity.NONE)
        activity.toString().assert().isEqualTo("StoreActivity(log, bank)")
    }

    @Test
    fun `a recorded call should record its success, or its failure, which it throws again`(): Unit = runBlocking {
        val activity = StoreActivity(StoreActivity.STORE, "main")
        activity.recorded { }
        activity.lastSuccess.assert().isNotEqualTo(StoreActivity.NONE)
        assertThrows<IllegalStateException> { runBlocking { activity.recorded { error("down") } } }
        activity.failingSince.assert().isNotEqualTo(StoreActivity.NONE)
        activity.lastError.assert().isEqualTo("IllegalStateException")
    }

    @Test
    fun `a tracked read with the snapshot should be recorded as the log's`(): Unit = runBlocking {
        val memory = InMemoryEventLog(2)
        val cart = me.ahoo.wow.runtime.subscription.cart("c-1")
        val reader = me.ahoo.wow.spi.log.SnapshotTailReader { ref, earlier ->
            memory.readSnapshotTailApart(ref, InMemorySnapshotStore(), earlier)
        }
        val untracked = NodeStores.track(
            "bank",
            ContextBackends(memory, InMemoryCheckpointStore(), InMemoryLeaseStore(), InMemoryFailureLedger()),
        )
        untracked.snapshotTails.of(cart.type).assert().isNull()
        val backends = NodeStores.track(
            "bank",
            ContextBackends(
                memory,
                InMemoryCheckpointStore(),
                InMemoryLeaseStore(),
                InMemoryFailureLedger(),
                snapshotTails = me.ahoo.wow.runtime.node.SnapshotTailReaders(reader),
            ),
        )
        val activity = activityOf(backends.log)!!
        backends.snapshotTails.of(cart.type)!!.readWithSnapshot(cart, 1).tail.streams.assert().isEmpty()
        activity.lastSuccess.assert().isNotEqualTo(StoreActivity.NONE)
    }

    @Test
    fun `a tracked log should record its reads, appends and outages, and only the store's`(): Unit = runBlocking {
        val memory = InMemoryEventLog(2)
        val other = InMemoryEventLog(2)
        val backends = NodeStores.track(
            "bank",
            ContextBackends(
                memory,
                InMemoryCheckpointStore(),
                InMemoryLeaseStore(),
                InMemoryFailureLedger(),
                groups = listOf(
                    LogGroupBackends(
                        "archive",
                        setOf(AggregateType("bank", "archive")),
                        other,
                        InMemoryCheckpointStore(),
                        InMemoryFailureLedger(),
                    ),
                ),
            ),
        )
        activityOf(backends.groups.single().log)!!.name.assert().isEqualTo("bank#archive")
        activityOf(backends.leases)!!.kind.assert().isEqualTo(StoreActivity.LEASES)
        activityOf(memory).assert().isNull()
        activityOf(InMemoryLeaseStore()).assert().isNull()
        val log = backends.log
        val activity = activityOf(log)!!
        activity.name.assert().isEqualTo("bank")
        log.toString().assert().isEqualTo(memory.toString())
        log.partitions.assert().isEqualTo(2)

        val appender = Appender(log)
        appender.append("c-1")
        val success = activity.lastSuccess
        success.assert().isNotEqualTo(StoreActivity.NONE)
        val ref = me.ahoo.wow.runtime.subscription.cart("c-1")
        log.read(ref).toList().size.assert().isEqualTo(1)
        log.readEntries(ref).toList().size.assert().isEqualTo(1)
        log.readTail(ref, 1, 1).streams.size.assert().isEqualTo(1)
        log.tail(me.ahoo.wow.spi.log.Partitioner.partitionOf(ref, 2)).let { tail ->
            kotlinx.coroutines.withTimeoutOrNull(100) { tail.collect { } }
        }
        log.positionOf(ref, 1).assert().isNotNull()
        log.head(0)
        log.lineage(0)
        log.findByRequestId(ref.type, "t", "missing").assert().isNull()
        log.appendAll(emptyList()).assert().isEmpty()
        activity.failingSince.assert().isEqualTo(StoreActivity.NONE)

        // A defect is not an outage; an unavailable store is.
        val failing = trackedLog(
            object : me.ahoo.wow.spi.log.EventLog by memory {
                override suspend fun head(partition: Int): Long? = throw IllegalArgumentException("defect")

                override fun tail(partition: Int, after: Long?) = kotlinx.coroutines.flow.flow<me.ahoo.wow.spi.log.LogEntry> {
                    throw StorageUnavailableException("down")
                }
            },
            activity,
        )
        assertThrows<IllegalArgumentException> { runBlocking { failing.head(0) } }
        activity.failingSince.assert().isEqualTo(StoreActivity.NONE)
        assertThrows<StorageUnavailableException> { runBlocking { failing.tail(0).toList() } }
        activity.failingSince.assert().isNotEqualTo(StoreActivity.NONE)
        activity.lastError.assert().isEqualTo("StorageUnavailableException")
    }

    @Test
    fun `a tracked lease store should record every call, any failure an outage`(): Unit = runBlocking {
        val activity = StoreActivity(StoreActivity.LEASES, "bank")
        val memory = InMemoryLeaseStore()
        val leases = trackedLeases(memory, activity)
        leases.toString().assert().isEqualTo(memory.toString())
        leases.heartbeat("g", "n", "a:1", 10.seconds)
        leases.members("g").size.assert().isEqualTo(1)
        leases.acquire("g", 0, "n", 10.seconds).assert().isNotNull()
        leases.renew("g", listOf(0), "n", 10.seconds).size.assert().isEqualTo(1)
        leases.owners("g").size.assert().isEqualTo(1)
        leases.release("g", 0, "n").assert().isTrue()
        leases.leave("g", "n")
        activity.lastSuccess.assert().isNotEqualTo(StoreActivity.NONE)
        val broken = trackedLeases(
            object : me.ahoo.wow.spi.subscription.LeaseStore by memory {
                override suspend fun members(group: String) = throw IllegalStateException("refused")
            },
            activity,
        )
        assertThrows<IllegalStateException> { runBlocking { broken.members("g") } }
        activity.failingSince.assert().isNotEqualTo(StoreActivity.NONE)
    }
}

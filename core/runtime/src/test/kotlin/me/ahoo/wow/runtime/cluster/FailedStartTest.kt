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
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Review R2.1-8 on virtual time: a start of [PartitionOwnership] that fails half-way, the lease store briefly
 * unreachable, undoes what it did, so the ownership can be started again instead of failing its `running == null`
 * check with its runtime left running.
 */
@OptIn(ExperimentalCoroutinesApi::class) // The scheduler's time source times the renewals.
class FailedStartTest {
    private val store = InMemoryLeaseStore()
    private val leases = FlakyLeases(store)

    @Test
    fun `a start whose heartbeat fails should stop the runtime it started and start again`(): Unit =
        runTest(timeout = 1.minutes) {
            val runtime = runtime()
            val ownership = ownership(runtime)
            leases.heartbeats.set(1)
            assertThrows<IllegalStateException> { ownership.start() }
            ownership.isRunning.assert().isFalse()
            runtime.isRunning.assert().isFalse()
            store.members(GROUP.service).assert().isEmpty()

            ownership.start()
            try {
                runtime.isRunning.assert().isTrue()
                ownership.owned.assert().isEqualTo((0 until PARTITIONS).toSet())
            } finally {
                ownership.stop()
            }
        }

    @Test
    fun `a start whose read of the members fails should leave the members and start again`(): Unit =
        runTest(timeout = 1.minutes) {
            val runtime = runtime()
            val ownership = ownership(runtime)
            // The membership heartbeats, then reads the members (finding R5.B-4): the read fails.
            leases.memberReads.set(1)
            assertThrows<IllegalStateException> { ownership.start() }
            ownership.isRunning.assert().isFalse()
            runtime.isRunning.assert().isFalse()
            // It had heartbeated: it left the members again.
            store.members(GROUP.service).assert().isEmpty()
            delay(5.seconds)
            store.members(GROUP.service).assert().isEmpty()

            ownership.start()
            try {
                ownership.owned.assert().isEqualTo((0 until PARTITIONS).toSet())
            } finally {
                ownership.stop()
            }
        }

    @Test
    fun `a start whose acquisition fails should release the partitions it acquired`(): Unit =
        runTest(timeout = 1.minutes) {
            val runtime = runtime()
            val ownership = ownership(runtime)
            leases.acquisitions.set(1)
            assertThrows<IllegalStateException> { ownership.start() }
            ownership.isRunning.assert().isFalse()
            ownership.owned.assert().isEmpty()
            store.owners(GROUP.name).assert().isEmpty()
            runtime.isRunning.assert().isFalse()

            ownership.start()
            try {
                ownership.owned.assert().isEqualTo((0 until PARTITIONS).toSet())
            } finally {
                ownership.stop()
            }
        }

    @Test
    fun `a failed start should leave a runtime already running to its owner`(): Unit = runTest(timeout = 1.minutes) {
        val runtime = runtime()
        runtime.start()
        try {
            val ownership = ownership(runtime)
            leases.heartbeats.set(1)
            assertThrows<IllegalStateException> { ownership.start() }
            runtime.isRunning.assert().isTrue()
        } finally {
            runtime.stop()
        }
    }

    private fun TestScope.runtime() = SubscriptionRuntime(
        GROUP.name,
        InMemoryEventLog(PARTITIONS),
        InMemoryCheckpointStore(),
        InMemoryFailureLedger(),
        context = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.ownership(runtime: SubscriptionRuntime) = PartitionOwnership(
        GROUP,
        NODE,
        "$NODE.internal:7070",
        PARTITIONS,
        leases,
        subscriptions = runtime,
        context = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
    )

    private companion object {
        const val NODE = "node-1"
        const val PARTITIONS = 4
        val GROUP = LeaseGroup("failing-service", "failing")
    }
}

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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The step-out of a node that met a fatal error (X-80, [PartitionOwnership.stepOut]): for good, bounded, and whatever
 * the hand-off comes to, renewing no lease, so the other node takes every partition, on expiry at worst.
 */
@OptIn(ExperimentalCoroutinesApi::class) // The scheduler's time source times the leases.
class StepOutTest {
    @Test
    fun `a step-out hands every partition to the other node, for good, and twice does nothing`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            val fatal = ownership(FATAL, store)
            val other = ownership(OTHER, store)
            try {
                fatal.start()
                other.start()
                delay(5.seconds)
                fatal.owned.assert().isNotEmpty()

                fatal.stepOut(BOUND)
                fatal.stepOut(BOUND)
                delay(5.seconds)
                other.owned.assert().isEqualTo((0 until PARTITIONS).toSet())
                fatal.owned.assert().isEmpty()
                members(store).assert().containsExactly(OTHER)
                // It reconciles no more: nothing is acquired back.
                fatal.reconcile()
                fatal.owned.assert().isEmpty()
            } finally {
                other.stop()
                fatal.stop()
            }
        }

    @Test
    fun `an ownership that stepped out before it started does not start`(): Unit = runTest(timeout = 1.minutes) {
        val ownership = ownership(FATAL, InMemoryLeaseStore(virtualClock()))
        try {
            ownership.stepOut(BOUND)
            runCatching { ownership.start() }.exceptionOrNull()?.message.assert().contains("stepped out")
            ownership.isRunning.assert().isFalse()
        } finally {
            // Nothing once a stop has run; ends the ownership's loops on the test's scheduler when it did not.
            ownership.abort()
        }
    }

    @Test
    fun `a hand-off that outlasts its bound renews no lease, so the other node takes them once they expire`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            // No release of the fatal node answers: its hand-off cannot end.
            val hanging = HangingReleases(store)
            val fatal = ownership(FATAL, hanging)
            val other = ownership(OTHER, store)
            try {
                fatal.start()
                other.start()
                delay(5.seconds)
                val held = fatal.owned
                held.assert().isNotEmpty()

                hanging.hangs = true
                fatal.stepOut(BOUND)
                // Past the bound and the leases' time to live: the other node owns them, the fatal one renewed none.
                delay(1.minutes)
                other.owned.assert().isEqualTo((0 until PARTITIONS).toSet())
                fatal.owned.assert().isEmpty()
            } finally {
                hanging.hangs = false
                hanging.release.complete(Unit)
                other.stop()
                fatal.stop()
            }
        }

    @Test
    fun `a step-out goes on without a lifecycle a stuck start holds, and that start backs out`(): Unit =
        runTest(timeout = 1.minutes) {
            val store = InMemoryLeaseStore(virtualClock())
            // The first heartbeat of the fatal node's join does not answer until released.
            val joining = HangingHeartbeat(store)
            val fatal = ownership(FATAL, joining)
            try {
                val starting = async { runCatching { fatal.start() } }
                delay(1.seconds)

                fatal.stepOut(BOUND)
                joining.release.complete(Unit)
                starting.await().exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
                fatal.isRunning.assert().isFalse()
                members(store).assert().isEmpty()
            } finally {
                // A start still waiting on the heartbeat holds the lifecycle: released first, so the abort can take it.
                joining.release.complete(Unit)
                // Nothing once a stop has run; ends the ownership's loops on the test's scheduler when it did not.
                fatal.abort()
            }
        }

    private fun TestScope.ownership(node: String, leases: LeaseStore) = PartitionOwnership(
        GROUP,
        node,
        "$node.internal:7070",
        PARTITIONS,
        leases,
        context = StandardTestDispatcher(testScheduler),
        timeSource = testScheduler.timeSource,
    )

    private suspend fun members(store: LeaseStore): List<String> =
        store.members(GROUP.service).filter { GROUP.name in it.groups }.map { it.node }

    /** The store's clock follows the test's virtual time. */
    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(testScheduler.currentTime)
    }

    /** A release that waits for [release] while [hangs]. */
    private class HangingReleases(private val delegate: LeaseStore) : LeaseStore by delegate {
        @Volatile
        var hangs = false
        val release = CompletableDeferred<Unit>()

        override suspend fun release(group: String, partition: Int, owner: String): Boolean {
            if (hangs) release.await()
            return delegate.release(group, partition, owner)
        }
    }

    /** The first heartbeat waits for [release]; the others answer at once. */
    private class HangingHeartbeat(private val delegate: LeaseStore) : LeaseStore by delegate {
        val release = CompletableDeferred<Unit>()

        override suspend fun heartbeat(
            service: String,
            node: String,
            address: String,
            ttl: Duration,
            groups: Set<String>,
        ): Member {
            release.await()
            return delegate.heartbeat(service, node, address, ttl, groups)
        }
    }

    private companion object {
        val GROUP = LeaseGroup("svc", "orders")
        const val PARTITIONS = 8
        const val FATAL = "fatal"
        const val OTHER = "other"
        val BOUND = 10.seconds
    }
}

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

package me.ahoo.wow.runtime.access

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.state.ReplayGate
import me.ahoo.wow.spi.StorageBusyException
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.AccessStamped.Cause
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A walk under the writes' pressure: one aggregate read and stamped after another, each read under the replay gate,
 * giving way while the writes press and never stopping for them, and the lease renewed within a long page.
 */
class AccessWalkPressureTest : AccessWalkingFixtures() {
    /** A gate that counts the walk's reads by aggregate id, refuses the first [refusals] and says whether [pressed]. */
    private class Gate(var refusals: Int = 0, val pressed: Boolean = false) : ReplayGate {
        val replayed = CopyOnWriteArrayList<String>()

        override suspend fun <T> replay(ref: AggregateRef, read: suspend () -> T): T {
            if (refusals > 0) {
                refusals--
                // As a query bulkhead refuses: its own error, the store's refusal as the cause.
                throw IllegalStateException(
                    "Retry later.",
                    StorageBusyException("The log store runs as many queries as it allows at once."),
                )
            }
            replayed += ref.id
            return read()
        }

        override fun pressed(type: AggregateType): Boolean = pressed
    }

    private fun gated(log: EventLog, gate: ReplayGate, options: AccessStampOptions = this.options) = AccessWalking(
        "office",
        "service/office",
        "n-1",
        listOf(model),
        Kernel(log),
        { log },
        InMemoryLeaseStore(),
        options,
        observer = observer,
        gate = gate,
    )

    @Test
    fun `a walk reads and stamps one aggregate after another, each in statements of its own, never a page at once`(): Unit =
        runBlocking {
            val log = Recording(InMemoryEventLog())
            unstamped(log.log, listOf("d-1", "d-2", "d-3"), "sales", "legal")
            walkingOf(log).maintain().assert().isFalse()
            log.calls.assert().containsExactly(
                "read d-1",
                "stamp d-1",
                "read d-2",
                "stamp d-2",
                "read d-3",
                "stamp d-3",
            )
            listOf(
                "d-1",
                "d-2",
                "d-3"
            ).forEach { history(log.log, it).assert().containsExactly(access("legal"), access("legal")) }
        }

    @Test
    fun `a walk reads each history under the replay gate, and gives way while the writes press`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1", "d-2"), "sales")
        val gate = Gate(pressed = true)
        val walking = gated(log, gate)
        walking.share(type).assert().isNull()
        walking.maintain().assert().isFalse()
        gate.replayed.assert().containsExactly("d-1", "d-2")
        // Sensed once in the run's first second: halved.
        walking.share(type).assert().isEqualTo(AccessStampOptions.DEFAULT_WALK_SHARE / 2)
        listOf("d-1", "d-2").forEach { history(log, it).assert().containsExactly(access("sales")) }
        // The follower's catch-up reads its history directly.
        walking.stampCurrent(model, type.ref("tenant", "d-1"), Cause.CATCH_UP)
        gate.replayed.assert().containsExactly("d-1", "d-2")
    }

    @Test
    fun `a read the gate refuses presses the walk, which reads that aggregate again and goes on`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1", "d-2"), "sales")
        val gate = Gate(refusals = 1)
        val walking = gated(log, gate, AccessStampOptions(walkBatch = 2, walkRate = 1_000_000, walkShare = 0.8))
        walking.maintain().assert().isFalse()
        gate.replayed.assert().containsExactly("d-1", "d-2")
        log.walk(type)!!.let {
            it.completed.assert().isTrue()
            it.walked.assert().isEqualTo(2)
        }
        // Halved by the refusal; the next second's calm sense adds back an eighth.
        walking.share(type)!!.assert().isLessThan(0.8)
        listOf("d-1", "d-2").forEach { history(log, it).assert().containsExactly(access("sales")) }
    }

    @Test
    fun `a failure of the gate that is no refusal fails the run`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1"), "sales")
        val broken = object : ReplayGate {
            override suspend fun <T> replay(ref: AggregateRef, read: suspend () -> T): T = error("The log is down.")
        }
        assertThrows<IllegalStateException> { gated(log, broken).maintain() }
        log.walk(type)!!.walked.assert().isEqualTo(0)
    }

    @Test
    fun `a walk ends however long the writes press, at an eighth of its share`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        val ids = (1..6).map { "d-$it" }
        unstamped(log, ids, "sales")
        val walking =
            gated(log, Gate(pressed = true), AccessStampOptions(walkBatch = 2, walkRate = 1_000_000, walkShare = 1.0))
        withTimeout(10.seconds) {
            while (log.walk(type)?.completed != true) walking.maintain()
        }
        walking.share(type)!!.assert().isBetween(1.0 / 8, 0.5)
        ids.forEach { history(log, it).assert().containsExactly(access("sales")) }
    }

    @Test
    fun `a page that takes longer than a third of the lease renews the lease within it`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1", "d-2", "d-3"), "sales")
        val leases = InMemoryLeaseStore()
        // The aggregates the walk had stamped at each renewal of its lease.
        val renewals = CopyOnWriteArrayList<Int>()
        val renewing = object : LeaseStore by leases {
            override suspend fun renew(group: String, partitions: Collection<Int>, owner: String, ttl: Duration) =
                leases.renew(group, partitions, owner, ttl).also {
                    renewals += observed.count { it is ObservedEvent.AccessStamped && it.cause == Cause.WALK }
                }
        }
        // 10 aggregates a second, a page of three: a third of the 240 ms lease passes within the page.
        val options = AccessStampOptions(
            walkBatch = 3,
            walkRate = 10,
            interval = 200.milliseconds,
            leaseTtl = 240.milliseconds,
        )
        val walking = walkingOf(log, options = options, leases = renewing)
        withTimeout(10.seconds) {
            while (walking.maintain()) continue
        }
        log.walk(type)!!.completed.assert().isTrue()
        // Pages of one and two end at one and three aggregates: a renewal at two is within the page.
        renewals.assert().contains(2)
    }
}

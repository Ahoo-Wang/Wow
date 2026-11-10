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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.source.ORDER
import me.ahoo.wow.runtime.source.TopicEntries
import me.ahoo.wow.runtime.source.order
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The park states a run of a topic's subscription runtime starts its partitions with, moved entries re-homed. */
class ParkedAtStartTest {
    private val topics = InMemoryTopics(PARTITIONS).also { it.create(TOPIC, PARTITIONS) }
    private val source = TopicEntries(TOPIC, topics, PARTITIONS)
    private val ledger = InMemoryFailureLedger()
    private val listings = AtomicInteger()
    private val failing = AtomicBoolean()

    /** While set, the next listing waits here after it began, until it is completed. */
    private val hold = AtomicReference<CompletableDeferred<Unit>?>()
    private val listing = CompletableDeferred<Unit>()
    private val counted = object : FailureLedger by ledger {
        override suspend fun list(
            subscription: SubscriptionId,
            after: AggregateRef?,
            limit: Int,
            tenantId: String?,
        ): List<ParkedAggregate> {
            listings.incrementAndGet()
            hold.getAndSet(null)?.let {
                listing.complete(Unit)
                it.await()
            }
            check(!failing.getAndSet(false)) { "the ledger is unavailable" }
            return ledger.list(subscription, after, limit, tenantId)
        }
    }

    /** The run's scope, which owns the listings. */
    private val run = CoroutineScope(SupervisorJob())

    @AfterEach
    fun stop() {
        run.cancel()
    }
    private val subscription = Subscription(NAME, SubscriptionFilter(ORDER.context, setOf(ORDER.name))) { }

    /** An order whose key maps to [partition] now. */
    private suspend fun orderIn(partition: Int): AggregateRef = generateSequence(0) { it + 1 }
        .map { order("o-$it") }.first { source.partitionOf(it) == partition }

    @Test
    fun `an entry parked in another partition than its key's is re-homed where the key maps, listed once a run`(): Unit =
        runBlocking {
            val moved = orderIn(1)
            val stays = orderIn(0)
            ledger.park(ParkedAggregate(SubscriptionId(ORDER.context, NAME), moved, 0, 3, "failed", 0, 7))
            ledger.park(ParkedAggregate(SubscriptionId(ORDER.context, NAME), stays, 0, 2, "failed", 0, 5))
            val start = ParkedAtStart(source, counted, run)
            // The partition it parked in holds it as it finds it there; re-homing is the other partition's start.
            start.parkedIn(subscription, listOf(0)).assert().isEqualTo(mapOf(0 to mapOf(stays to 2L, moved to 3L)))
            ledger.parked(SubscriptionId(ORDER.context, NAME), moved)!!.partition.assert().isEqualTo(0)
            start.parkedIn(subscription, listOf(1)).assert().isEqualTo(mapOf(1 to mapOf(moved to 3L)))
            val rehomed = ledger.parked(SubscriptionId(ORDER.context, NAME), moved)!!
            rehomed.partition.assert().isEqualTo(1)
            rehomed.unrecoverable.assert().isTrue()
            rehomed.reason.assert().contains("partition 0 from offset 7", "partition 1")
            rehomed.fromPosition.assert().isNull()
            // Started again in this run: the entry is in its partition's due listing now; the ledger is not listed again.
            start.parkedIn(subscription, listOf(1)).assert().isEqualTo(mapOf(1 to mapOf(moved to 3L)))
            listings.get().assert().isEqualTo(1)
        }

    @Test
    fun `an entry resolved after the listing is neither re-homed nor held`(): Unit = runBlocking {
        val moved = orderIn(1)
        ledger.park(ParkedAggregate(SubscriptionId(ORDER.context, NAME), moved, 0, 3, "failed", 0, 7))
        val start = ParkedAtStart(source, counted, run)
        start.parkedIn(subscription, listOf(0))
        ledger.resolveAnywhere(SubscriptionId(ORDER.context, NAME), moved)
        start.parkedIn(subscription, listOf(1)).assert().isEmpty()
        ledger.parked(SubscriptionId(ORDER.context, NAME), moved).assert().isNull()
    }

    @Test
    fun `a listing that fails fails the start, and the next start lists again`(): Unit = runBlocking {
        val moved = orderIn(1)
        ledger.park(ParkedAggregate(SubscriptionId(ORDER.context, NAME), moved, 0, 3, "failed", 0, 7))
        val start = ParkedAtStart(source, counted, run)
        failing.set(true)
        assertThrows<IllegalStateException> { runBlocking { start.parkedIn(subscription, listOf(1)) } }
        start.parkedIn(subscription, listOf(1)).assert().isEqualTo(mapOf(1 to mapOf(moved to 3L)))
        listings.get().assert().isEqualTo(2)
    }

    @Test
    fun `a start that fails for its own reason while the listing runs leaves the listing to the others`(): Unit =
        runBlocking {
            othersAfterAFailedStart(listingFails = false)
        }

    @Test
    fun `a start waiting on a listing that fails fails with the listing's own failure, and the next lists again`(): Unit =
        runBlocking {
            othersAfterAFailedStart(listingFails = true)
        }

    /**
     * Two starts of one run: the first asks for the listing and fails for a reason of its own, as a start whose
     * `coroutineScope` lost another child does, while the listing is held; then the listing ends, or fails.
     */
    private suspend fun othersAfterAFailedStart(listingFails: Boolean) = coroutineScope {
        val moved = orderIn(1)
        ledger.park(ParkedAggregate(SubscriptionId(ORDER.context, NAME), moved, 0, 3, "failed", 0, 7))
        val start = ParkedAtStart(source, counted, run)
        val release = CompletableDeferred<Unit>()
        hold.set(release)
        val first = async { start.parkedIn(subscription, listOf(1)) }
        listing.await()
        first.cancel(CancellationException("the first start failed for a reason of its own"))
        // Runs until it waits on the listing the first one started.
        val second =
            async(start = CoroutineStart.UNDISPATCHED) { runCatching { start.parkedIn(subscription, listOf(1)) } }
        failing.set(listingFails)
        release.complete(Unit)
        val outcome = second.await()
        if (listingFails) {
            outcome.exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
            outcome.exceptionOrNull().assert().isNotInstanceOf(CancellationException::class.java)
            // Taken out: the next start lists again.
            start.parkedIn(subscription, listOf(1)).assert().isEqualTo(mapOf(1 to mapOf(moved to 3L)))
            listings.get().assert().isEqualTo(2)
        } else {
            outcome.getOrThrow().assert().isEqualTo(mapOf(1 to mapOf(moved to 3L)))
            listings.get().assert().isEqualTo(1)
        }
    }

    @Test
    fun `a source whose partitions do not grow lists nothing`(): Unit = runBlocking {
        val log = me.ahoo.wow.kernel.memory.InMemoryEventLog(partitions = PARTITIONS)
        val ref = cart("x")
        ledger.park(ParkedAggregate(SubscriptionId(ORDER.context, NAME), ref, 1, 2, "failed", 0))
        ParkedAtStart(LogSource(log), counted, run).parkedIn(subscription, listOf(1))
            .assert().isEqualTo(mapOf(1 to mapOf(ref to 2L)))
        listings.get().assert().isEqualTo(0)
    }

    private companion object {
        const val TOPIC = "wow.sales.order.state"
        const val NAME = "Board.onOrder"
        const val PARTITIONS = 2
    }
}

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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.runtime.ledger.MaintenanceSkips
import me.ahoo.wow.runtime.ledger.SkipLedger
import me.ahoo.wow.runtime.ledger.UnreplayableHistoryException
import me.ahoo.wow.spi.StorageRefusedException
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.MaintenanceSkipped.Work
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * An aggregate the walk cannot read: one whose history does not replay is parked under `wow.access.walk` and the walk
 * goes on; a read or a stamp that fails is skipped only when no retry changes it, and stops the run otherwise; an
 * operator retries or skips the parked one.
 */
class AccessWalkSkipsTest : AccessWalkingFixtures() {
    private val ledger = InMemoryFailureLedger()
    private val parked = SubscriptionId("office", MaintenanceSkips.ACCESS_WALK)
    private val poisoned = type.ref("tenant", "b")
    private val classification = FailureClassification.DEFAULT

    /** The document as the walk reads it: a history moved to `poison` no longer replays, as after a broken upgrade. */
    private val reader = aggregate<Document>("office", "document") {
        initial { Document(it.id) }
        decide<Move>(Creation.ALLOWED) { _, command -> Decision.events(Moved(command.department)) }
        evolve<Moved> {
            check(it.department != POISON) { "No department is $POISON any more." }
            if (it.department == STOPPING) throw CancellationException("The node stops mid-replay.")
            department = it.department
        }
        access(department) { it.department }
    }

    /** [log], whose reads and stamps of `b` fail with [read] and [stamp] while they are set. */
    private class Faulty(val log: InMemoryEventLog) : EventLog by log, AccessWalks by log {
        var read: Exception? = null
        var stamp: Exception? = null

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> {
            val failure = read?.takeIf { ref.id == "b" } ?: return log.read(ref, fromVersion, toVersion)
            return flow { throw failure }
        }

        override suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int): Int {
            stamp?.takeIf { ref.id == "b" }?.let { throw it }
            return log.stamp(ref, access, asOf, from, until)
        }
    }

    private fun walkingOf(log: EventLog, read: Boolean = true) = AccessWalking(
        "office",
        "service/office",
        "n-1",
        listOf(if (read) model else reader),
        Kernel(log),
        { log },
        InMemoryLeaseStore(),
        options,
        observer = observer,
        skips = MaintenanceSkips("office", Work.ACCESS_WALK, { SkipLedger(ledger, 4) }, classification, observer),
    )

    private fun completed(walking: AccessWalking) = runBlocking {
        repeat(MAX_RUNS) { walking.maintain() }
        walking.walks().getValue(type)
    }

    private fun skipped() = observed.filterIsInstance<ObservedEvent.MaintenanceSkipped>()

    @Test
    fun `an aggregate whose history does not replay should be parked, and the walk should go on with the others`() {
        val log = InMemoryEventLog()
        unstamped(log, listOf("a"), "finance")
        unstamped(log, listOf("b"), "finance", POISON)
        unstamped(log, listOf("c"), "legal")

        val walk = completed(walkingOf(log, read = false))

        walk.completed.assert().isTrue()
        walk.walked.assert().isEqualTo(2L)
        history(log, "a").assert().containsExactly(access("finance"))
        history(log, "c").assert().containsExactly(access("legal"))
        // Never stamped: an events query under an access policy reads none of it (AccessQueryPolicy's `access` presence).
        history(log, "b").assert().containsExactly(null, null)
        val entry = runBlocking { ledger.parked(parked, poisoned) }!!
        entry.fromVersion.assert().isEqualTo(1)
        entry.reason.assert().startsWith("Its history did not replay").contains(POISON)
        skipped().map { it.type to it.work }.assert().containsExactly(type to Work.ACCESS_WALK)
        observed.filterIsInstance<ObservedEvent.AccessWalked>().sumOf { it.aggregates }.assert().isEqualTo(2)
    }

    @Test
    fun `a read or a stamp that a retry may change should stop the walk at the aggregate, parking nothing`() {
        val log = Faulty(InMemoryEventLog())
        unstamped(log.log, listOf("a", "b", "c"), "finance")
        val walking = walkingOf(log)
        log.read = StorageUnavailableException("The log store is down.")

        assertThrows<StorageUnavailableException> { runBlocking { walking.maintain() } }
        log.read = null
        log.stamp = IllegalStateException("A failure nothing classifies.")
        assertThrows<IllegalStateException> { runBlocking { walking.maintain() } }

        walking.walks().getValue(type).completed.assert().isFalse()
        runBlocking { ledger.count(parked, 10) }.assert().isZero()
        skipped().assert().isEmpty()
        log.stamp = null
        completed(walking).walked.assert().isEqualTo(3L)
        history(log.log, "b").assert().containsExactly(access("finance"))
    }

    @Test
    fun `a replay cancelled mid-way should stop the walk and park nothing`() {
        val log = InMemoryEventLog()
        unstamped(log, listOf("b"), "finance", STOPPING)
        val walking = walkingOf(log, read = false)

        assertThrows<CancellationException> { runBlocking { walking.maintain() } }

        walking.walks().getValue(type).completed.assert().isFalse()
        runBlocking { ledger.count(parked, 10) }.assert().isZero()
        skipped().assert().isEmpty()
    }

    @Test
    fun `a stamp refused for the aggregate's own data should be parked, and the walk should go on`() {
        val log = Faulty(InMemoryEventLog())
        unstamped(log.log, listOf("a", "b", "c"), "finance")
        log.stamp = StorageRefusedException("The stamp of b is larger than the store takes.")

        val walk = completed(walkingOf(log))

        walk.completed.assert().isTrue()
        walk.walked.assert().isEqualTo(2L)
        runBlocking { ledger.parked(parked, poisoned) }!!.reason.assert().startsWith("It failed").contains("larger")
        skipped().assert().hasSize(1)
    }

    @Test
    fun `an operator's retry should stamp the aggregate once it reads, and resolve its entry`() {
        val log = InMemoryEventLog()
        unstamped(log, listOf("b"), "finance", POISON)
        completed(walkingOf(log, read = false))

        runBlocking { walkingOf(log, read = false).retry(poisoned) }.assert().isFalse()
        runBlocking { ledger.isParked(parked, poisoned) }.assert().isTrue()
        history(log, "b").assert().containsExactly(null, null)

        // A deployment whose model reads the history again.
        runBlocking { walkingOf(log).retry(poisoned) }.assert().isTrue()
        runBlocking { ledger.isParked(parked, poisoned) }.assert().isFalse()
        history(log, "b").assert().containsExactly(access(POISON), access(POISON))
    }

    @Test
    fun `an operator's skip should resolve the entry and leave the history unstamped`() {
        val log = InMemoryEventLog()
        unstamped(log, listOf("b"), "finance", POISON)
        val walking = walkingOf(log, read = false)
        completed(walking)

        runBlocking { checkNotNull(walking.skips).skip(poisoned) }.assert().isTrue()

        runBlocking { ledger.isParked(parked, poisoned) }.assert().isFalse()
        history(log, "b").assert().containsExactly(null, null)
        skipped().assert().hasSize(1)
    }

    @Test
    fun `a walk without skips should stop at an aggregate whose history does not replay, as it did`() {
        val log = InMemoryEventLog()
        unstamped(log, listOf("b"), "finance", POISON)
        val walking = AccessWalking(
            "office",
            "service/office",
            "n-1",
            listOf(reader),
            Kernel(log),
            { log },
            InMemoryLeaseStore(),
            options,
        )

        assertThrows<UnreplayableHistoryException> { runBlocking { walking.maintain() } }

        walking.walks().getValue(type).completed.assert().isFalse()
    }

    private companion object {
        const val POISON = "poison"
        const val STOPPING = "stopping"
        const val MAX_RUNS = 4
    }
}

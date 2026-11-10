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

package me.ahoo.wow.tck.ledger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.LedgerRequest
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The [FailureLedger] contract (documentation/designs/04-subscriptions.md §2) every backend passes. A backend's
 * test extends it and supplies a ledger from [createLedger].
 */
abstract class FailureLedgerSpec {
    /** A ledger. Ledgers may share storage: each test uses subscriptions of its own. */
    protected abstract fun createLedger(): FailureLedger

    private val subscription = "sub-" + UUID.randomUUID()
    private val another = "$subscription-another"
    private val type = AggregateType("ledger", "order")

    private val ledger: FailureLedger by lazy { createLedger() }

    private fun ref(id: String, tenant: String = "tenant") = type.ref(tenant, id)

    private fun entry(
        ref: AggregateRef,
        fromVersion: Int,
        reason: String = "failed at $fromVersion",
        subscription: String = this.subscription,
        partition: Int = 0,
    ) = ParkedAggregate(subscription, ref, partition, fromVersion, reason, FAILED_AT + fromVersion)

    @Test
    fun `a parked aggregate reads back for its subscription only`(): Unit = runBlocking {
        val parked = entry(ref("a"), 3, reason = "boom: ü \" é")
        ledger.park(parked).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(parked)
        ledger.isParked(subscription, ref("a")).assert().isTrue()
        ledger.isParked(subscription, ref("b")).assert().isFalse()
        ledger.isParked(another, ref("a")).assert().isFalse()
        ledger.isParked(subscription, ref("a", tenant = "other")).assert().isFalse()
        ledger.isParked(subscription, AggregateType("other", "order").ref("tenant", "a")).assert().isFalse()
    }

    @Test
    fun `a source position reads back with its entry, and moves with the earlier version or a repark`(): Unit =
        runBlocking {
            val parked = entry(ref("a"), 4).copyWith(fromPosition = 40)
            ledger.park(parked).assert().isTrue()
            ledger.parked(subscription, ref("a")).assert().isEqualTo(parked)
            ledger.list(subscription).assert().containsExactly(parked)
            ledger.park(entry(ref("a"), 6).copyWith(fromPosition = 60)).assert().isFalse()
            ledger.parked(subscription, ref("a"))!!.fromPosition.assert().isEqualTo(40L)
            val earlier = entry(ref("a"), 2).copyWith(fromPosition = Long.MAX_VALUE)
            ledger.park(earlier).assert().isTrue()
            ledger.parked(subscription, ref("a")).assert().isEqualTo(earlier.at(1))
            val unrecoverable = earlier.copyWith(reason = "${ParkedAggregate.UNRECOVERABLE}: gone", fromPosition = 0)
            ledger.repark(unrecoverable).assert().isTrue()
            ledger.parked(subscription, ref("a")).assert().isEqualTo(unrecoverable.at(2))
            ledger.parked(subscription, ref("a"))!!.unrecoverable.assert().isTrue()
            val withoutPosition = earlier.copyWith(fromPosition = null)
            ledger.repark(withoutPosition).assert().isTrue()
            ledger.parked(subscription, ref("a")).assert().isEqualTo(withoutPosition.at(3))
            ledger.resolve(subscription, ref("a")).assert().isEqualTo(withoutPosition.at(3))
        }

    @Test
    fun `parking an aggregate again keeps the earliest from version`(): Unit = runBlocking {
        val first = entry(ref("a"), 5)
        ledger.park(first).assert().isTrue()
        ledger.park(first).assert().isFalse()
        ledger.park(entry(ref("a"), 7)).assert().isFalse()
        ledger.park(entry(ref("a"), 5, reason = "again")).assert().isFalse()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(first)
        val earlier = entry(ref("a"), 2)
        ledger.park(earlier).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(earlier.at(1))
    }

    @Test
    fun `concurrent parks of one aggregate keep the earliest from version`(): Unit = runBlocking {
        val changed = coroutineScope {
            (CONCURRENT_PARKS downTo 1).map { version ->
                async(Dispatchers.Default) { ledger.park(entry(ref("a"), version)) }
            }.awaitAll()
        }
        changed.assert().contains(true)
        // Its revision counts the parks that replaced it, however they interleaved.
        ledger.parked(subscription, ref("a"))!!.at(0).assert().isEqualTo(entry(ref("a"), 1))
    }

    @Test
    fun `reparking replaces a parked entry, later or earlier, with its partition and reason`(): Unit = runBlocking {
        ledger.park(entry(ref("a"), 3))
        ledger.park(entry(ref("a"), 3, subscription = another))
        val later = entry(ref("a"), 6, reason = "failed again: ü \" é")
        ledger.repark(later).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(later.at(1))
        ledger.parked(another, ref("a")).assert().isEqualTo(entry(ref("a"), 3, subscription = another))
        val sameVersion = entry(ref("a"), 6, reason = "a newer reason")
        ledger.repark(sameVersion).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(sameVersion.at(2))
        // Re-homed to another partition: the owner of that one looks at it from now on.
        val earlier = entry(ref("a"), 2, partition = 3)
        ledger.repark(earlier, from = 0).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(earlier.at(3))
        ledger.list(subscription).assert().containsExactly(earlier.at(3))
        ledger.due(subscription, listOf(3), ParkedAggregate.NEVER).map { it.ref }.assert().containsExactly(ref("a"))
        ledger.due(subscription, listOf(0), ParkedAggregate.NEVER).assert().isEmpty()
    }

    @Test
    fun `a repark or a worker's resolve from the partition an entry left writes nothing`(): Unit = runBlocking {
        // #5481: the old owner's retry ends after the new owner re-homed the entry.
        ledger.park(entry(ref("a"), 3, partition = 0))
        val rehomed = entry(ref("a"), 3, reason = "${ParkedAggregate.UNRECOVERABLE}: moved", partition = 1)
            .copyWith(fromPosition = null)
        ledger.repark(rehomed, from = 0).assert().isTrue()
        // Read in partition 0 before the move: neither its repark nor its resolve applies.
        ledger.repark(entry(ref("a"), 5, reason = "failed again", partition = 0)).assert().isFalse()
        ledger.repark(entry(ref("a"), 5, partition = 2), from = 0).assert().isFalse()
        ledger.resolve(subscription, ref("a"), 0).assert().isNull()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(rehomed.copyWith(revision = 1))
        // In the partition it is parked in now, both apply; an operator's resolve applies wherever it is.
        ledger.repark(rehomed.copyWith(reason = "${ParkedAggregate.UNRECOVERABLE}: still moved")).assert().isTrue()
        ledger.resolve(subscription, ref("a"), 1)!!.partition.assert().isEqualTo(1)
        ledger.park(entry(ref("b"), 1, partition = 4))
        ledger.resolve(subscription, ref("b"), 5).assert().isNull()
        ledger.resolve(subscription, ref("b"))!!.partition.assert().isEqualTo(4)
        ledger.resolve(subscription, ref("c"), 0).assert().isNull()
    }

    @Test
    fun `reparking an aggregate that is not parked writes nothing`(): Unit = runBlocking {
        ledger.repark(entry(ref("a"), 4)).assert().isFalse()
        ledger.isParked(subscription, ref("a")).assert().isFalse()
        ledger.park(entry(ref("a"), 2))
        ledger.resolve(subscription, ref("a"))
        ledger.repark(entry(ref("a"), 4)).assert().isFalse()
        ledger.parked(subscription, ref("a")).assert().isNull()
    }

    @Test
    fun `a subscription's parked aggregates are listed in pages, in aggregate order`(): Unit = runBlocking {
        val refs = listOf(ref("b"), ref("a", tenant = "u2"), ref("a"), ref("c"), ref("a", tenant = "u1"))
        refs.forEach { ledger.park(entry(it, 1)) }
        ledger.park(entry(ref("z"), 1, subscription = another))
        val expected = listOf(ref("a"), ref("b"), ref("c"), ref("a", tenant = "u1"), ref("a", tenant = "u2"))
        val first = ledger.list(subscription, limit = 2)
        first.map { it.ref }.assert().isEqualTo(expected.take(2))
        val second = ledger.list(subscription, after = first.last().ref, limit = 2)
        second.map { it.ref }.assert().isEqualTo(expected.subList(2, 4))
        val third = ledger.list(subscription, after = second.last().ref, limit = 2)
        third.map { it.ref }.assert().isEqualTo(expected.drop(4))
        ledger.list(subscription, after = third.last().ref, limit = 2).assert().isEmpty()
        ledger.list(subscription).map { it.ref }.assert().isEqualTo(expected)
        ledger.list("$subscription-none").assert().isEmpty()
    }

    @Test
    fun `a tenant's parked aggregates are listed alone, in pages, in aggregate order`(): Unit = runBlocking {
        val refs = listOf(ref("b"), ref("a", tenant = "u2"), ref("a"), ref("c", tenant = "u2"), ref("a", tenant = "u1"))
        refs.forEach { ledger.park(entry(it, 1)) }
        ledger.park(entry(ref("z", tenant = "u2"), 1, subscription = another))
        val first = ledger.list(subscription, limit = 1, tenantId = "u2")
        first.map { it.ref }.assert().containsExactly(ref("a", tenant = "u2"))
        ledger.list(subscription, after = first.last().ref, limit = 1, tenantId = "u2").map { it.ref }
            .assert().containsExactly(ref("c", tenant = "u2"))
        ledger.list(subscription, after = ref("c", tenant = "u2"), tenantId = "u2").assert().isEmpty()
        ledger.list(subscription, tenantId = "tenant").map { it.ref }.assert().containsExactly(ref("a"), ref("b"))
        ledger.list(subscription, tenantId = "nobody").assert().isEmpty()
    }

    @Test
    fun `a subscription's parked aggregates are counted up to the bound`(): Unit = runBlocking {
        (1..5).forEach { ledger.park(entry(ref("a$it"), 1)) }
        ledger.park(entry(ref("z"), 1, subscription = another))
        ledger.count(subscription, 10).assert().isEqualTo(5)
        ledger.count(subscription, 5).assert().isEqualTo(5)
        ledger.count(subscription, 3).assert().isEqualTo(3)
        ledger.count(another, 10).assert().isEqualTo(1)
        ledger.count("$subscription-none", 10).assert().isEqualTo(0)
    }

    @Test
    fun `the due entries are those of the partitions not scheduled yet or scheduled by now, in pages`(): Unit =
        runBlocking {
            ledger.park(entry(ref("a"), 1, partition = 0))
            ledger.park(entry(ref("b"), 1, partition = 1))
            ledger.park(entry(ref("c"), 1, partition = 0))
            ledger.park(entry(ref("d"), 1, partition = 0))
            ledger.park(entry(ref("e"), 1, partition = 0, subscription = another))
            ledger.amend(subscription, ref("c"), ParkedAmendment.Schedule(0, FAILED_AT + 100))!!
            ledger.amend(subscription, ref("d"), ParkedAmendment.Schedule(0, ParkedAggregate.NEVER))!!
            fun refs(page: List<ParkedAggregate>) = page.map { it.ref }

            refs(ledger.due(subscription, listOf(0), FAILED_AT + 99)).assert().containsExactly(ref("a"))
            refs(ledger.due(subscription, listOf(0), FAILED_AT + 100)).assert().containsExactly(ref("a"), ref("c"))
            refs(ledger.due(subscription, listOf(0, 1), FAILED_AT + 100)).assert()
                .containsExactly(ref("a"), ref("b"), ref("c"))
            refs(ledger.due(subscription, listOf(1, 2), FAILED_AT)).assert().containsExactly(ref("b"))
            // At NEVER, every entry of the partitions: what a node reads as it acquires them.
            refs(ledger.due(subscription, listOf(0), ParkedAggregate.NEVER)).assert()
                .containsExactly(ref("a"), ref("c"), ref("d"))
            val first = ledger.due(subscription, listOf(0, 1), FAILED_AT + 100, limit = 2)
            refs(first).assert().containsExactly(ref("a"), ref("b"))
            refs(ledger.due(subscription, listOf(0, 1), FAILED_AT + 100, after = first.last().ref, limit = 2))
                .assert().containsExactly(ref("c"))
            ledger.due(subscription, emptyList(), ParkedAggregate.NEVER).assert().isEmpty()
            ledger.due(subscription, listOf(7), ParkedAggregate.NEVER).assert().isEmpty()
        }

    @Test
    fun `every stored change of an entry gives it the next revision`(): Unit = runBlocking {
        ledger.park(entry(ref("a"), 5).copyWith(revision = 9)).assert().isTrue()
        ledger.parked(subscription, ref("a"))!!.revision.assert().isEqualTo(0L)
        // A park that changes nothing keeps it.
        ledger.park(entry(ref("a"), 6)).assert().isFalse()
        ledger.parked(subscription, ref("a"))!!.revision.assert().isEqualTo(0L)
        var revision = 0L
        unconditionalWrites(ref("a")).forEach { (name, write) ->
            write()
            ledger.parked(subscription, ref("a"))!!.revision.assert().describedAs(name).isEqualTo(++revision)
        }
        conditionalAmendments(0).indices.forEach { index ->
            val amendment = conditionalAmendments(revision)[index]
            ledger.amend(subscription, ref("a"), amendment)!!.revision.assert().describedAs("$amendment")
                .isEqualTo(++revision)
            ledger.parked(subscription, ref("a"))!!.revision.assert().isEqualTo(revision)
        }
    }

    @Test
    fun `a claim, schedule or take read before another write never applies after it, the entry due again`(): Unit =
        runBlocking {
            // D404: each of these writes changes what the owner read, a retry state or where and why it is parked.
            unconditionalWrites(ref("w")).forEach { (name, write) ->
                ledger.resolve(subscription, ref("w"))
                ledger.park(entry(ref("w"), 5))
                val read = ledger.amend(subscription, ref("w"), ParkedAmendment.Schedule(0, ParkedAggregate.NEVER))!!
                write()
                val after = ledger.parked(subscription, ref("w"))!!
                conditionalAmendments(read.revision).forEach { stale ->
                    ledger.amend(subscription, ref("w"), stale).assert().describedAs("$stale after $name").isNull()
                }
                ledger.parked(subscription, ref("w")).assert().describedAs(name).isEqualTo(after)
                // Unscheduled by the write, so the owner's next scan reads it again.
                after.nextAttemptAt.assert().describedAs(name).isNull()
                ledger.due(subscription, listOf(after.partition), FAILED_AT).map { it.ref }.assert()
                    .describedAs(name).containsExactly(ref("w"))
            }
        }

    @Test
    fun `a schedule, claim or take applies at the revision read, and a claim or operator's change unschedules`(): Unit =
        runBlocking {
            ledger.park(entry(ref("a"), 1))
            val at = FAILED_AT + 100
            val scheduled = ledger.amend(subscription, ref("a"), ParkedAmendment.Schedule(0, at))!!
            scheduled.nextAttemptAt.assert().isEqualTo(at)
            // Read before it was scheduled: written over nothing.
            ledger.amend(subscription, ref("a"), ParkedAmendment.Schedule(0, at + 1)).assert().isNull()
            ledger.amend(subscription, ref("a"), ParkedAmendment.ClaimRetry(0, at)).assert().isNull()
            ledger.amend(subscription, ref("a"), ParkedAmendment.TakeRequest(0)).assert().isNull()
            ledger.parked(subscription, ref("a")).assert().isEqualTo(scheduled)
            val claimed = ledger.amend(subscription, ref("a"), ParkedAmendment.ClaimRetry(scheduled.revision, at))!!
            claimed.nextAttemptAt.assert().isNull()
            claimed.retries.assert().isEqualTo(1)
            claimed.retryAt.assert().isEqualTo(at)
            listOf(
                ParkedAmendment.MarkRecoverable(Recoverable.RECOVERABLE),
                ParkedAmendment.ApplyRetrySpec(SPEC),
                ParkedAmendment.Request(LedgerRequest.RETRY),
            ).forEach { change ->
                val read = ledger.parked(subscription, ref("a"))!!
                ledger.amend(subscription, ref("a"), ParkedAmendment.Schedule(read.revision, at + 10))!!.nextAttemptAt
                    .assert().isEqualTo(at + 10)
                ledger.amend(subscription, ref("a"), change)!!.nextAttemptAt.assert().isNull()
            }
            // Taking the request keeps the entry unscheduled, for the owner to look at again.
            val requested = ledger.parked(subscription, ref("a"))!!
            ledger.amend(subscription, ref("a"), ParkedAmendment.TakeRequest(requested.revision))!!.nextAttemptAt
                .assert().isNull()
        }

    @Test
    fun `resolving removes the entry, after which the aggregate may be parked anew`(): Unit = runBlocking {
        val parked = entry(ref("a"), 4)
        ledger.park(parked)
        ledger.park(entry(ref("a"), 4, subscription = another))
        ledger.resolve(subscription, ref("a")).assert().isEqualTo(parked)
        ledger.isParked(subscription, ref("a")).assert().isFalse()
        ledger.resolve(subscription, ref("a")).assert().isNull()
        ledger.isParked(another, ref("a")).assert().isTrue()
        val later = entry(ref("a"), 9)
        ledger.park(later).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(later)
    }

    @Test
    fun `a new entry's retry state reads back with it, in its page and when resolved`(): Unit = runBlocking {
        val parked = entry(ref("a"), 3, partition = 5).copyWith(
            retries = 2,
            retryAt = FAILED_AT + 100,
            retrySpec = SPEC,
            recoverable = Recoverable.RECOVERABLE,
            request = LedgerRequest.RETRY,
            nextAttemptAt = FAILED_AT + 500,
        )
        ledger.park(parked).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(parked)
        ledger.list(subscription).assert().containsExactly(parked)
        ledger.resolve(subscription, ref("a")).assert().isEqualTo(parked)
    }

    @Test
    fun `parking from an earlier version and reparking keep the retry state`(): Unit = runBlocking {
        ledger.park(entry(ref("a"), 5))
        val claimed = ledger.amend(subscription, ref("a"), ParkedAmendment.ClaimRetry(0, FAILED_AT + 50))!!
        ledger.amend(subscription, ref("a"), ParkedAmendment.ApplyRetrySpec(SPEC))
        ledger.amend(subscription, ref("a"), ParkedAmendment.MarkRecoverable(Recoverable.RECOVERABLE))
        ledger.amend(subscription, ref("a"), ParkedAmendment.Request(LedgerRequest.SKIP))
        val state = claimed.copyWith(
            retrySpec = SPEC,
            recoverable = Recoverable.RECOVERABLE,
            request = LedgerRequest.SKIP,
            revision = 4,
        )
        ledger.parked(subscription, ref("a")).assert().isEqualTo(state)

        // The earlier entry's own retry state is not taken: the parked one keeps its own.
        ledger.park(entry(ref("a"), 2).copyWith(retries = 7, recoverable = Recoverable.UNRECOVERABLE)).assert().isTrue()
        val earlier = entry(ref("a"), 2)
        ledger.parked(subscription, ref("a")).assert().isEqualTo(
            state.copyWith(fromVersion = 2, reason = earlier.reason, failedAt = earlier.failedAt, revision = 5),
        )
        val reparked = entry(ref("a"), 4, reason = "failed again").copyWith(fromPosition = 44, retries = 9)
        ledger.repark(reparked).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(
            state.copyWith(
                fromVersion = 4,
                reason = "failed again",
                failedAt = reparked.failedAt,
                fromPosition = 44,
                revision = 6,
            ),
        )
    }

    @Test
    fun `a retry is claimed only at the revision read, once among concurrent claims`(): Unit = runBlocking {
        ledger.park(entry(ref("a"), 1))
        ledger.amend(subscription, ref("a"), ParkedAmendment.ClaimRetry(1, FAILED_AT)).assert().isNull()
        val first = ledger.amend(subscription, ref("a"), ParkedAmendment.ClaimRetry(0, FAILED_AT + 10))!!
        first.retries.assert().isEqualTo(1)
        first.retryAt.assert().isEqualTo(FAILED_AT + 10)
        ledger.amend(subscription, ref("a"), ParkedAmendment.ClaimRetry(0, FAILED_AT + 20)).assert().isNull()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(first)

        val claims = coroutineScope {
            (1..CONCURRENT_PARKS).map { attempt ->
                async(Dispatchers.Default) {
                    ledger.amend(subscription, ref("a"), ParkedAmendment.ClaimRetry(1, FAILED_AT + 100 + attempt))
                }
            }.awaitAll()
        }
        val won = claims.filterNotNull()
        won.size.assert().isEqualTo(1)
        ledger.parked(subscription, ref("a")).assert().isEqualTo(won.single())
        won.single().retries.assert().isEqualTo(2)
    }

    @Test
    fun `an operator's retry spec and recoverability are applied and cleared`(): Unit = runBlocking {
        ledger.park(entry(ref("a"), 1))
        ledger.amend(subscription, ref("a"), ParkedAmendment.ApplyRetrySpec(SPEC))!!.retrySpec.assert().isEqualTo(SPEC)
        val zero = ParkedRetrySpec(0, Duration.ZERO, Duration.ZERO)
        ledger.amend(subscription, ref("a"), ParkedAmendment.ApplyRetrySpec(zero))!!.retrySpec.assert().isEqualTo(zero)
        ledger.parked(subscription, ref("a"))!!.retrySpec.assert().isEqualTo(zero)
        ledger.amend(subscription, ref("a"), ParkedAmendment.ApplyRetrySpec(null))!!.retrySpec.assert().isNull()
        ledger.parked(subscription, ref("a"))!!.retrySpec.assert().isNull()
        Recoverable.entries.forEach { recoverable ->
            ledger.amend(subscription, ref("a"), ParkedAmendment.MarkRecoverable(recoverable))!!
                .recoverable.assert().isEqualTo(recoverable)
            ledger.parked(subscription, ref("a"))!!.recoverable.assert().isEqualTo(recoverable)
        }
        val revision = 3L + Recoverable.entries.size
        ledger.parked(subscription, ref("a")).assert()
            .isEqualTo(entry(ref("a"), 1).copyWith(recoverable = Recoverable.UNRECOVERABLE, revision = revision))
    }

    @Test
    fun `a request is left for the owner and taken once`(): Unit = runBlocking {
        ledger.park(entry(ref("a"), 1))
        ledger.amend(subscription, ref("a"), ParkedAmendment.Request(LedgerRequest.RETRY))!!
            .request.assert().isEqualTo(LedgerRequest.RETRY)
        // Replaced before it was taken: a take of the request read before it applies to nothing.
        ledger.amend(subscription, ref("a"), ParkedAmendment.Request(LedgerRequest.SKIP))!!
            .request.assert().isEqualTo(LedgerRequest.SKIP)
        ledger.amend(subscription, ref("a"), ParkedAmendment.TakeRequest(1)).assert().isNull()
        ledger.amend(subscription, ref("a"), ParkedAmendment.TakeRequest(2))!!.request.assert().isNull()
        ledger.amend(subscription, ref("a"), ParkedAmendment.TakeRequest(2)).assert().isNull()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(entry(ref("a"), 1).at(3))
    }

    @Test
    fun `amending an aggregate that is not parked writes nothing`(): Unit = runBlocking {
        ledger.park(entry(ref("a"), 1, subscription = another))
        (operatorAmendments() + conditionalAmendments(0)).forEach { amendment ->
            ledger.amend(subscription, ref("a"), amendment).assert().isNull()
            ledger.amend(subscription, ref("b"), amendment).assert().isNull()
        }
        ledger.isParked(subscription, ref("a")).assert().isFalse()
        ledger.parked(another, ref("a")).assert().isEqualTo(entry(ref("a"), 1, subscription = another))
    }

    /** [this] entry as stored at [revision]. */
    private fun ParkedAggregate.at(revision: Long) = copyWith(revision = revision)

    /** One of each amendment an operator makes, applied whatever the entry's revision. */
    private fun operatorAmendments(): List<ParkedAmendment> = listOf(
        ParkedAmendment.MarkRecoverable(Recoverable.RECOVERABLE),
        ParkedAmendment.ApplyRetrySpec(SPEC),
        ParkedAmendment.Request(LedgerRequest.RETRY),
    )

    /** One of each amendment that acts on what its caller read, read at [revision]. */
    private fun conditionalAmendments(revision: Long): List<ParkedAmendment> = listOf(
        ParkedAmendment.ClaimRetry(revision, FAILED_AT + 1),
        ParkedAmendment.Schedule(revision, FAILED_AT + 2),
        ParkedAmendment.TakeRequest(revision),
    )

    /**
     * Every write of [ref]'s entry that is not conditional on a revision read, by name: each operator amendment, a
     * repark, and a park that replaces the entry. A new amendment fails [classified] until it is named here or among
     * the [conditionalAmendments].
     */
    private fun unconditionalWrites(ref: AggregateRef): List<Pair<String, suspend () -> Unit>> =
        operatorAmendments().map { amendment ->
            "$amendment" to suspend {
                checkNotNull(ledger.amend(subscription, ref, amendment)) { "$amendment wrote nothing" }
                Unit
            }
        } + listOf(
            "repark" to suspend { check(ledger.repark(entry(ref, 6, reason = "failed again"))) { "repark wrote nothing" } },
            "park from an earlier version" to suspend {
                val current = checkNotNull(ledger.parked(subscription, ref))
                check(ledger.park(entry(ref, current.fromVersion - 1))) { "park wrote nothing" }
            },
        )

    @Test
    fun `every amendment is classified, as conditional on a revision read or as an unconditional write`() {
        // D404: a new amendment must say which it is, so the contract above covers it.
        (operatorAmendments() + conditionalAmendments(0)).map { it::class }.toSet().assert()
            .isEqualTo(ParkedAmendment::class.sealedSubclasses.toSet())
        conditionalAmendments(0).forEach { classified(it).assert().isTrue() }
        operatorAmendments().forEach { classified(it).assert().isFalse() }
    }

    /** Whether [amendment] applies only at the revision its caller read: over the sealed type, so none is forgotten. */
    private fun classified(amendment: ParkedAmendment): Boolean = when (amendment) {
        is ParkedAmendment.ClaimRetry, is ParkedAmendment.Schedule, is ParkedAmendment.TakeRequest -> true
        is ParkedAmendment.MarkRecoverable, is ParkedAmendment.ApplyRetrySpec, is ParkedAmendment.Request -> false
    }

    protected companion object {
        const val FAILED_AT = 1_700_000_000_000L
        const val CONCURRENT_PARKS = 12
        val SPEC = ParkedRetrySpec(maxRetries = 4, initialBackoff = 1500.milliseconds, claimTtl = 2.minutes)
    }
}

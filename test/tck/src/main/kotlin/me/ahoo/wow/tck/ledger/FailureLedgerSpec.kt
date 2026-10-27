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
    ) = ParkedAggregate(subscription, ref, fromVersion, reason, FAILED_AT + fromVersion)

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
            ledger.parked(subscription, ref("a")).assert().isEqualTo(earlier)
            val unrecoverable = earlier.copyWith(reason = "${ParkedAggregate.UNRECOVERABLE}: gone", fromPosition = 0)
            ledger.repark(unrecoverable).assert().isTrue()
            ledger.parked(subscription, ref("a")).assert().isEqualTo(unrecoverable)
            ledger.parked(subscription, ref("a"))!!.unrecoverable.assert().isTrue()
            val withoutPosition = earlier.copyWith(fromPosition = null)
            ledger.repark(withoutPosition).assert().isTrue()
            ledger.parked(subscription, ref("a")).assert().isEqualTo(withoutPosition)
            ledger.resolve(subscription, ref("a")).assert().isEqualTo(withoutPosition)
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
        ledger.parked(subscription, ref("a")).assert().isEqualTo(earlier)
    }

    @Test
    fun `concurrent parks of one aggregate keep the earliest from version`(): Unit = runBlocking {
        val changed = coroutineScope {
            (CONCURRENT_PARKS downTo 1).map { version ->
                async(Dispatchers.Default) { ledger.park(entry(ref("a"), version)) }
            }.awaitAll()
        }
        changed.assert().contains(true)
        ledger.parked(subscription, ref("a")).assert().isEqualTo(entry(ref("a"), 1))
    }

    @Test
    fun `reparking replaces a parked entry, later or earlier, with its reason`(): Unit = runBlocking {
        ledger.park(entry(ref("a"), 3))
        ledger.park(entry(ref("a"), 3, subscription = another))
        val later = entry(ref("a"), 6, reason = "failed again: ü \" é")
        ledger.repark(later).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(later)
        ledger.parked(another, ref("a")).assert().isEqualTo(entry(ref("a"), 3, subscription = another))
        val sameVersion = entry(ref("a"), 6, reason = "a newer reason")
        ledger.repark(sameVersion).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(sameVersion)
        val earlier = entry(ref("a"), 2)
        ledger.repark(earlier).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(earlier)
        ledger.list(subscription).assert().containsExactly(earlier)
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
        val parked = entry(ref("a"), 3).copyWith(
            retries = 2,
            retryAt = FAILED_AT + 100,
            retrySpec = SPEC,
            recoverable = Recoverable.RECOVERABLE,
            request = LedgerRequest.RESUME,
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
        )
        ledger.parked(subscription, ref("a")).assert().isEqualTo(state)

        // The earlier entry's own retry state is not taken: the parked one keeps its own.
        ledger.park(entry(ref("a"), 2).copyWith(retries = 7, recoverable = Recoverable.UNRECOVERABLE)).assert().isTrue()
        val earlier = entry(ref("a"), 2)
        ledger.parked(subscription, ref("a")).assert().isEqualTo(
            state.copyWith(fromVersion = 2, reason = earlier.reason, failedAt = earlier.failedAt),
        )
        val reparked = entry(ref("a"), 4, reason = "failed again").copyWith(fromPosition = 44, retries = 9)
        ledger.repark(reparked).assert().isTrue()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(
            state.copyWith(fromVersion = 4, reason = "failed again", failedAt = reparked.failedAt, fromPosition = 44),
        )
    }

    @Test
    fun `a retry is claimed only from the retries read, once among concurrent claims`(): Unit = runBlocking {
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
        ledger.parked(subscription, ref("a")).assert()
            .isEqualTo(entry(ref("a"), 1).copyWith(recoverable = Recoverable.UNRECOVERABLE))
    }

    @Test
    fun `a request is left for the owner and taken once`(): Unit = runBlocking {
        ledger.park(entry(ref("a"), 1))
        ledger.amend(subscription, ref("a"), ParkedAmendment.TakeRequest(LedgerRequest.RESUME)).assert().isNull()
        ledger.amend(subscription, ref("a"), ParkedAmendment.Request(LedgerRequest.RESUME))!!
            .request.assert().isEqualTo(LedgerRequest.RESUME)
        ledger.amend(subscription, ref("a"), ParkedAmendment.TakeRequest(LedgerRequest.SKIP)).assert().isNull()
        ledger.amend(subscription, ref("a"), ParkedAmendment.Request(LedgerRequest.SKIP))!!
            .request.assert().isEqualTo(LedgerRequest.SKIP)
        ledger.amend(subscription, ref("a"), ParkedAmendment.TakeRequest(LedgerRequest.SKIP))!!
            .request.assert().isNull()
        ledger.amend(subscription, ref("a"), ParkedAmendment.TakeRequest(LedgerRequest.SKIP)).assert().isNull()
        ledger.parked(subscription, ref("a")).assert().isEqualTo(entry(ref("a"), 1))
    }

    @Test
    fun `amending an aggregate that is not parked writes nothing`(): Unit = runBlocking {
        ledger.park(entry(ref("a"), 1, subscription = another))
        listOf(
            ParkedAmendment.ClaimRetry(0, FAILED_AT),
            ParkedAmendment.MarkRecoverable(Recoverable.RECOVERABLE),
            ParkedAmendment.ApplyRetrySpec(SPEC),
            ParkedAmendment.Request(LedgerRequest.RESUME),
            ParkedAmendment.TakeRequest(LedgerRequest.RESUME),
        ).forEach { amendment ->
            ledger.amend(subscription, ref("a"), amendment).assert().isNull()
            ledger.amend(subscription, ref("b"), amendment).assert().isNull()
        }
        ledger.isParked(subscription, ref("a")).assert().isFalse()
        ledger.parked(another, ref("a")).assert().isEqualTo(entry(ref("a"), 1, subscription = another))
    }

    protected companion object {
        const val FAILED_AT = 1_700_000_000_000L
        const val CONCURRENT_PARKS = 12
        val SPEC = ParkedRetrySpec(maxRetries = 4, initialBackoff = 1500.milliseconds, executionTimeout = 2.minutes)
    }
}

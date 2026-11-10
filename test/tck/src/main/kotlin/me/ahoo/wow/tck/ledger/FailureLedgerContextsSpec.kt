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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.LedgerRequest
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.ledger.Recoverable
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * The [FailureLedger] keys an entry by its subscription's identity, its context and name ([SubscriptionId]), every
 * backend alike: names are unique within a context only, and the contexts of a service whose stores are one store
 * share one ledger. Two subscriptions of one name in two contexts, which park the same aggregate in the same partition,
 * never see, list, count, re-park, amend or resolve each other's entries, through any method of the port.
 *
 * A backend's test extends it and supplies a ledger from [createLedger].
 */
abstract class FailureLedgerContextsSpec {
    /** A ledger. Ledgers may share storage: each test uses contexts of its own. */
    protected abstract fun createLedger(): FailureLedger

    private val unique = UUID.randomUUID().toString()
    private val board = SubscriptionId("sales-$unique", NAME)

    /** The other context's subscription of the same name. */
    private val other = SubscriptionId("stock-$unique", NAME)

    private val ref = AggregateType("sales", "order").ref("tenant", "o-1")

    private val ledger: FailureLedger by lazy { createLedger() }

    private fun entry(subscription: SubscriptionId, fromVersion: Int, reason: String = "$subscription failed") =
        ParkedAggregate(subscription, ref, PARTITION, fromVersion, reason, FAILED_AT + fromVersion)

    /** Both subscriptions park [ref], from different versions; returns the other's entry as stored. */
    private suspend fun bothParked(): ParkedAggregate {
        ledger.park(entry(board, 3)).assert().isTrue()
        ledger.park(entry(other, 5)).assert().isTrue()
        return entry(other, 5)
    }

    @Test
    fun `each context's subscription parks, and reads back, its own entry`(): Unit = runBlocking {
        ledger.park(entry(board, 3)).assert().isTrue()
        ledger.isParked(other, ref).assert().isFalse()
        ledger.parked(other, ref).assert().isNull()
        // Not an earlier version of the first one: an entry of its own.
        ledger.park(entry(other, 5)).assert().isTrue()
        ledger.parked(board, ref).assert().isEqualTo(entry(board, 3))
        ledger.parked(other, ref).assert().isEqualTo(entry(other, 5))
        ledger.parked(other, ref)!!.subscription.context.assert().isEqualTo(other.context)
        // An earlier version parks over its own subscription's entry only.
        ledger.park(entry(other, 1)).assert().isTrue()
        ledger.parked(board, ref).assert().isEqualTo(entry(board, 3))
        ledger.isParked(SubscriptionId("third-$unique", NAME), ref).assert().isFalse()
    }

    @Test
    fun `a listing reads its own subscription's entries only, with a tenant or after a key`(): Unit = runBlocking {
        val others = bothParked()
        ledger.list(board).assert().containsExactly(entry(board, 3))
        ledger.list(other).assert().containsExactly(others)
        ledger.list(board, tenantId = ref.tenantId).assert().containsExactly(entry(board, 3))
        ledger.list(board, after = AggregateType("sales", "a").ref("tenant", "a")).assert()
            .containsExactly(entry(board, 3))
        ledger.list(board, after = ref).assert().isEmpty()
    }

    @Test
    fun `a due scan reads its own subscription's entries only`(): Unit = runBlocking {
        val others = bothParked()
        ledger.due(board, listOf(PARTITION), FAILED_AT).assert().containsExactly(entry(board, 3))
        ledger.due(board, listOf(PARTITION), ParkedAggregate.NEVER).assert().containsExactly(entry(board, 3))
        ledger.due(other, listOf(PARTITION), ParkedAggregate.NEVER).assert().containsExactly(others)
        ledger.due(board, listOf(PARTITION), ParkedAggregate.NEVER, after = ref).assert().isEmpty()
    }

    @Test
    fun `a count counts its own subscription's entries only`(): Unit = runBlocking {
        bothParked()
        ledger.count(board, 10).assert().isEqualTo(1)
        ledger.count(other, 10).assert().isEqualTo(1)
        ledger.resolveAnywhere(other, ref)
        ledger.count(board, 10).assert().isEqualTo(1)
        ledger.count(other, 10).assert().isEqualTo(0)
    }

    @Test
    fun `a repark replaces its own subscription's entry only`(): Unit = runBlocking {
        val others = bothParked()
        val again = entry(board, 4, reason = "failed again")
        ledger.repark(again).assert().isTrue()
        ledger.parked(board, ref).assert().isEqualTo(again.copyWith(revision = 1))
        ledger.parked(other, ref).assert().isEqualTo(others)
        // A subscription with nothing parked re-parks nothing, whoever else parks the aggregate.
        ledger.resolveAnywhere(other, ref)
        ledger.repark(entry(other, 2)).assert().isFalse()
        ledger.parked(other, ref).assert().isNull()
    }

    @Test
    fun `every amendment changes its own subscription's entry only`(): Unit = runBlocking {
        val others = bothParked()
        val amendments = listOf(
            ParkedAmendment.MarkRecoverable(Recoverable.RECOVERABLE),
            ParkedAmendment.ApplyRetrySpec(SPEC),
            ParkedAmendment.Request(LedgerRequest.SKIP),
            ParkedAmendment.TakeRequest(3),
            ParkedAmendment.ClaimRetry(4, FAILED_AT + 10),
            ParkedAmendment.Schedule(5, FAILED_AT + 20),
            ParkedAmendment.Restate(6, "restated", FAILED_AT + 30, 3, FAILED_AT + 25, SPEC, Recoverable.UNRECOVERABLE),
        )
        amendments.forEach { amendment ->
            ledger.amend(board, ref, amendment).assert().isNotNull()
            ledger.parked(other, ref).assert().isEqualTo(others)
        }
        ledger.parked(board, ref)!!.revision.assert().isEqualTo(amendments.size.toLong())
        // Of a subscription with nothing parked, nothing is amended, whoever else parks the aggregate.
        ledger.resolveAnywhere(other, ref)
        amendments.forEach { ledger.amend(other, ref, it).assert().isNull() }
        ledger.parked(board, ref)!!.revision.assert().isEqualTo(amendments.size.toLong())
    }

    @Test
    fun `a resolve, fenced or not, removes its own subscription's entry only`(): Unit = runBlocking {
        val others = bothParked()
        ledger.resolve(board, ref, PARTITION).assert().isEqualTo(entry(board, 3))
        ledger.parked(other, ref).assert().isEqualTo(others)
        ledger.resolve(board, ref, PARTITION).assert().isNull()
        ledger.resolveAnywhere(board, ref).assert().isNull()
        ledger.parked(other, ref).assert().isEqualTo(others)
        ledger.park(entry(board, 3)).assert().isTrue()
        ledger.resolveAnywhere(other, ref).assert().isEqualTo(others)
        ledger.parked(board, ref).assert().isEqualTo(entry(board, 3))
    }

    protected companion object {
        const val NAME = "Board.onOrder"
        const val PARTITION = 1
        const val FAILED_AT = 1_700_000_000_000L
        val SPEC = ParkedRetrySpec(maxRetries = 4, initialBackoff = 2.minutes, claimTtl = 2.minutes)
    }
}

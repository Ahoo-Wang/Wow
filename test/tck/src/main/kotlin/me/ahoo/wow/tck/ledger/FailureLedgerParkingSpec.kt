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
import me.ahoo.wow.api.ledger.LedgerRequest
import me.ahoo.wow.api.ledger.Recoverable
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedAmendment
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * The parking an operator acts on, part of the [FailureLedger] contract every backend passes: an operator's mark, retry
 * spec or request applies only to the parking it names by its failure time, checked in the ledger's own write, and a
 * request belongs to the parking it was made for, so a park or a repark of another failure time clears it. A
 * backend's test extends it and supplies a ledger from [createLedger].
 */
abstract class FailureLedgerParkingSpec {
    /** A ledger. Ledgers may share storage: each test uses subscriptions of its own. */
    protected abstract fun createLedger(): FailureLedger

    private val subscription = SubscriptionId(CONTEXT, "sub-" + UUID.randomUUID())
    private val ref: AggregateRef = AggregateType(CONTEXT, "order").ref("tenant", "a")
    private val ledger: FailureLedger by lazy { createLedger() }

    private fun entry(fromVersion: Int, failedAt: Long, partition: Int = 0, reason: String = "failed") =
        ParkedAggregate(subscription, ref, partition, fromVersion, reason, failedAt)

    private suspend fun parked(): ParkedAggregate = checkNotNull(ledger.parked(subscription, ref))

    @Test
    fun `an operator's mark, retry spec or request should apply only to the parking it names`(): Unit = runBlocking {
        ledger.park(entry(5, FAILED_AT))
        val other = FAILED_AT + 1
        val amendments = listOf<(Long?) -> ParkedAmendment>(
            { ParkedAmendment.MarkRecoverable(Recoverable.UNRECOVERABLE, it) },
            { ParkedAmendment.ApplyRetrySpec(SPEC, it) },
            { ParkedAmendment.Request(LedgerRequest.SKIP, it) },
        )
        amendments.forEach { amendment ->
            val before = parked()
            ledger.amend(subscription, ref, amendment(other)).assert().isNull()
            parked().assert().isEqualTo(before)
            ledger.amend(subscription, ref, amendment(FAILED_AT))!!.revision.assert().isEqualTo(before.revision + 1)
            // Null names no parking: whichever is there.
            ledger.amend(subscription, ref, amendment(null))!!.revision.assert().isEqualTo(before.revision + 2)
        }
        parked().recoverable.assert().isEqualTo(Recoverable.UNRECOVERABLE)
        parked().retrySpec.assert().isEqualTo(SPEC)
        parked().request.assert().isEqualTo(LedgerRequest.SKIP)
    }

    @Test
    fun `a skip asked of a parking a repark replaced should be refused in the ledger's write`(): Unit = runBlocking {
        ledger.park(entry(5, FAILED_AT))
        val seen = parked()
        // A retry failed again meanwhile: the aggregate is parked anew, at another time.
        ledger.repark(entry(7, FAILED_AT + 10, reason = "failed again")).assert().isTrue()

        ledger.amend(subscription, ref, ParkedAmendment.Request(LedgerRequest.SKIP, seen.failedAt)).assert().isNull()

        parked().request.assert().isNull()
        ledger.amend(subscription, ref, ParkedAmendment.Request(LedgerRequest.SKIP, FAILED_AT + 10))!!
            .request.assert().isEqualTo(LedgerRequest.SKIP)
    }

    @Test
    fun `a skip racing a repark should never be left on the parking the repark made`(): Unit = runBlocking {
        repeat(RACES) { race ->
            val first = FAILED_AT + race * 100
            ledger.park(entry(5, first))
            coroutineScope {
                listOf(
                    async(Dispatchers.Default) { ledger.repark(entry(6, first + 1, reason = "failed again")) },
                    async(Dispatchers.Default) {
                        ledger.amend(subscription, ref, ParkedAmendment.Request(LedgerRequest.SKIP, first))
                    },
                ).awaitAll()
            }
            // Before the repark, the request was made for the parking it replaced, and cleared; after, refused.
            parked().failedAt.assert().isEqualTo(first + 1)
            parked().request.assert().isNull()
            ledger.resolveAnywhere(subscription, ref)
        }
    }

    @Test
    fun `a request should be cleared by a park or repark of another failure time, and kept by a re-home`(): Unit =
        runBlocking {
            ledger.park(entry(5, FAILED_AT, partition = 1))
            ledger.amend(subscription, ref, ParkedAmendment.Request(LedgerRequest.RETRY, FAILED_AT))

            // Re-homed to another partition after the source gained some: the same parking, the request stays.
            ledger.repark(entry(5, FAILED_AT, partition = 3), from = 1).assert().isTrue()
            parked().partition.assert().isEqualTo(3)
            parked().request.assert().isEqualTo(LedgerRequest.RETRY)

            // Parked from an earlier version, at another time: another parking, the request goes.
            ledger.park(entry(2, FAILED_AT + 20, partition = 3)).assert().isTrue()
            parked().request.assert().isNull()

            ledger.amend(subscription, ref, ParkedAmendment.Request(LedgerRequest.SKIP, FAILED_AT + 20))
            ledger.repark(entry(4, FAILED_AT + 30, partition = 3), from = 3).assert().isTrue()
            parked().request.assert().isNull()
            parked().failedAt.assert().isEqualTo(FAILED_AT + 30)
        }

    private companion object {
        const val CONTEXT = "ledger-parking"
        const val FAILED_AT = 1_700_000_000_000L
        const val RACES = 20
        val SPEC = ParkedRetrySpec(maxRetries = 3, initialBackoff = 1.minutes, claimTtl = 2.minutes)
    }
}

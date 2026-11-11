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

package me.ahoo.wow.spi.ledger

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.ledger.LedgerRequest
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test

/**
 * [FailureLedger.amend]'s generic path ([amendByRevision]), which every ledger takes for an amendment it has no
 * statement for: here its reads and writes are functions, so a test puts another writer's change between them.
 */
class AmendByRevisionTest {
    private val entry = ParkedAggregate.of(
        SubscriptionId("shop", "Projector.on"),
        AggregateType("shop", "cart").ref("tenant", "x"),
        0,
        1,
        "failed",
        100,
    )

    @Test
    fun `a write that lost to another reads the entry again and applies the amendment to what that write left`(): Unit =
        runBlocking {
            var stored = entry
            var reads = 0
            val spec = ParkedRetrySpec.of(maxRetries = 2)
            val amended = amendByRevision(ParkedAmendment.ApplyRetrySpec(spec), {
                reads++
                stored.also {
                    // Another writer, between this read and its write.
                    if (reads == 1) stored = checkNotNull(stored.amended(ParkedAmendment.Request(LedgerRequest.RETRY)))
                }
            }) { next, revision -> (stored.revision == revision).also { if (it) stored = next } }
            reads.assert().isEqualTo(2)
            amended.assert().isEqualTo(stored)
            stored.revision.assert().isEqualTo(2)
            stored.retrySpec.assert().isEqualTo(spec)
            stored.request.assert().isEqualTo(LedgerRequest.RETRY)
        }

    @Test
    fun `nothing is written for an entry not parked, nor for an amendment naming a revision the entry left`(): Unit =
        runBlocking {
            val written: suspend (ParkedAggregate, Long) -> Boolean = { _, _ -> error("Nothing is written.") }
            amendByRevision(ParkedAmendment.Request(LedgerRequest.SKIP), { null }, written).assert().isNull()
            amendByRevision(ParkedAmendment.ClaimRetry(3, 200), { entry }, written).assert().isNull()
        }
}

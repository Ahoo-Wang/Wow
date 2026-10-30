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

package me.ahoo.wow.ops.domain.parked

import me.ahoo.wow.api.ledger.ApplyParkedRetrySpec
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.MarkParkedRecoverable
import me.ahoo.wow.api.ledger.RetryParked
import me.ahoo.wow.api.ledger.SkipParked
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.ops.api.parked.ApplyRetrySpec
import me.ahoo.wow.ops.api.parked.ApplyRetrySpecRequested
import me.ahoo.wow.ops.api.parked.MarkRecoverable
import me.ahoo.wow.ops.api.parked.MarkRecoverableRequested
import me.ahoo.wow.ops.api.parked.ParkedRecorded
import me.ahoo.wow.ops.api.parked.RecordActionRefused
import me.ahoo.wow.ops.api.parked.RecordParked
import me.ahoo.wow.ops.api.parked.Retry
import me.ahoo.wow.ops.api.parked.RetryRequested
import me.ahoo.wow.ops.api.parked.Skip
import me.ahoo.wow.ops.api.parked.SkipRequested
import me.ahoo.wow.test.ProcessorSpec
import me.ahoo.wow.test.sagaSpec
import org.junit.jupiter.api.Test

/** An operator's request on an entry goes back to its owning service as the ledger's system command (D141). */
class ParkedOperationSagaTest {
    private val handlers = parkedOperationSagaHandlers()
    private val target = Ledger.targetOf("eshop-payment", ParkedSpec.ID)

    /** The entry, as the owning service recorded it. */
    private fun ProcessorSpec.recorded() = givenAggregate(
        parkedModel(),
        ParkedSpec.ID,
        ParkedRecorded(ParkedSpec.ENTRY)
    )

    @Test
    fun `a retry and a skip go to the context that holds the entry`() = sagaSpec(handlers) {
        recorded()
        whenEvent(RetryRequested(), by = Retry::class) {
            expectCommand(RetryParked.of(ParkedSpec.SUBSCRIPTION, ParkedSpec.AGGREGATE.toRef()), to = target)
            expectDeterministic()
        }
        // A skip always names a parking: without the one the operator saw, the one the entry records.
        whenEvent(SkipRequested("not needed"), by = Skip::class) {
            expectCommand(
                SkipParked.of(ParkedSpec.SUBSCRIPTION, ParkedSpec.AGGREGATE.toRef(), ParkedSpec.ENTRY.failedAt),
                to = target,
            )
        }
        // The parking the operator acted on goes with it, for the owning service to refuse another.
        whenEvent(SkipRequested(failedAt = 100), by = Skip::class) {
            expectCommand(SkipParked.of(ParkedSpec.SUBSCRIPTION, ParkedSpec.AGGREGATE.toRef(), 100), to = target)
        }
        whenEvent(RetryRequested(failedAt = 100), by = Retry::class) {
            expectCommand(RetryParked.of(ParkedSpec.SUBSCRIPTION, ParkedSpec.AGGREGATE.toRef(), 100), to = target)
        }
    }

    @Test
    fun `a mark and a retry spec carry what the operator asked`() = sagaSpec(handlers) {
        recorded()
        whenEvent(MarkRecoverableRequested("recoverable"), by = MarkRecoverable::class) {
            expectCommand(
                MarkParkedRecoverable.of(ParkedSpec.SUBSCRIPTION, ParkedSpec.AGGREGATE.toRef(), "recoverable"),
                to = target
            )
        }
        whenEvent(ApplyRetrySpecRequested(1, "PT1M", null), by = ApplyRetrySpec::class) {
            expectCommand(
                ApplyParkedRetrySpec.of(ParkedSpec.SUBSCRIPTION, ParkedSpec.AGGREGATE.toRef(), 1, "PT1M", null),
                to = target,
            )
        }
    }

    @Test
    fun `a command the owning service refuses is recorded on the entry (D226)`() = sagaSpec(handlers) {
        val parked = recorded()
        whenEvent(RetryRequested(), by = Retry::class) {
            val retry = commands.single()
            whenRejected(retry, FrameworkCodes.AUTH_FORBIDDEN, "Not from the ops saga.", commandType = Ledger.RETRY) {
                expectCommand(
                    RecordActionRefused(Ledger.RETRY, FrameworkCodes.AUTH_FORBIDDEN, "Not from the ops saga."),
                    to = parked.ref,
                )
            }
        }
    }

    @Test
    fun `a record of the service sends nothing back`() = sagaSpec(handlers) {
        givenAggregate(parkedModel(), ParkedSpec.ID)
        whenEvent(ParkedRecorded(ParkedSpec.ENTRY), by = RecordParked::class) { expectNoCommand() }
    }
}

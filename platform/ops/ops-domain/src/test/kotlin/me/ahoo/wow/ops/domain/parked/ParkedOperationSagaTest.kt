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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.ledger.ApplyParkedRetrySpec
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.MarkParkedRecoverable
import me.ahoo.wow.api.ledger.ResumeParked
import me.ahoo.wow.api.ledger.SkipParked
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.ops.api.OpsContext
import me.ahoo.wow.ops.api.parked.ApplyRetrySpecRequested
import me.ahoo.wow.ops.api.parked.MarkRecoverableRequested
import me.ahoo.wow.ops.api.parked.ParkedRecorded
import me.ahoo.wow.ops.api.parked.ResumeRequested
import me.ahoo.wow.ops.api.parked.SkipRequested
import me.ahoo.wow.test.sagaSpec
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.api.Test

/** An operator's request on an entry goes back to its owning service as the ledger's system command (D141). */
class ParkedOperationSagaTest {
    private val handlers = parkedOperationSagaHandlers(Dependencies.of())
    private val parked = AggregateType(OpsContext.NAME, OpsContext.PARKED).ref("(0)", ParkedSpec.ID)
    private val state = ParkedState().apply { onSourcing(ParkedRecorded(ParkedSpec.ENTRY)) }
    private val target = Ledger.targetOf("eshop-payment", ParkedSpec.ID)

    private fun requested(event: Any) = stateEvent(parked, state, event, version = 2)

    @Test
    fun `a resume and a skip go to the context that holds the entry`() = sagaSpec(handlers) {
        whenEvent(requested(ResumeRequested())) {
            expectCommand(ResumeParked(ParkedSpec.SUBSCRIPTION, ParkedSpec.AGGREGATE), to = target)
            expectDeterministic()
        }
        whenEvent(requested(SkipRequested("not needed"))) {
            expectCommand(SkipParked(ParkedSpec.SUBSCRIPTION, ParkedSpec.AGGREGATE), to = target)
        }
    }

    @Test
    fun `a mark and a retry spec carry what the operator asked`() = sagaSpec(handlers) {
        whenEvent(requested(MarkRecoverableRequested("RECOVERABLE"))) {
            expectCommand(
                MarkParkedRecoverable(ParkedSpec.SUBSCRIPTION, ParkedSpec.AGGREGATE, "RECOVERABLE"),
                to = target
            )
        }
        whenEvent(requested(ApplyRetrySpecRequested(1, "PT1M", null))) {
            expectCommand(
                ApplyParkedRetrySpec(ParkedSpec.SUBSCRIPTION, ParkedSpec.AGGREGATE, 1, "PT1M", null),
                to = target,
            )
        }
    }

    @Test
    fun `a record of the service sends nothing back`() = sagaSpec(handlers) {
        whenEvent(stateEvent(parked, state, ParkedRecorded(ParkedSpec.ENTRY))) { expectNoCommand() }
    }
}

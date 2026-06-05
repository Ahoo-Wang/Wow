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

package me.ahoo.wow.messaging.compensation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class EventCompensateSupporterBehaviorTest {

    private val aggregateId = "wow-core-test.messaging_aggregate".toNamedAggregate().aggregateId("aggregate-id")

    @Test
    fun `compensate delegates event functions to domain event compensator`() {
        val domainCompensator = mockk<DomainEventCompensator>()
        val stateCompensator = mockk<StateEventCompensator>()
        val target = CompensationTarget(function = function(FunctionKind.EVENT))
        every { domainCompensator.compensate(aggregateId, 3, target) } returns Mono.just(5)
        val supporter = EventCompensateSupporter(domainCompensator, stateCompensator)

        StepVerifier.create(supporter.compensate(aggregateId, 3, target))
            .expectNext(5)
            .verifyComplete()

        verify(exactly = 1) { domainCompensator.compensate(aggregateId, 3, target) }
        verify(exactly = 0) { stateCompensator.compensate(any(), any(), any()) }
    }

    @Test
    fun `compensate delegates state event functions to state event compensator`() {
        val domainCompensator = mockk<DomainEventCompensator>()
        val stateCompensator = mockk<StateEventCompensator>()
        val target = CompensationTarget(function = function(FunctionKind.STATE_EVENT))
        every { stateCompensator.compensate(aggregateId, 4, target) } returns Mono.just(7)
        val supporter = EventCompensateSupporter(domainCompensator, stateCompensator)

        StepVerifier.create(supporter.compensate(aggregateId, 4, target))
            .expectNext(7)
            .verifyComplete()

        verify(exactly = 0) { domainCompensator.compensate(any(), any(), any()) }
        verify(exactly = 1) { stateCompensator.compensate(aggregateId, 4, target) }
    }

    @Test
    fun `compensate returns error for unsupported function kind`() {
        val supporter = EventCompensateSupporter(mockk(), mockk())
        val target = CompensationTarget(function = function(FunctionKind.COMMAND))

        StepVerifier.create(supporter.compensate(aggregateId, 1, target))
            .expectErrorSatisfies {
                (it is IllegalArgumentException).assert().isTrue()
                it.message.assert().isEqualTo("Unsupported FunctionKind:COMMAND")
            }
            .verify()
    }

    private fun function(kind: FunctionKind): FunctionInfoData =
        FunctionInfoData(
            functionKind = kind,
            contextName = "context",
            processorName = "processor",
            name = "function",
        )
}

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

package me.ahoo.wow.event.compensation

import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.messaging.compensation.CompensationTarget
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class StateEventCompensatorTest {
    @Test
    fun resend() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId()
        val eventStore = InMemoryEventStore()
        val eventStream = generateEventStream(aggregateId)
        eventStore.append(eventStream).block()
        val domainEventCompensator =
            StateEventCompensator(ConstructorStateAggregateFactory, eventStore, InMemoryStateEventBus())
        domainEventCompensator.resend(
            aggregateId = aggregateId,
            headVersion = 1,
            tailVersion = 2,
            target = CompensationTarget(
                function = FunctionInfoData(FunctionKind.STATE_EVENT, "test", "test", "test")
            )
        ).test()
            .expectNextCount(1)
            .verifyComplete()
    }
}

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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.compensation.COMPENSATION_ID
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.IgnoredErrorEvent
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class StateEventCompensatorTest {

    @Test
    fun `resend rebuilds state and sends compensated state events in requested range`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("state-compensate")
        val eventStore = InMemoryEventStore()
        val stateEventBus = RecordingStateEventBus()
        val first = generateEventStream(
            aggregateId = aggregateId,
            aggregateVersion = 0,
            eventCount = 1,
            createdEventSupplier = { MockAggregateCreated("state-v1") },
        )
        val second = generateEventStream(
            aggregateId = aggregateId,
            aggregateVersion = 1,
            eventCount = 1,
            createdEventSupplier = { MockAggregateCreated("state-v2") },
        )
        val target = compensationTarget(FunctionKind.STATE_EVENT)
        val compensator = StateEventCompensator(
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStore = eventStore,
            stateEventBus = stateEventBus,
        )

        StepVerifier.create(eventStore.append(first)).verifyComplete()
        StepVerifier.create(eventStore.append(second)).verifyComplete()
        StepVerifier.create(compensator.resend(aggregateId, headVersion = 2, tailVersion = 2, target = target))
            .expectNext(1)
            .verifyComplete()

        val sent = stateEventBus.sent.single()
        sent.version.assert().isEqualTo(2)
        sent.header[COMPENSATION_ID].assert().isEqualTo(target.id)
        (sent.state as MockStateAggregate).data.assert().isEqualTo("state-v2")
    }

    @Test
    fun `resend skips ignored initial error streams`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("state-compensate-ignored")
        val eventStore = InMemoryEventStore()
        val stateEventBus = RecordingStateEventBus()
        val ignoredInitialErrorStream = generateEventStream(
            aggregateId = aggregateId,
            aggregateVersion = 0,
            eventCount = 1,
            createdEventSupplier = { IgnoredErrorEvent("failed", "failed create") },
        )
        val target = compensationTarget(FunctionKind.STATE_EVENT)
        val compensator = StateEventCompensator(
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStore = eventStore,
            stateEventBus = stateEventBus,
        )

        StepVerifier.create(eventStore.append(ignoredInitialErrorStream)).verifyComplete()
        StepVerifier.create(
            compensator.resend(
                aggregateId = aggregateId,
                headVersion = 1,
                tailVersion = Int.MAX_VALUE,
                target = target,
            )
        )
            .expectNext(0)
            .verifyComplete()

        stateEventBus.sent.assert().isEmpty()
    }

    @Test
    fun `resend skips later streams when initial stream did not source state`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("state-compensate-unsourced-later")
        val eventStore = InMemoryEventStore()
        val stateEventBus = RecordingStateEventBus()
        val ignoredInitialErrorStream = generateEventStream(
            aggregateId = aggregateId,
            aggregateVersion = 0,
            eventCount = 1,
            createdEventSupplier = { IgnoredErrorEvent("failed", "failed create") },
        )
        val laterStream = generateEventStream(
            aggregateId = aggregateId,
            aggregateVersion = 1,
            eventCount = 1,
            createdEventSupplier = { MockAggregateCreated("state-v2") },
        )
        val target = compensationTarget(FunctionKind.STATE_EVENT)
        val compensator = StateEventCompensator(
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStore = eventStore,
            stateEventBus = stateEventBus,
        )

        StepVerifier.create(eventStore.append(ignoredInitialErrorStream)).verifyComplete()
        StepVerifier.create(eventStore.append(laterStream)).verifyComplete()
        StepVerifier.create(
            compensator.resend(
                aggregateId = aggregateId,
                headVersion = 1,
                tailVersion = Int.MAX_VALUE,
                target = target,
            )
        )
            .expectNext(0)
            .verifyComplete()

        stateEventBus.sent.assert().isEmpty()
    }

    private class RecordingStateEventBus : StateEventBus {
        val sent = mutableListOf<StateEvent<*>>()

        override fun send(message: StateEvent<*>): Mono<Void> =
            Mono.fromRunnable {
                sent += message
            }

        override fun receive(subscription: MessageSubscription): Flux<StateEventExchange<*>> = Flux.empty()
    }
}

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
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.messaging.compensation.COMPENSATION_ID
import me.ahoo.wow.messaging.compensation.CompensationTarget
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class DomainEventCompensatorBehaviorTest {

    @Test
    fun `compensate marks stream and sends it to event bus`() {
        val eventBus = RecordingDomainEventBus()
        val target = compensationTarget(FunctionKind.EVENT)
        val eventStream = generateEventStream(MOCK_AGGREGATE_METADATA.aggregateId("compensate-1"))
        val compensator = DomainEventCompensator(InMemoryEventStore(), eventBus)

        StepVerifier.create(compensator.compensate(eventStream, target))
            .verifyComplete()

        eventBus.sent.single().assert().isSameAs(eventStream)
        eventStream.header[COMPENSATION_ID].assert().isEqualTo(target.id)
    }

    @Test
    fun `resend loads matching version range and compensates each stream`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("compensate-2")
        val eventStore = InMemoryEventStore()
        val eventBus = RecordingDomainEventBus()
        val first = generateEventStream(aggregateId, aggregateVersion = 0)
        val second = generateEventStream(aggregateId, aggregateVersion = 1)
        val target = compensationTarget(FunctionKind.EVENT)
        val compensator = DomainEventCompensator(eventStore, eventBus)

        StepVerifier.create(eventStore.append(first)).verifyComplete()
        StepVerifier.create(eventStore.append(second)).verifyComplete()
        StepVerifier.create(compensator.resend(aggregateId, headVersion = 2, tailVersion = 2, target = target))
            .expectNext(1)
            .verifyComplete()

        eventBus.sent.size.assert().isEqualTo(1)
        eventBus.sent.single().version.assert().isEqualTo(2)
        eventBus.sent.single().header[COMPENSATION_ID].assert().isEqualTo(target.id)
    }

    private class RecordingDomainEventBus : DomainEventBus {
        val sent = mutableListOf<DomainEventStream>()

        override fun send(message: DomainEventStream): Mono<Void> =
            Mono.fromRunnable {
                sent += message
            }

        override fun receive(namedAggregates: Set<NamedAggregate>): Flux<EventStreamExchange> = Flux.empty()
    }
}

internal fun compensationTarget(functionKind: FunctionKind): CompensationTarget =
    CompensationTarget(
        id = "compensation-$functionKind",
        function = FunctionInfoData(
            functionKind = functionKind,
            contextName = "test",
            processorName = "processor",
            name = "handler",
        ),
    )

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

package me.ahoo.wow.test.spec.event

import me.ahoo.wow.configuration.asRequiredNamedAggregate
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.writeReceiverGroup
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.test.spec.eventsourcing.MockDomainEventStreams
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.kotlin.test.test
import java.time.Duration

abstract class DomainEventBusSpec {
    protected val namedAggregateForSend = MockDomainEventBusSendEvent::class.java.asRequiredNamedAggregate()
    protected val namedAggregateForReceive = MockDomainEventBusReceiveEvent::class.java.asRequiredNamedAggregate()

    protected abstract fun createEventBus(): DomainEventBus

    @Test
    fun send() {
        val eventBus = createEventBus()

        val eventStream = MockDomainEventStreams.generateEventStream(
            aggregateId = namedAggregateForSend.asAggregateId(GlobalIdGenerator.generateAsString()),
            eventCount = 1,
            createdEventSupplier = { MockDomainEventBusSendEvent(GlobalIdGenerator.generateAsString()) },
        )
        eventBus.send(eventStream)
            .test()
            .verifyComplete()
    }

    @Test
    fun receive() {
        val eventBus = createEventBus()
        eventBus.receive(setOf(namedAggregateForReceive))
            .writeReceiverGroup(GlobalIdGenerator.generateAsString())
            .test()
            .consumeSubscriptionWith {
                Flux.range(0, 10)
                    .flatMap {
                        val eventStream = MockDomainEventStreams.generateEventStream(
                            aggregateId = namedAggregateForReceive.asAggregateId(GlobalIdGenerator.generateAsString()),
                            eventCount = 1,
                            createdEventSupplier = {
                                MockDomainEventBusReceiveEvent(
                                    GlobalIdGenerator.generateAsString(),
                                )
                            },
                        )
                        eventBus.send(eventStream)
                    }
                    .delaySubscription(Duration.ofSeconds(1))
                    .subscribe()
            }
            .expectNextCount(10)
            .verifyTimeout(Duration.ofSeconds(2))
    }
}

data class MockDomainEventBusSendEvent(val state: String)
data class MockDomainEventBusReceiveEvent(val state: String)

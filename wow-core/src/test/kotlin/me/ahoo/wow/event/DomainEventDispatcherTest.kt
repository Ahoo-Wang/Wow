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

package me.ahoo.wow.event

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.dispatcher.DomainEventDispatcher
import me.ahoo.wow.event.dispatcher.DomainEventFunctionRegistrar
import me.ahoo.wow.event.dispatcher.DomainEventHandler
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockStateAggregate
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.time.Duration

class DomainEventDispatcherTest {

    @Test
    fun `dispatcher routes event and state event functions to their buses`() {
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId("dispatcher-aggregate")
        val namedAggregate = MOCK_AGGREGATE_METADATA.materialize()
        val domainEventBus = InMemoryDomainEventBus()
        val stateEventBus = InMemoryStateEventBus()
        val registrar = registerFunctions(namedAggregate)
        val domainHandled = Sinks.many().replay().all<String>()
        val stateHandled = Sinks.many().replay().all<String>()
        val dispatcher = DomainEventDispatcher(
            name = "test.DomainEventDispatcher",
            domainEventBus = domainEventBus,
            stateEventBus = stateEventBus,
            functionRegistrar = registrar,
            eventHandler = object : DomainEventHandler {
                override fun handle(context: DomainEventExchange<*>): Mono<Void> =
                    Mono.fromRunnable {
                        val eventFunction = checkNotNull(context.getEventFunction())
                        when (eventFunction.functionKind) {
                            FunctionKind.EVENT -> domainHandled.tryEmitNext(eventFunction.name).orThrow()
                            FunctionKind.STATE_EVENT -> stateHandled.tryEmitNext(eventFunction.name).orThrow()
                            else -> error("Unexpected function kind [${eventFunction.functionKind}].")
                        }
                    }
            },
        )
        val eventStream = MockAggregateCreated("created").toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId),
            aggregateVersion = 0,
        )
        val stateEvent = eventStream.toStateEvent(MockStateAggregate(aggregateId.id))
        dispatcher.start()

        domainEventBus.subscriberCount(namedAggregate).assert().isEqualTo(1)
        stateEventBus.subscriberCount(namedAggregate).assert().isEqualTo(1)
        StepVerifier.create(domainHandled.asFlux().take(1))
            .then {
                StepVerifier.create(domainEventBus.send(eventStream)).verifyComplete()
            }
            .expectNext("domain")
            .expectComplete()
            .verify(Duration.ofSeconds(2))
        StepVerifier.create(stateHandled.asFlux().take(1))
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(100))
            .thenCancel()
            .verify(Duration.ofSeconds(2))

        StepVerifier.create(stateHandled.asFlux().take(1))
            .then {
                StepVerifier.create(stateEventBus.send(stateEvent)).verifyComplete()
            }
            .expectNext("state")
            .expectComplete()
            .verify(Duration.ofSeconds(2))

        StepVerifier.create(dispatcher.stopGracefully())
            .expectComplete()
            .verify(Duration.ofSeconds(2))
    }

    private fun registerFunctions(namedAggregate: NamedAggregate): DomainEventFunctionRegistrar =
        DomainEventFunctionRegistrar().apply {
            register(
                RecordingFunction(
                    name = "domain",
                    functionKind = FunctionKind.EVENT,
                    namedAggregate = namedAggregate,
                )
            )
            register(
                RecordingFunction(
                    name = "state",
                    functionKind = FunctionKind.STATE_EVENT,
                    namedAggregate = namedAggregate,
                )
            )
        }

    private class RecordingFunction(
        override val name: String,
        override val functionKind: FunctionKind,
        private val namedAggregate: NamedAggregate,
    ) : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
        override val contextName: String = namedAggregate.contextName
        override val supportedType: Class<*> = MockAggregateCreated::class.java
        override val supportedTopics: Set<NamedAggregate> = setOf(namedAggregate)
        override val processor: Any = this

        override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

        override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = Mono.empty<Void>()
    }
}

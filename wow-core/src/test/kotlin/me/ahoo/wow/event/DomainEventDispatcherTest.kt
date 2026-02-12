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

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.event.dispatcher.DefaultDomainEventHandler
import me.ahoo.wow.event.dispatcher.DomainEventDispatcher
import me.ahoo.wow.event.dispatcher.DomainEventFunctionFilter
import me.ahoo.wow.event.dispatcher.DomainEventFunctionRegistrar
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.tck.mock.MockAggregateChanged
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.locks.LockSupport

internal class DomainEventDispatcherTest {
    private val namedAggregate = DomainEventDispatcherTest::class.java.requiredNamedAggregate()
    private val domainEventBus: DomainEventBus = InMemoryDomainEventBus()
    private val stateEventBus: StateEventBus = InMemoryStateEventBus()
    private val functionRegistrar = DomainEventFunctionRegistrar()

    @Test
    fun run() {
        val sink = Sinks.empty<Void>()
        functionRegistrar.registerProcessor(object : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
            override val contextName: String
                get() = "test"
            override val name: String
                get() = "test"
            override val supportedType: Class<*>
                get() = MockAggregateCreated::class.java
            override val processor: Any
                get() = Any()
            override val functionKind: FunctionKind
                get() = FunctionKind.EVENT
            override val supportedTopics: Set<NamedAggregate>
                get() = setOf(namedAggregate)

            override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? {
                return null
            }

            override fun invoke(exchange: DomainEventExchange<*>): Mono<*> {
                return Mono.fromRunnable<Void> { sink.tryEmitEmpty() }
            }
        })

        val chain = FilterChainBuilder<DomainEventExchange<*>>()
            .addFilter(DomainEventFunctionFilter(SimpleServiceProvider()))
            .build()

        val domainEventProcessor =
            DomainEventDispatcher(
                name = "test.DomainEventProcessor",
                domainEventBus = domainEventBus,
                stateEventBus = stateEventBus,
                functionRegistrar = functionRegistrar,
                eventHandler = DefaultDomainEventHandler(chain).metrizable(),
            )
        domainEventProcessor.start()
        val aggregateId = namedAggregate.aggregateId()
        val eventStream =
            MockAggregateCreated(
                GlobalIdGenerator.generateAsString(),
            ).toDomainEventStream(GivenInitializationCommand(aggregateId), Version.INITIAL_VERSION)
        domainEventBus.send(eventStream).block()

        sink.asMono().block(Duration.ofSeconds(1))

        val eventStreamIgnore =
            MockAggregateChanged(
                GlobalIdGenerator.generateAsString(),
            ).toDomainEventStream(GivenInitializationCommand(aggregateId), Version.INITIAL_VERSION)

        domainEventBus.send(eventStreamIgnore).block()

        LockSupport.parkNanos(Duration.ofSeconds(1).toNanos())
        domainEventProcessor.close()
    }
}

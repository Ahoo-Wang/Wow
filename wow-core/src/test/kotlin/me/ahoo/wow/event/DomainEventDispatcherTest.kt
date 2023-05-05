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
import me.ahoo.wow.configuration.asRequiredNamedAggregate
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.handler.FilterChainBuilder
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.tck.eventsourcing.Created
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration

internal class DomainEventDispatcherTest {
    private val namedAggregate = DomainEventDispatcherTest::class.java.asRequiredNamedAggregate()
    private val domainEventBus: DomainEventBus = InMemoryDomainEventBus()
    private val handlerRegistrar = DomainEventFunctionRegistrar()

    @Test
    fun run() {
        val sink = Sinks.empty<Void>()
        handlerRegistrar.register(object : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
            override val supportedType: Class<*>
                get() = Created::class.java
            override val processor: Any
                get() = Any()
            override val supportedTopics: Set<Any>
                get() = setOf(namedAggregate)

            override fun handle(exchange: DomainEventExchange<*>): Mono<*> {
                return Mono.fromRunnable<Void> { sink.tryEmitEmpty() }
            }
        })

        val chain = FilterChainBuilder<DomainEventExchange<Any>>()
            .addFilter(DomainEventFunctionFilter(SimpleServiceProvider()))
            .build()

        val domainEventProcessor =
            DomainEventDispatcher(
                name = "test.DomainEventProcessor",
                domainEventBus = domainEventBus,
                functionRegistrar = handlerRegistrar,
                eventHandler = DomainEventHandler(chain),
            )
        domainEventProcessor.run()
        val aggregateId = namedAggregate.asAggregateId()
        val eventStream =
            Created().asDomainEventStream(GivenInitializationCommand(aggregateId), Version.INITIAL_VERSION)
        domainEventBus.send(eventStream).block()

        sink.asMono().block(Duration.ofSeconds(1))

        domainEventProcessor.close()
    }
}

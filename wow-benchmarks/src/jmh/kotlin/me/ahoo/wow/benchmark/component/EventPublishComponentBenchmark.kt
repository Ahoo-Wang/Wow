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

package me.ahoo.wow.benchmark.component

import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.benchmark.fixture.BenchmarkEvents
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.event.SimpleDomainEvent
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.SimpleMessageFunctionRegistrar
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono

@State(Scope.Thread)
open class EventPublishComponentBenchmark {
    private lateinit var domainEventBus: InMemoryDomainEventBus
    private lateinit var stateEventBus: InMemoryStateEventBus
    private lateinit var registrar: SimpleMessageFunctionRegistrar<MessageFunction<*, DomainEventExchange<*>, *>>
    private lateinit var domainEvent: SimpleDomainEvent<CartItemAdded>
    private lateinit var eventStream: DomainEventStream
    private lateinit var stateAggregate: StateAggregate<*>
    private lateinit var stateEvent: StateEvent<*>

    @Setup
    fun setup() {
        domainEventBus = InMemoryDomainEventBus()
        domainEventBus.receive(MessageSubscription(BenchmarkAggregates.namedAggregate)).subscribe()
        stateEventBus = InMemoryStateEventBus()
        stateEventBus.receive(MessageSubscription(BenchmarkAggregates.namedAggregate)).subscribe()

        registrar = SimpleMessageFunctionRegistrar()
        registrar.register(BenchmarkEventHandler(BenchmarkAggregates.namedAggregate))

        val aggregateId = BenchmarkAggregates.aggregateId()
        domainEvent = SimpleDomainEvent(
            id = "event-id",
            body = CartItemAdded(CartItem("productId")),
            aggregateId = aggregateId,
            version = 1,
            commandId = "command-id",
        )
        eventStream = BenchmarkEvents.singleEventStream()
        stateAggregate = ConstructorStateAggregateFactory.create(
            BenchmarkAggregates.cartMetadata.state,
            eventStream.aggregateId,
        )
        stateEvent = eventStream.copy().toStateEvent(stateAggregate)
    }

    @TearDown
    fun tearDown() {
        stateEventBus.close()
        domainEventBus.close()
    }

    @Benchmark
    fun publishDomainEventStream(blackhole: Blackhole) {
        val result = domainEventBus.send(eventStream).block()
        blackhole.consume(result)
    }

    @Benchmark
    fun lookupEventFunction(blackhole: Blackhole) {
        val functions = registrar.supportedFunctions(domainEvent).count()
        blackhole.consume(functions)
    }

    @Benchmark
    fun copyStateEvent(blackhole: Blackhole) {
        val copied = eventStream.copy().toStateEvent(stateAggregate)
        blackhole.consume(copied)
    }

    @Benchmark
    fun publishStateEvent(blackhole: Blackhole) {
        val result = stateEventBus.send(stateEvent).block()
        blackhole.consume(result)
    }
}

private class BenchmarkEventHandler(
    private val topic: NamedAggregate,
) : MessageFunction<Any, DomainEventExchange<*>, Mono<Any>> {
    override val supportedType: Class<*> = CartItemAdded::class.java
    override val supportedTopics: Set<NamedAggregate> = setOf(topic)
    override val processor: Any = this
    override val functionKind: FunctionKind = FunctionKind.EVENT
    override val contextName: String = topic.contextName
    override val name: String = "benchmark-event-handler"
    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null
    override fun invoke(exchange: DomainEventExchange<*>): Mono<Any> = Mono.empty()
}

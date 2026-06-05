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

package me.ahoo.wow.saga

import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.cartAggregateMetadata
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEvent
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.SimpleMessageFunctionRegistrar
import me.ahoo.wow.modeling.aggregateId
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono

@State(Scope.Benchmark)
open class StatelessSagaBenchmark {
    private lateinit var sagaDelegate: BenchmarkSagaDelegate
    private lateinit var exchange: DomainEventExchange<CartItemAdded>
    private lateinit var registrar: SimpleMessageFunctionRegistrar<MessageFunction<*, DomainEventExchange<*>, *>>

    @Setup
    fun setup() {
        val topic = cartAggregateMetadata.namedAggregate
        val aggregateId = cartAggregateMetadata.aggregateId()

        sagaDelegate = BenchmarkSagaDelegate(topic)

        registrar = SimpleMessageFunctionRegistrar()
        registrar.register(sagaDelegate)

        val domainEvent = SimpleDomainEvent(
            id = "event-id",
            body = CartItemAdded(me.ahoo.wow.example.api.cart.CartItem("productId")),
            aggregateId = aggregateId,
            version = 1,
            commandId = "command-id",
        )
        exchange = SimpleDomainEventExchange(domainEvent)
    }

    @Benchmark
    fun functionLookup(blackhole: Blackhole) {
        val functions = registrar.supportedFunctions(exchange.message).toList()
        blackhole.consume(functions)
    }

    @Benchmark
    fun sagaDelegateInvoke(blackhole: Blackhole) {
        val result = sagaDelegate.invoke(exchange).block()
        blackhole.consume(result)
    }
}

private class BenchmarkSagaDelegate(
    private val topic: NamedAggregate,
) : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
    override val supportedType: Class<*> = CartItemAdded::class.java
    override val supportedTopics: Set<NamedAggregate> = setOf(topic)
    override val processor: Any = this
    override val functionKind: FunctionKind = FunctionKind.EVENT
    override val contextName: String = topic.contextName
    override val name: String = "benchmark-saga"
    @Suppress("UNCHECKED_CAST")
    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null as A?
    override fun invoke(exchange: DomainEventExchange<*>): Mono<Any> = Mono.empty()
}

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

import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.function.SimpleMessageFunctionRegistrar
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import reactor.core.publisher.Mono

@State(Scope.Benchmark)
open class EventDispatcherBenchmark {
    private lateinit var registrar: SimpleMessageFunctionRegistrar<MessageFunction<*, DomainEventExchange<*>, *>>
    private lateinit var domainEvent: SimpleDomainEvent<CartItemAdded>

    @Setup
    fun setup() {
        registrar = SimpleMessageFunctionRegistrar()
        val topic = BenchmarkAggregates.cartMetadata.namedAggregate
        registrar.register(BenchmarkEventHandler(topic))

        val aggregateId = BenchmarkAggregates.aggregateId()
        domainEvent = SimpleDomainEvent(
            id = "event-id",
            body = CartItemAdded(me.ahoo.wow.example.api.cart.CartItem("productId")),
            aggregateId = aggregateId,
            version = 1,
            commandId = "command-id",
        )
    }

    @Benchmark
    fun functionLookup(blackhole: Blackhole) {
        val functions = registrar.supportedFunctions(domainEvent).count()
        blackhole.consume(functions)
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

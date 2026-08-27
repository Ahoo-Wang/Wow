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
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory.toStateAggregate
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class SimpleDomainEventExchangeTest {

    private val aggregateId = FIXTURE_NAMED_AGGREGATE.toNamedAggregate().aggregateId("exchange-aggregate")
    private val event = FixtureNamedEvent().toDomainEvent(
        aggregateId = aggregateId,
        commandId = "command-1",
        version = 7,
    )

    @Test
    fun `simple exchange exposes function and aggregate version`() {
        val exchange = SimpleDomainEventExchange(event)
        val function = TestEventFunction()

        exchange.setFunction(function).assert().isSameAs(exchange)

        exchange.getEventFunction().assert().isSameAs(function)
        exchange.getFunction().assert().isSameAs(function)
        exchange.getAggregateVersion().assert().isEqualTo(7)
        exchange.extractDeclared(event.javaClass).assert().isSameAs(event)
        exchange.extractDeclared(event.header.javaClass).assert().isSameAs(event.header)
        exchange.extractDeclared(event.aggregateId.javaClass).assert().isSameAs(event.aggregateId)
    }

    @Test
    fun `state exchange also exposes state aggregate and state object`() {
        val stateAggregate = MOCK_AGGREGATE_METADATA.state.toStateAggregate(
            aggregateId = aggregateId,
            state = MockStateAggregate("exchange-aggregate"),
            version = 1,
        )
        val exchange = SimpleStateDomainEventExchange(
            state = stateAggregate,
            message = event,
        )

        exchange.extractDeclared(ReadOnlyStateAggregate::class.java).assert().isSameAs(stateAggregate)
        exchange.extractDeclared(MockStateAggregate::class.java).assert().isSameAs(stateAggregate.state)
    }

    private class TestEventFunction : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
        override val name: String = "test"
        override val contextName: String = "event"
        override val supportedType: Class<*> = FixtureNamedEvent::class.java
        override val supportedTopics: Set<NamedAggregate> = emptySet()
        override val processor: Any = this
        override val functionKind: FunctionKind = FunctionKind.EVENT

        override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

        override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = Mono.empty<Void>()
    }
}

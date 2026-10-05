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

package me.ahoo.wow.event.dispatcher

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.tck.mock.MockAggregateCreated
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class DomainEventFunctionRegistrarTest {

    @Test
    fun `an annotated processor is resolved into one function per handler`() {
        val registrar = DomainEventFunctionRegistrar()
        val processor = FixtureEventProcessor()
        val created = mockDomainEvent(MockAggregateCreated("created"))

        registrar.registerProcessor(processor)

        registrar.functions.single().functionKind.assert().isEqualTo(FunctionKind.EVENT)
        val function = registrar.supportedFunctions(created).single()
        StepVerifier.create(function.invoke(SimpleDomainEventExchange(created))).verifyComplete()
        processor.events.assert().containsExactly("created")
    }

    @Test
    fun `a processor that is itself a message function is registered as is`() {
        val registrar = DomainEventFunctionRegistrar()
        val function = SelfFunction()

        registrar.registerProcessor(function)

        registrar.functions.single().assert().isSameAs(function)
        registrar.delegate.functions.single().assert().isSameAs(function)
    }
}

private class FixtureEventProcessor {
    val events: MutableList<String> = mutableListOf()

    @OnEvent
    fun onCreated(created: MockAggregateCreated) {
        events.add(created.data)
    }
}

private class SelfFunction : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
    override val processor: Any = this
    override val contextName: String = "fixture"
    override val processorName: String = "SelfFunction"
    override val name: String = "onAny"
    override val functionKind: FunctionKind = FunctionKind.EVENT
    override val supportedType: Class<*> = Any::class.java
    override val supportedTopics: Set<NamedAggregate> = emptySet()

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

    override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = Mono.empty<Void>()
}

private inline fun <reified T : Any> mockDomainEvent(body: T): DomainEvent<T> {
    val namedAggregate = requiredNamedAggregate<T>()
    return mockk {
        every { this@mockk.body } returns body
        every { contextName } returns namedAggregate.contextName
        every { aggregateName } returns namedAggregate.aggregateName
    }
}

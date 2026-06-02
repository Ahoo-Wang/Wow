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
package me.ahoo.wow.messaging.function

import io.mockk.every
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toFunctionMetadata
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

internal class SimpleMessageFunctionRegistrarTest {
    private val message = mockk<DomainEvent<MockEventBody>> {
        every { body } returns MockEventBody()
        every { contextName } returns requiredNamedAggregate<MockEventBody>().contextName
        every { aggregateName } returns requiredNamedAggregate<MockEventBody>().aggregateName
    }

    @Test
    fun `should register`() {
        val function = MockFunction::onEvent.toFunctionMetadata<Any, Any>()
            .toMessageFunction<Any, DomainEventExchange<*>, Any>(MockFunction())
        val registrar = SimpleMessageFunctionRegistrar<MessageFunction<*, *, *>>()
        registrar.register(function)

        var actual: Set<MessageFunction<*, *, *>?> = registrar.supportedFunctions(message).toSet()
        actual.size.assert().isEqualTo(1)
        actual.assert().contains(function)

        // 重复注册相同 handler
        registrar.register(function)
        actual = registrar.supportedFunctions(message).toSet()
        actual.size.assert().isEqualTo(1)
        actual.assert().contains(function)

        val anotherHandler = MockAnotherFunction::onEvent
            .toFunctionMetadata<Any, Any>()
            .toMessageFunction<Any, DomainEventExchange<*>, Any>(MockFunction())
        registrar.register(anotherHandler)
        actual = registrar.supportedFunctions(message).toSet()
        actual.size.assert().isEqualTo(2)
        actual.assert().contains(function, anotherHandler)

        val filteredRegistrar = registrar.filter { it != function }
        filteredRegistrar.functions.size.assert().isOne()
        filteredRegistrar.assert().isNotEqualTo(registrar)
    }

    @Test
    fun `should unregister`() {
        val handler = MockFunction::onEvent
            .toFunctionMetadata<Any, Any>()
            .toMessageFunction<Any, DomainEventExchange<*>, Any>(MockFunction())
        val registrar = SimpleMessageFunctionRegistrar<MessageFunction<*, *, *>>()
        registrar.register(handler)
        registrar.unregister(handler)
        val actual = registrar.supportedFunctions(message).toSet()
        actual.assert().isEmpty()
    }

    @Test
    fun `should lookup functions by aggregate topic before evaluating support`() {
        val targetTopic = requiredNamedAggregate<MockEventBody>()
        val unrelatedSupportCount = AtomicInteger()
        val matchingSupportCount = AtomicInteger()
        val registrar = SimpleMessageFunctionRegistrar<MessageFunction<*, *, *>>()
        (1..128).forEach {
            registrar.register(
                CountingMessageFunction(
                    topic = MaterializedNamedAggregate(targetTopic.contextName, "unrelated-$it"),
                    supportCount = unrelatedSupportCount,
                )
            )
        }
        val matchingFunctions = (1..2).map {
            CountingMessageFunction(
                topic = targetTopic,
                supportCount = matchingSupportCount,
            ).also { function ->
                registrar.register(function)
            }
        }

        val actual = registrar.supportedFunctions(message).toSet()

        actual.assert().containsAll(matchingFunctions)
        actual.assert().hasSize(matchingFunctions.size)
        matchingSupportCount.get().assert().isEqualTo(matchingFunctions.size)
        unrelatedSupportCount.get().assert().isEqualTo(0)
    }
}

private class CountingMessageFunction(
    private val topic: NamedAggregate,
    private val supportCount: AtomicInteger,
) : MessageFunction<Any, DomainEventExchange<*>, Any> {
    override val supportedType: Class<*> = MockEventBody::class.java
    override val supportedTopics: Set<NamedAggregate> = setOf(topic)
    override val processor: Any = this
    override val functionKind: FunctionKind = FunctionKind.EVENT
    override val contextName: String = topic.contextName
    override val name: String = "counting-${topic.aggregateName}"

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

    override fun <M> supportMessage(message: M): Boolean
        where M : Message<*, Any>, M : NamedBoundedContext, M : NamedAggregate {
        supportCount.incrementAndGet()
        return supportedType.isInstance(message.body) &&
            supportedTopics.any {
                it.isSameAggregateName(message)
            }
    }

    override fun invoke(exchange: DomainEventExchange<*>): Any = Unit
}

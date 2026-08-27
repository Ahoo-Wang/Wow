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

import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.function.MessageFunction
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class DomainEventFunctionFilterTest {

    @Test
    fun `filter invokes event function before continuing chain`() {
        val serviceProvider = mockk<ServiceProvider>()
        val exchange = SimpleDomainEventExchange(mockk<DomainEvent<Any>>(relaxed = true))
        val invocations = mutableListOf<String>()
        val eventFunction = TestDomainEventFunction(invocations)
        exchange.setFunction(eventFunction)
        var nextExchange: DomainEventExchange<*>? = null
        val next = FilterChain<DomainEventExchange<*>> {
            nextExchange = it
            Mono.fromRunnable<Void> { invocations += "next" }
        }

        StepVerifier.create(DomainEventFunctionFilter(serviceProvider).filter(exchange, next))
            .verifyComplete()

        invocations.assert().isEqualTo(listOf("eventFunction", "next"))
        exchange.getServiceProvider().assert().isSameAs(serviceProvider)
        nextExchange.assert().isSameAs(exchange)
    }
}

private class TestDomainEventFunction(
    private val invocations: MutableList<String>
) : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
    override val contextName: String = "wow-core-test"
    override val name: String = "onEvent"
    override val supportedType: Class<*> = String::class.java
    override val supportedTopics: Set<NamedAggregate> = emptySet()
    override val processor: Any = this
    override val functionKind: FunctionKind = FunctionKind.EVENT

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

    override fun invoke(exchange: DomainEventExchange<*>): Mono<*> =
        Mono.fromRunnable<Any> { invocations += "eventFunction" }
}

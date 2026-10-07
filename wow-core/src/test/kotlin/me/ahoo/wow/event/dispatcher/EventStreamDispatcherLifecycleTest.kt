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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.execution.KeyedExecutor
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

class EventStreamDispatcherLifecycleTest {

    @Test
    fun `stopGracefully closes the runtime's keyed executor after the dispatcher stops`() {
        val namedAggregate = MOCK_AGGREGATE_METADATA.materialize()
        val keyedExecutor = KeyedExecutor(workers = 1, name = "event-stream-lifecycle")
        val functionRegistrar = DomainEventFunctionRegistrar()
        functionRegistrar.register(NoOpMessageFunction(namedAggregate))
        val dispatcher = EventStreamDispatcher(
            name = "test.EventStreamDispatcher",
            messageBus = InMemoryDomainEventBus(),
            functionRegistrar = functionRegistrar,
            eventHandler = object : EventHandler {
                override fun handle(context: DomainEventExchange<*>): Mono<Void> = Mono.empty()
            },
        )
        val runtime = WowRuntime(
            components = listOf(dispatcher),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
            keyedExecutor = keyedExecutor,
        )
        runtime.start().block()

        StepVerifier.create(runtime.stopGracefully())
            .verifyComplete()

        keyedExecutor.isDisposed.assert().isTrue()
    }

    private class NoOpMessageFunction(
        private val namedAggregate: NamedAggregate,
    ) : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
        override val contextName: String = namedAggregate.contextName
        override val name: String = "noop"
        override val supportedType: Class<*> = Any::class.java
        override val supportedTopics: Set<NamedAggregate> = setOf(namedAggregate)
        override val processor: Any = this
        override val functionKind: FunctionKind = FunctionKind.EVENT

        override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

        override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = Mono.empty<Void>()
    }
}

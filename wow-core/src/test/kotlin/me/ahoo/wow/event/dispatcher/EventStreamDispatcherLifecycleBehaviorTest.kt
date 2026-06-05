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
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.scheduler.AggregateSchedulerSupplier
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicBoolean

class EventStreamDispatcherLifecycleBehaviorTest {

    @Test
    fun `stopGracefully stops scheduler supplier`() {
        val namedAggregate = MOCK_AGGREGATE_METADATA.materialize()
        val schedulerSupplier = RecordingAggregateSchedulerSupplier()
        val functionRegistrar = DomainEventFunctionRegistrar()
        functionRegistrar.register(NoOpMessageFunction(namedAggregate))
        val dispatcher = EventStreamDispatcher(
            name = "test.EventStreamDispatcher",
            parallelism = 1,
            messageBus = InMemoryDomainEventBus(),
            functionRegistrar = functionRegistrar,
            eventHandler = object : EventHandler {
                override fun handle(context: DomainEventExchange<*>): Mono<Void> = Mono.empty()
            },
            schedulerSupplier = schedulerSupplier,
        )
        dispatcher.start()

        StepVerifier.create(dispatcher.stopGracefully())
            .verifyComplete()

        schedulerSupplier.stopped.get().assert().isTrue()
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

    private class RecordingAggregateSchedulerSupplier : AggregateSchedulerSupplier {
        val stopped = AtomicBoolean()
        private val scheduler = Schedulers.newSingle("recording-event-stream-dispatcher")

        override fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler = scheduler

        override fun stopGracefully(): Mono<Void> =
            Mono.fromRunnable {
                stopped.set(true)
                scheduler.dispose()
            }
    }
}

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

package me.ahoo.wow.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.event.LocalDomainEventBus
import me.ahoo.wow.eventsourcing.state.LocalStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class MetricEventBusDecoratorTest {
    private val aggregate = MaterializedNamedAggregate("sales", "Order")
    private val subscription = MessageSubscription(aggregate, receiverGroup = "order-handler")

    @Test
    fun `local domain event bus should preserve delivery receiver lifecycle and close`() {
        val registry = SimpleMeterRegistry()
        val stream = mockk<DomainEventStream> {
            every { contextName } returns aggregate.contextName
            every { aggregateName } returns aggregate.aggregateName
        }
        val exchange = mockk<EventStreamExchange>()
        var opened = 0
        var closed = 0
        val delegate = mockk<LocalDomainEventBus> {
            every { send(stream) } returns Mono.empty()
            every { sendIfSubscribed(stream) } returns Mono.just(true)
            every { subscriberCount(aggregate) } returns 2
            every { receive(subscription) } returns Flux.just(exchange)
            every { receiver(subscription) } returns receiver(exchange, { opened++ }, { closed++ })
            every { runtimeReceiver(subscription) } returns receiver(exchange, { opened++ }, { closed++ })
            every { close() } just Runs
        }
        val eventBus = MetricLocalDomainEventBus(delegate, WowMetrics(registry), "local-domain-event-bus")

        StepVerifier.create(eventBus.send(stream)).verifyComplete()
        StepVerifier.create(eventBus.sendIfSubscribed(stream)).expectNext(true).verifyComplete()
        StepVerifier.create(eventBus.receive(subscription)).expectNext(exchange).verifyComplete()
        eventBus.subscriberCount(aggregate).assert().isEqualTo(2)
        assertReceiver(eventBus.receiver(subscription), exchange)
        assertReceiver(eventBus.runtimeReceiver(subscription), exchange)
        eventBus.close()

        opened.assert().isEqualTo(2)
        closed.assert().isEqualTo(2)
        registry.successfulOperations("domain_event_bus")
            .assert()
            .containsExactlyInAnyOrder("send", "send_if_subscribed")
        verify(exactly = 1) {
            delegate.send(stream)
            delegate.sendIfSubscribed(stream)
            delegate.subscriberCount(aggregate)
            delegate.receive(subscription)
            delegate.receiver(subscription)
            delegate.runtimeReceiver(subscription)
            delegate.close()
        }
    }

    @Test
    fun `local state event bus should preserve delivery receiver lifecycle and close`() {
        val registry = SimpleMeterRegistry()
        val stateEvent = mockk<StateEvent<Any>> {
            every { contextName } returns aggregate.contextName
            every { aggregateName } returns aggregate.aggregateName
        }
        val exchange = mockk<StateEventExchange<Any>>()
        var opened = 0
        var closed = 0
        val delegate = mockk<LocalStateEventBus> {
            every { send(stateEvent) } returns Mono.empty()
            every { sendIfSubscribed(stateEvent) } returns Mono.just(false)
            every { subscriberCount(aggregate) } returns 3
            every { receive(subscription) } returns Flux.just(exchange)
            every { receiver(subscription) } returns receiver(exchange, { opened++ }, { closed++ })
            every { runtimeReceiver(subscription) } returns receiver(exchange, { opened++ }, { closed++ })
            every { close() } just Runs
        }
        val eventBus = MetricLocalStateEventBus(delegate, WowMetrics(registry), "local-state-event-bus")

        StepVerifier.create(eventBus.send(stateEvent)).verifyComplete()
        StepVerifier.create(eventBus.sendIfSubscribed(stateEvent)).expectNext(false).verifyComplete()
        StepVerifier.create(eventBus.receive(subscription)).expectNext(exchange).verifyComplete()
        eventBus.subscriberCount(aggregate).assert().isEqualTo(3)
        assertReceiver(eventBus.receiver(subscription), exchange)
        assertReceiver(eventBus.runtimeReceiver(subscription), exchange)
        eventBus.close()

        opened.assert().isEqualTo(2)
        closed.assert().isEqualTo(2)
        registry.successfulOperations("state_event_bus")
            .assert()
            .containsExactlyInAnyOrder("send", "send_if_subscribed")
        verify(exactly = 1) {
            delegate.send(stateEvent)
            delegate.sendIfSubscribed(stateEvent)
            delegate.subscriberCount(aggregate)
            delegate.receive(subscription)
            delegate.receiver(subscription)
            delegate.runtimeReceiver(subscription)
            delegate.close()
        }
    }

    private fun <T : Any> receiver(
        exchange: T,
        open: () -> Unit,
        close: () -> Unit,
    ): MessageReceiver<T> = MessageReceiver(
        messages = Flux.just(exchange),
        processingAdmission = open,
        processingQuiescence = close,
    )

    private fun <T : Any> assertReceiver(
        receiver: MessageReceiver<T>,
        exchange: T,
    ) {
        receiver.openProcessing()
        StepVerifier.create(receiver.messages).expectNext(exchange).verifyComplete()
        receiver.closeProcessing()
    }

    private fun SimpleMeterRegistry.successfulOperations(component: String): List<String> = meters
        .map { it.id }
        .filter { it.name == WowMetricNames.OPERATION }
        .filter { it.getTag(MetricDescriptor.COMPONENT_TAG) == component }
        .filter { it.getTag(MetricDescriptor.OUTCOME_TAG) == MetricOutcome.SUCCESS.metricValue }
        .mapNotNull { it.getTag(MetricDescriptor.OPERATION_TAG) }
}

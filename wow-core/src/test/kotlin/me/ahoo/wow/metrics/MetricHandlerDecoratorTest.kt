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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.wait.TestCommandMessage
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.dispatcher.DomainEventHandler
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotHandler
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.projection.ProjectionHandler
import me.ahoo.wow.saga.stateless.StatelessSagaHandler
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class MetricHandlerDecoratorTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = WowMetrics(registry)

    @BeforeEach
    fun clearRegistry() {
        registry.clear()
    }

    @Test
    fun `command handler should delegate and record message identity`() {
        val exchange = SimpleServerCommandExchange(TestCommandMessage(id = "command-id"))
        val delegate = mockk<CommandHandler> {
            every { handle(exchange) } returns Mono.empty()
        }

        StepVerifier.create(MetricCommandHandler(delegate, metrics, "command-handler").handle(exchange))
            .verifyComplete()

        verify(exactly = 1) { delegate.handle(exchange) }
        registry.operationTags("command_handler")[MetricDescriptor.MESSAGE_TAG]
            .assert()
            .isEqualTo(exchange.message.name)
    }

    @Test
    fun `event handlers should record processor identity and preserve delegation`() {
        val exchange = domainEventExchange("OrderProjection")
        val domainHandler = mockk<DomainEventHandler> {
            every { handle(exchange) } returns Mono.empty()
        }
        val projectionHandler = mockk<ProjectionHandler> {
            every { handle(exchange) } returns Mono.empty()
        }
        val sagaHandler = mockk<StatelessSagaHandler> {
            every { handle(exchange) } returns Mono.empty()
        }

        StepVerifier.create(MetricDomainEventHandler(domainHandler, metrics, "domain-handler").handle(exchange))
            .verifyComplete()
        StepVerifier.create(MetricProjectionHandler(projectionHandler, metrics, "projection-handler").handle(exchange))
            .verifyComplete()
        StepVerifier.create(MetricStatelessSagaHandler(sagaHandler, metrics, "saga-handler").handle(exchange))
            .verifyComplete()

        verify(exactly = 1) {
            domainHandler.handle(exchange)
            projectionHandler.handle(exchange)
            sagaHandler.handle(exchange)
        }
        listOf("domain_event_handler", "projection_handler", "stateless_saga_handler").forEach { component ->
            registry.operationTags(component)[MetricDescriptor.PROCESSOR_TAG]
                .assert()
                .isEqualTo("OrderProjection")
        }
    }

    @Test
    fun `event handlers should use bounded fallback without selected function`() {
        val exchange = domainEventExchange(processor = null)
        val domainHandler = mockk<DomainEventHandler> {
            every { handle(exchange) } returns Mono.empty()
        }
        val projectionHandler = mockk<ProjectionHandler> {
            every { handle(exchange) } returns Mono.empty()
        }
        val sagaHandler = mockk<StatelessSagaHandler> {
            every { handle(exchange) } returns Mono.empty()
        }

        StepVerifier.create(MetricDomainEventHandler(domainHandler, metrics, "domain-handler").handle(exchange))
            .verifyComplete()
        StepVerifier.create(MetricProjectionHandler(projectionHandler, metrics, "projection-handler").handle(exchange))
            .verifyComplete()
        StepVerifier.create(MetricStatelessSagaHandler(sagaHandler, metrics, "saga-handler").handle(exchange))
            .verifyComplete()

        listOf("domain_event_handler", "projection_handler", "stateless_saga_handler").forEach { component ->
            registry.operationTags(component)[MetricDescriptor.PROCESSOR_TAG]
                .assert()
                .isEqualTo(MetricDescriptor.NONE)
        }
    }

    @Test
    fun `snapshot components should delegate and record aggregate identity`() {
        val exchange = stateEventExchange()
        val snapshotHandler = mockk<SnapshotHandler> {
            every { handle(exchange) } returns Mono.empty()
        }
        val snapshotStrategy = mockk<SnapshotStrategy> {
            every { onEvent(exchange) } returns Mono.empty()
        }

        StepVerifier.create(MetricSnapshotHandler(snapshotHandler, metrics, "snapshot-handler").handle(exchange))
            .verifyComplete()
        StepVerifier.create(MetricSnapshotStrategy(snapshotStrategy, metrics, "snapshot-strategy").onEvent(exchange))
            .verifyComplete()

        verify(exactly = 1) {
            snapshotHandler.handle(exchange)
            snapshotStrategy.onEvent(exchange)
        }
        registry.operationTags("snapshot_handler")[MetricDescriptor.AGGREGATE_TAG]
            .assert()
            .isEqualTo("Order")
        registry.operationTags("snapshot_strategy")[MetricDescriptor.AGGREGATE_TAG]
            .assert()
            .isEqualTo("Order")
    }

    private fun domainEventExchange(processor: String?): DomainEventExchange<Any> {
        val message = mockk<DomainEvent<Any>> {
            every { contextName } returns "sales"
            every { aggregateName } returns "Order"
            every { name } returns "OrderCreated"
        }
        val function = processor?.let { processorName ->
            val messageFunction = mockk<MessageFunction<Any, DomainEventExchange<*>, Mono<*>>>()
            every { messageFunction.processorName } returns processorName
            messageFunction
        }
        val exchange = mockk<DomainEventExchange<Any>>()
        every { exchange.message } returns message
        every { exchange.getEventFunction() } returns function
        return exchange
    }

    private fun stateEventExchange(): StateEventExchange<Any> {
        val message = mockk<StateEvent<Any>> {
            every { contextName } returns "sales"
            every { aggregateName } returns "Order"
        }
        val exchange = mockk<StateEventExchange<Any>>()
        every { exchange.message } returns message
        return exchange
    }

    private fun SimpleMeterRegistry.operationTags(component: String): Map<String, String> =
        get(WowMetricNames.OPERATION)
            .tag(MetricDescriptor.COMPONENT_TAG, component)
            .timer()
            .id
            .tags
            .associate { it.key to it.value }
}

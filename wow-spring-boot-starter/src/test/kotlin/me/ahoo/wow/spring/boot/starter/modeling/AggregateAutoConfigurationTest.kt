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

package me.ahoo.wow.spring.boot.starter.modeling

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.messaging.handler.ExchangeFilter
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.command.AggregateProcessorFactory
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.CommandDispatcher
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.runtime.RuntimeResources
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.spring.WowRuntimeLifecycle
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.opentelemetry.WowOpenTelemetryAutoConfiguration
import me.ahoo.wow.test.SagaVerifier
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono

internal class AggregateAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should load context with aggregate processing beans`() {
        contextRunner
            .enableWow()
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(SnapshotStore::class.java, { NoOpSnapshotStore })
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withBean(DomainEventBus::class.java, { InMemoryDomainEventBus() })
            .withBean(CommandGateway::class.java, { SagaVerifier.defaultCommandGateway() })
            .withUserConfiguration(
                WowOpenTelemetryAutoConfiguration::class.java,
                AggregateAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(StateAggregateFactory::class.java)
                    .hasSingleBean(StateAggregateRepository::class.java)
                    .hasSingleBean(CommandAggregateFactory::class.java)
                    .hasSingleBean(AggregateProcessorFactory::class.java)
                    .hasSingleBean(CommandHandler::class.java)
                    .hasSingleBean(WowRuntimeLifecycle::class.java)
                context.getBean(
                    WowRuntime::class.java
                ).components.filterNot { it is RuntimeResources }.single().assert()
                    .isInstanceOf(CommandDispatcher::class.java)
            }
    }

    private fun aggregateContext(): ApplicationContextRunner =
        contextRunner
            .enableWow()
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(SnapshotStore::class.java, { NoOpSnapshotStore })
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withBean(DomainEventBus::class.java, { InMemoryDomainEventBus() })
            .withBean(CommandGateway::class.java, { SagaVerifier.defaultCommandGateway() })
            .withUserConfiguration(
                WowOpenTelemetryAutoConfiguration::class.java,
                AggregateAutoConfiguration::class.java,
            )

    @Test
    fun `a command exchange filter bean fails startup and names the replacement`() {
        aggregateContext()
            .withBean(
                "legacyCommandFilter",
                CommandExchangeFilter::class.java,
                { CommandExchangeFilter() },
            )
            .run { context: AssertableApplicationContext ->
                context.assert().hasFailed()
                val message = context.startupFailure!!.stackTraceToString()
                message.assert().contains("legacyCommandFilter").contains("CommandInstrumentation")
            }
    }

    @Test
    fun `a filter typed for the command dispatcher fails startup`() {
        aggregateContext()
            .withBean("dispatcherTypedFilter", DispatcherTypedFilter::class.java, { DispatcherTypedFilter() })
            .run { context: AssertableApplicationContext ->
                context.assert().hasFailed()
                context.startupFailure!!.stackTraceToString().assert().contains("dispatcherTypedFilter")
            }
    }

    @Test
    fun `an event-side exchange filter does not fail startup`() {
        aggregateContext()
            .withBean("eventFilter", EventExchangeFilter::class.java, { EventExchangeFilter() })
            .run { context: AssertableApplicationContext ->
                context.assert().hasNotFailed()
            }
    }
}

private class CommandExchangeFilter : ExchangeFilter<ServerCommandExchange<*>> {
    override fun filter(exchange: ServerCommandExchange<*>, next: FilterChain<ServerCommandExchange<*>>): Mono<Void> =
        next.filter(exchange)
}

@FilterType(CommandDispatcher::class)
private class DispatcherTypedFilter : ExchangeFilter<MessageExchange<*, *>> {
    override fun filter(exchange: MessageExchange<*, *>, next: FilterChain<MessageExchange<*, *>>): Mono<Void> =
        next.filter(exchange)
}

private class EventExchangeFilter : ExchangeFilter<DomainEventExchange<*>> {
    override fun filter(exchange: DomainEventExchange<*>, next: FilterChain<DomainEventExchange<*>>): Mono<Void> =
        next.filter(exchange)
}

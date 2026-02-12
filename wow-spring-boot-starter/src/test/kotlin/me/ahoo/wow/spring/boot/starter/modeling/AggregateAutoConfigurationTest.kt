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

import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.modeling.command.AggregateProcessorFactory
import me.ahoo.wow.modeling.command.CommandAggregateFactory
import me.ahoo.wow.modeling.command.dispatcher.AggregateProcessorFilter
import me.ahoo.wow.modeling.command.dispatcher.CommandHandler
import me.ahoo.wow.modeling.command.dispatcher.SendDomainEventStreamFilter
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.opentelemetry.WowOpenTelemetryAutoConfiguration
import me.ahoo.wow.spring.command.CommandDispatcherLauncher
import me.ahoo.wow.test.SagaVerifier
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class AggregateAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(SnapshotRepository::class.java, { NoOpSnapshotRepository })
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withBean(DomainEventBus::class.java, { InMemoryDomainEventBus() })
            .withBean(CommandGateway::class.java, { SagaVerifier.defaultCommandGateway() })
            .withUserConfiguration(
                WowOpenTelemetryAutoConfiguration::class.java,
                AggregateAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(StateAggregateFactory::class.java)
                    .hasSingleBean(StateAggregateRepository::class.java)
                    .hasSingleBean(CommandAggregateFactory::class.java)
                    .hasSingleBean(AggregateProcessorFactory::class.java)
                    .hasSingleBean(AggregateProcessorFilter::class.java)
                    .hasSingleBean(SendDomainEventStreamFilter::class.java)
                    .hasSingleBean(FilterChain::class.java)
                    .hasSingleBean(CommandHandler::class.java)
                    .hasSingleBean(CommandDispatcherLauncher::class.java)
            }
    }
}

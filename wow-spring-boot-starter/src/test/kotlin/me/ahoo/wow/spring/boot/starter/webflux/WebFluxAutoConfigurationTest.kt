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

package me.ahoo.wow.spring.boot.starter.webflux

import io.mockk.mockk
import io.mockk.spyk
import me.ahoo.cosid.machine.HostAddressSupplier
import me.ahoo.cosid.machine.LocalHostAddressSupplier
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.eventsourcing.snapshot.NoOpSnapshotRepository
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.query.filter.SnapshotQueryHandler
import me.ahoo.wow.spring.boot.starter.command.CommandAutoConfiguration
import me.ahoo.wow.spring.boot.starter.command.CommandGatewayAutoConfiguration
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.EventSourcingAutoConfiguration
import me.ahoo.wow.spring.boot.starter.kafka.KafkaProperties
import me.ahoo.wow.spring.boot.starter.modeling.AggregateAutoConfiguration
import me.ahoo.wow.spring.boot.starter.openapi.OpenAPIAutoConfiguration
import me.ahoo.wow.spring.boot.starter.webflux.WebFluxProperties.Companion.GLOBAL_ERROR_ENABLED
import me.ahoo.wow.test.SagaVerifier
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.exception.GlobalExceptionHandler
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class WebFluxAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withBean(CommandWaitNotifier::class.java, { mockk() })
            .withBean(CommandGateway::class.java, { SagaVerifier.defaultCommandGateway() })
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(SnapshotRepository::class.java, { NoOpSnapshotRepository })
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withBean(DomainEventBus::class.java, { InMemoryDomainEventBus() })
            .withBean(DomainEventCompensator::class.java, { mockk() })
            .withBean(StateEventCompensator::class.java, { mockk() })
            .withBean(SnapshotQueryHandler::class.java, { spyk<SnapshotQueryHandler>() })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
                EventSourcingAutoConfiguration::class.java,
                AggregateAutoConfiguration::class.java,
                OpenAPIAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(GlobalExceptionHandler::class.java)
                    .hasBean("commandRouterFunction")
                    .hasSingleBean(ExceptionHandler::class.java)
            }
    }

    @Test
    fun contextLoadsWithKafkaProperties() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${GLOBAL_ERROR_ENABLED}=false"
            )
            .withBean(CommandWaitNotifier::class.java, { mockk() })
            .withBean(CommandGateway::class.java, { SagaVerifier.defaultCommandGateway() })
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withBean(SnapshotRepository::class.java, { NoOpSnapshotRepository })
            .withBean(EventStore::class.java, { InMemoryEventStore() })
            .withBean(DomainEventBus::class.java, { InMemoryDomainEventBus() })
            .withBean(DomainEventCompensator::class.java, { mockk() })
            .withBean(StateEventCompensator::class.java, { mockk() })
            .withBean(SnapshotQueryHandler::class.java, { spyk<SnapshotQueryHandler>() })
            .withBean(KafkaProperties::class.java, {
                KafkaProperties(bootstrapServers = listOf("localhost:9092"))
            })
            .withBean(HostAddressSupplier::class.java, { LocalHostAddressSupplier.INSTANCE })
            .withUserConfiguration(
                CommandAutoConfiguration::class.java,
                CommandGatewayAutoConfiguration::class.java,
                EventSourcingAutoConfiguration::class.java,
                AggregateAutoConfiguration::class.java,
                OpenAPIAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .doesNotHaveBean(GlobalExceptionHandler::class.java)
                    .hasBean("commandRouterFunction")
                    .hasSingleBean(ExceptionHandler::class.java)
            }
    }
}

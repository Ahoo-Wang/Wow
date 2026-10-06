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

package me.ahoo.wow.spring.boot.starter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.state.DistributedStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.messaging.LocalFirstDistributedCopies
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.spring.boot.starter.command.CommandAutoConfiguration
import me.ahoo.wow.spring.boot.starter.event.EventAutoConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.state.StateAutoConfiguration
import me.ahoo.wow.spring.boot.starter.metrics.MetricsAutoConfiguration
import me.ahoo.wow.spring.boot.starter.opentelemetry.WowOpenTelemetryAutoConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * With metrics and tracing on, the post-processors decorate the local-first buses; the context still starts, and the
 * distributed copies are their own beans, the same instances the buses use.
 */
class LocalFirstDecoratedBusesTest {

    @Test
    fun `local-first buses decorated by metrics and tracing start with their distributed copies`() {
        ApplicationContextRunner()
            .enableWow()
            .withBean(SimpleMeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean(DistributedCommandBus::class.java, { mockk(relaxed = true) })
            .withBean(DistributedDomainEventBus::class.java, { mockk(relaxed = true) })
            .withBean(DistributedStateEventBus::class.java, { mockk(relaxed = true) })
            .withBean(EventStore::class.java, { mockk<EventStore>() })
            .withBean(StateAggregateFactory::class.java, { ConstructorStateAggregateFactory })
            .withConfiguration(
                AutoConfigurations.of(
                    MetricsAutoConfiguration::class.java,
                    WowOpenTelemetryAutoConfiguration::class.java,
                    CommandAutoConfiguration::class.java,
                    EventAutoConfiguration::class.java,
                    StateAutoConfiguration::class.java,
                ),
            )
            .run { context: AssertableApplicationContext ->
                context.assert().hasNotFailed()
                context.getBeansOfType(LocalFirstDistributedCopies::class.java).keys.assert().containsExactlyInAnyOrder(
                    CommandAutoConfiguration.LOCAL_FIRST_COPIES,
                    EventAutoConfiguration.LOCAL_FIRST_COPIES,
                    StateAutoConfiguration.LOCAL_FIRST_COPIES,
                )
                // The primary event buses are decorated (metrics, tracing), not the bare local-first buses; the command
                // bus is left undecorated by the metrics (its local and distributed parts are metered).
                listOf(
                    context.getBean(DomainEventBus::class.java),
                    context.getBean(StateEventBus::class.java),
                ).forEach { bus -> bus.assert().isInstanceOf(Decorator::class.java) }
                context.getBean(CommandBus::class.java).assert().isNotNull()
            }
    }
}

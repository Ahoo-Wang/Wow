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

package me.ahoo.wow.spring.boot.starter.saga

import io.mockk.mockk
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.factory.CommandMessageFactory
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.eventsourcing.state.InMemoryStateEventBus
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.saga.stateless.StatelessSagaDispatcher
import me.ahoo.wow.saga.stateless.StatelessSagaFunctionFilter
import me.ahoo.wow.saga.stateless.StatelessSagaFunctionRegistrar
import me.ahoo.wow.saga.stateless.StatelessSagaHandler
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.saga.StatelessSagaDispatcherLauncher
import me.ahoo.wow.spring.saga.StatelessSagaProcessorAutoRegistrar
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class StatelessSagaAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withBean(CommandGateway::class.java, { mockk() })
            .withBean(CommandMessageFactory::class.java,{ mockk() })
            .withBean(DomainEventBus::class.java, { InMemoryDomainEventBus() })
            .withBean(StateEventBus::class.java, { InMemoryStateEventBus() })
            .withUserConfiguration(
                StatelessSagaAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(StatelessSagaFunctionRegistrar::class.java)
                    .hasSingleBean(StatelessSagaProcessorAutoRegistrar::class.java)
                    .hasSingleBean(StatelessSagaFunctionFilter::class.java)
                    .hasBean("statelessSagaFilterChain")
                    .hasSingleBean(StatelessSagaHandler::class.java)
                    .hasSingleBean(StatelessSagaDispatcher::class.java)
                    .hasSingleBean(StatelessSagaDispatcherLauncher::class.java)
            }
    }
}

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

package me.ahoo.wow.spring.boot.starter.command

import io.mockk.mockk
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.ProcessedNotifierFilter
import me.ahoo.wow.command.wait.ProjectedNotifierFilter
import me.ahoo.wow.command.wait.WaitStrategyRegistrar
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.spring.boot.starter.MessageBusType
import me.ahoo.wow.spring.boot.starter.enableWow
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cloud.commons.util.UtilAutoConfiguration
import javax.validation.Validator

internal class CommandAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withPropertyValues("${CommandProperties.Bus.TYPE}=${MessageBusType.IN_MEMORY_NAME}")
            .withBean(CommandWaitNotifier::class.java, { mockk<CommandWaitNotifier>() })
            .withUserConfiguration(
                UtilAutoConfiguration::class.java,
                WebClientAutoConfiguration::class.java,
                CommandAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(Validator::class.java)
                    .hasSingleBean(IdempotencyChecker::class.java)
                    .hasSingleBean(WaitStrategyRegistrar::class.java)
                    .hasSingleBean(CommandWaitEndpoint::class.java)
                    .hasSingleBean(CommandGateway::class.java)
                    .hasSingleBean(ProcessedNotifierFilter::class.java)
                    .hasSingleBean(ProjectedNotifierFilter::class.java)
            }
    }
}

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

package me.ahoo.wow.spring.boot.starter.projection

import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.InMemoryDomainEventBus
import me.ahoo.wow.projection.ProjectionDispatcher
import me.ahoo.wow.projection.ProjectionFunctionFilter
import me.ahoo.wow.projection.ProjectionFunctionRegistrar
import me.ahoo.wow.projection.ProjectionHandler
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.projection.ProjectionDispatcherLauncher
import me.ahoo.wow.spring.projection.ProjectionProcessorAutoRegistrar
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class ProjectionDispatcherAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withBean(DomainEventBus::class.java, { InMemoryDomainEventBus() })
            .withUserConfiguration(
                ProjectionDispatcherAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(ProjectionFunctionRegistrar::class.java)
                    .hasSingleBean(ProjectionProcessorAutoRegistrar::class.java)
                    .hasSingleBean(ProjectionFunctionFilter::class.java)
                    .hasBean("projectionFilterChain")
                    .hasSingleBean(ProjectionHandler::class.java)
                    .hasSingleBean(ProjectionDispatcher::class.java)
                    .hasSingleBean(ProjectionDispatcherLauncher::class.java)
            }
    }
}

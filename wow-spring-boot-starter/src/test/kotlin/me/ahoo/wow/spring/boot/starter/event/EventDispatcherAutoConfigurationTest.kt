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

package me.ahoo.wow.spring.boot.starter.event

import io.mockk.mockk
import me.ahoo.wow.event.DomainEventDispatcher
import me.ahoo.wow.event.DomainEventFunctionFilter
import me.ahoo.wow.event.DomainEventFunctionRegistrar
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.messaging.handler.RetryableFilter
import me.ahoo.wow.spring.boot.starter.BusProperties
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.opentelemetry.WowOpenTelemetryAutoConfiguration
import me.ahoo.wow.spring.event.DomainEventDispatcherLauncher
import me.ahoo.wow.spring.event.EventProcessorAutoRegistrar
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cloud.commons.util.UtilAutoConfiguration

internal class EventDispatcherAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withPropertyValues("${EventProperties.BUS_TYPE}=${BusProperties.Type.IN_MEMORY_NAME}")
            .withBean(EventStore::class.java, { mockk() })
            .withUserConfiguration(
                UtilAutoConfiguration::class.java,
                WebClientAutoConfiguration::class.java,
                WowOpenTelemetryAutoConfiguration::class.java,
                EventAutoConfiguration::class.java,
                EventDispatcherAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(DomainEventFunctionRegistrar::class.java)
                    .hasSingleBean(EventProcessorAutoRegistrar::class.java)
                    .hasSingleBean(RetryableFilter::class.java)
                    .hasSingleBean(DomainEventFunctionFilter::class.java)
                    .hasSingleBean(DomainEventDispatcher::class.java)
                    .hasSingleBean(DomainEventDispatcherLauncher::class.java)
            }
    }
}

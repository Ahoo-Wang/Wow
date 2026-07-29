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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.exception.DefaultErrorInfoConverter
import me.ahoo.wow.exception.ErrorInfoConverter
import me.ahoo.wow.exception.ErrorInfoConverterFactory
import me.ahoo.wow.exception.ErrorInfoConverterRegistrar
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.runtime.WowRuntime
import me.ahoo.wow.spring.WOW_RUNTIME_PHASE
import me.ahoo.wow.spring.WowRuntimeLifecycle
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration.Companion.SPRING_APPLICATION_NAME
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.support.DefaultLifecycleProcessor
import org.springframework.test.util.ReflectionTestUtils

internal class WowAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LifecycleAutoConfiguration::class.java))

    @Test
    fun `should load context with default configuration`() {
        contextRunner
            .enableWow()
            .withBean(ErrorInfoConverterFactory::class.java, {
                return@withBean object : ErrorInfoConverterFactory<Throwable> {
                    override val supportedType: Class<Throwable>
                        get() = Throwable::class.java

                    override fun create(): ErrorInfoConverter<Throwable> {
                        return DefaultErrorInfoConverter
                    }
                }
            })
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(WowProperties::class.java)
                    .hasSingleBean(ServiceProvider::class.java)
                    .hasSingleBean(NamedBoundedContext::class.java)
                    .hasSingleBean(ErrorInfoConverterRegistrar::class.java)
                    .hasSingleBean(WowRuntime::class.java)
                    .hasSingleBean(WowRuntimeLifecycle::class.java)
                    .hasSingleBean(DefaultLifecycleProcessor::class.java)
            }
    }

    @Test
    fun `should preserve Spring Boot lifecycle timeout and add runtime phase timeout`() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "spring.lifecycle.timeout-per-shutdown-phase=47s",
                "wow.shutdown-timeout=12s",
            )
            .run { context: AssertableApplicationContext ->
                val lifecycleProcessor = context.getBean(DefaultLifecycleProcessor::class.java)
                val defaultPhaseTimeout =
                    ReflectionTestUtils.getField(lifecycleProcessor, "timeoutPerShutdownPhase")
                val phaseTimeouts =
                    ReflectionTestUtils.getField(
                        lifecycleProcessor,
                        "timeoutsForShutdownPhases",
                    ) as Map<*, *>

                defaultPhaseTimeout.assert().isEqualTo(47_000L)
                phaseTimeouts[WOW_RUNTIME_PHASE]
                    .assert()
                    .isEqualTo(13_000L)
            }
    }

    @Test
    fun `should load context when context name is null`() {
        contextRunner
            .withPropertyValues("$SPRING_APPLICATION_NAME=wow-spring-boot-starter-test")
            .withUserConfiguration(WowAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                context.assert()
                    .hasSingleBean(WowProperties::class.java)
                    .hasSingleBean(ServiceProvider::class.java)
                    .hasSingleBean(NamedBoundedContext::class.java)
                    .hasSingleBean(WowRuntime::class.java)
                    .hasSingleBean(WowRuntimeLifecycle::class.java)
            }
    }
}

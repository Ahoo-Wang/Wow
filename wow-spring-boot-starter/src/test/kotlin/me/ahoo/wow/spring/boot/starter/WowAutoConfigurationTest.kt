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

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.spring.boot.starter.WowAutoConfiguration.Companion.SPRING_APPLICATION_NAME
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class WowAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(WowProperties::class.java)
                    .hasSingleBean(ServiceProvider::class.java)
                    .hasSingleBean(NamedBoundedContext::class.java)
            }
    }

    @Test
    fun contextLoadsIfContextNull() {
        contextRunner
            .withPropertyValues("$SPRING_APPLICATION_NAME=wow-spring-boot-starter-test")
            .withUserConfiguration(WowAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(WowProperties::class.java)
                    .hasSingleBean(ServiceProvider::class.java)
                    .hasSingleBean(NamedBoundedContext::class.java)
            }
    }
}

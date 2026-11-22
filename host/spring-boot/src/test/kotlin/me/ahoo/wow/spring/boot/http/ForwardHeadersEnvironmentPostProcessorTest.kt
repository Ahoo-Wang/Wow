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

package me.ahoo.wow.spring.boot.http

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * The host's forwarded-header strategy steps aside for the application's, under either key and in any spelling Spring
 * binds: Spring Boot 4.2 does not start with both keys set.
 */
class ForwardHeadersEnvironmentPostProcessorTest {
    private fun processed(variables: Map<String, Any>): StandardEnvironment = StandardEnvironment().apply {
        propertySources.replace(
            SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables),
        )
        ForwardHeadersEnvironmentPostProcessor().postProcessEnvironment(this, SpringApplication())
    }

    @Test
    fun `an application that sets neither key gets web-server under the new one`() {
        val environment = processed(emptyMap())
        environment.getProperty(ForwardHeadersEnvironmentPostProcessor.STRATEGY).assert().isEqualTo("web-server")
        environment.getProperty(ForwardHeadersEnvironmentPostProcessor.DEPRECATED_STRATEGY).assert().isNull()
    }

    @Test
    fun `the deprecated key in an environment variable's spelling keeps the host from setting the new one`() {
        // Spring's canonical spelling, which Environment.containsProperty does not answer to: only a binder does.
        val environment = processed(mapOf("SERVER_FORWARDHEADERSSTRATEGY" to "none"))
        environment.propertySources.contains(ForwardHeadersEnvironmentPostProcessor.SOURCE).assert().isFalse()
        ForwardHeadersEnvironmentPostProcessor.inEffect(environment).assert().startsWith(
            "server.forward-headers-strategy=none (deprecated",
        )
    }

    @Test
    fun `the new key in an environment variable's spelling is the application's`() {
        val environment = processed(mapOf("SERVER_REQUEST_HEADER_FORWARDED_STRATEGY" to "none"))
        environment.propertySources.contains(ForwardHeadersEnvironmentPostProcessor.SOURCE).assert().isFalse()
        ForwardHeadersEnvironmentPostProcessor.inEffect(environment)
            .assert().isEqualTo("server.request.header.forwarded.strategy=none")
    }
}

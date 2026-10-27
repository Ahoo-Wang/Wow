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

package me.ahoo.wow.spring.boot

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Reads the client behind the gateway: a gateway always sits in front of the public
 * port and overwrites `X-Forwarded-For`, so the host makes Spring Boot's `server.forward-headers-strategy` `framework`
 * by default, and the address a command records (`remote_ip`) is the client's, not the gateway's. Whatever the
 * application sets wins, `none` included; the host logs the strategy in effect when it starts.
 */
internal class ForwardHeadersEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (environment.containsProperty(FORWARD_HEADERS_STRATEGY)) {
            return
        }
        environment.propertySources.addLast(MapPropertySource(SOURCE, mapOf(FORWARD_HEADERS_STRATEGY to DEFAULT)))
    }

    companion object {
        const val FORWARD_HEADERS_STRATEGY: String = "server.forward-headers-strategy"
        const val DEFAULT: String = "framework"
        const val SOURCE: String = "wowForwardHeaders"
    }
}

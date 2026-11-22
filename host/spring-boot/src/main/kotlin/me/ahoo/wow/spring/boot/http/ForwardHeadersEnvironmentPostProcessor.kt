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

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource

/**
 * Reads the client behind the gateway: a gateway always sits in front of the public
 * port and overwrites `X-Forwarded-For`, so the host makes Spring Boot's `server.request.header.forwarded.strategy`
 * `web-server` by default, the public port's Reactor Netty server taking the remote address from it, and the address a
 * command records (`remote_ip`) is the client's, not the gateway's. Not `framework`: CoSec does not start under
 * Spring's forwarded-header transformer, which the client address cannot be read safely through (CoSec's `PIP-16`);
 * under `web-server` CoSec reads `X-Forwarded-For` itself, one proxy deep by default.
 *
 * Whatever the application sets wins, `none` included, under either key: Spring Boot 4.2's
 * `server.request.header.forwarded.strategy`, or the deprecated `server.forward-headers-strategy` it replaces
 * (`native` is `web-server`), in any spelling Spring binds, an environment variable's too. The host then sets neither,
 * since Spring Boot does not start with both. The host logs the strategy in effect when it starts ([inEffect]).
 */
internal class ForwardHeadersEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (set(environment).isNotEmpty()) {
            return
        }
        environment.propertySources.addLast(MapPropertySource(SOURCE, mapOf(STRATEGY to DEFAULT)))
    }

    companion object {
        /** Spring Boot 4.2's key for how the server reads the forwarded headers. */
        const val STRATEGY: String = "server.request.header.forwarded.strategy"

        /** The key [STRATEGY] replaces, deprecated in Spring Boot 4.2, which still reads it. */
        const val DEPRECATED_STRATEGY: String = "server.forward-headers-strategy"
        const val DEFAULT: String = "web-server"
        const val SOURCE: String = "wowForwardHeaders"

        /** The keys of the strategy [environment] sets, as `key=value`, the deprecated one last. */
        private fun set(environment: Environment): List<String> {
            val binder = Binder.get(environment)
            return listOf(STRATEGY, DEPRECATED_STRATEGY).mapNotNull { key ->
                binder.bind(key, String::class.java).orElse(null)?.let { "$key=$it" }
            }
        }

        /**
         * The strategy in effect, as `key=value`: the application's, under the key it set, or the host's default.
         * A deprecated key says so, and names the key that replaces it.
         */
        fun inEffect(environment: Environment): String = set(environment).joinToString(", ").let { strategy ->
            if (strategy.startsWith(DEPRECATED_STRATEGY)) {
                "$strategy (deprecated in Spring Boot 4.2: set $STRATEGY, where native is web-server)"
            } else {
                strategy
            }
        }
    }
}

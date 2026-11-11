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

import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spring.boot.transport.PortProviders.Companion.configured
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource
import java.time.Duration
import kotlin.time.toJavaDuration

/**
 * Gives the node's stop the time it may take: Spring Boot stops each lifecycle phase within
 * `spring.lifecycle.timeout-per-shutdown-phase` (30 seconds by default), and [me.ahoo.wow.runtime.node.ServiceNode.stop]
 * may take its [ServiceNodeOptions.stopBound], from the node's own options (`wow.wait.max-timeout`,
 * `wow.cluster.drain-timeout`, `wow.subscription.stop-timeout`): 90 seconds by default. Unless the application sets the
 * timeout, it becomes that bound ([required]); a smaller one the application sets is kept, and the host warns of it when
 * it starts. A setting of those groups that the node's options refuse leaves the timeout unset here: the node's assembly
 * refuses it, as it does every refused setting of the node.
 */
internal class ShutdownTimeoutEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (environment.containsProperty(TIMEOUT_PER_SHUTDOWN_PHASE)) {
            return
        }
        // A `wow.*` setting that does not bind is the properties' bean's to refuse.
        val properties = WowProperties.ofOrNull(environment) ?: return
        val bound = try {
            stopBound(properties)
        } catch (refused: WowConfigurationException) {
            // Left unset: the node's assembly refuses the setting where it always did, once Spring Boot's logging is
            // configured, so the failure analysis reaches the application's own log, not only the console.
            return
        }
        environment.propertySources.addLast(
            MapPropertySource(SOURCE, mapOf(TIMEOUT_PER_SHUTDOWN_PHASE to "${bound.toMillis()}ms")),
        )
    }

    companion object {
        const val TIMEOUT_PER_SHUTDOWN_PHASE: String = "spring.lifecycle.timeout-per-shutdown-phase"
        const val SOURCE: String = "wowShutdownTimeout"

        /** The node's [ServiceNodeOptions.stopBound], from the options [environment] sets or by default. */
        fun required(environment: Environment): Duration = stopBound(WowProperties.of(environment))

        private fun stopBound(properties: WowProperties): Duration = ServiceNodeOptions(
            cluster = configured("wow.cluster") { properties.cluster.options(properties.wait.defaultTimeout) },
            wait = configured("wow.wait") { properties.wait.options() },
            subscription = configured("wow.subscription") { properties.subscription.options() },
        ).stopBound.toJavaDuration()

        /** The timeout per shutdown phase [environment] sets; null when it sets none. */
        fun configured(environment: Environment): Duration? =
            Binder.get(environment).bind(TIMEOUT_PER_SHUTDOWN_PHASE, Duration::class.java).orElse(null)
    }
}

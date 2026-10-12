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
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource
import java.time.Duration

/**
 * Gives the node's stop the time it may take (decision D108): Spring Boot stops each lifecycle phase within
 * `spring.lifecycle.timeout-per-shutdown-phase` (30 seconds by default), and [me.ahoo.wow.runtime.node.ServiceNode.stop]
 * may take the longest wait (`wow.wait.max-timeout`) after the drain of the partitions it hands off
 * (`wow.cluster.drain-timeout`). Unless the application sets the timeout, it becomes their sum ([required]); a smaller
 * one the application sets is kept, and the host warns of it when it starts.
 */
class ShutdownTimeoutEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (environment.containsProperty(TIMEOUT_PER_SHUTDOWN_PHASE)) {
            return
        }
        environment.propertySources.addLast(
            MapPropertySource(SOURCE, mapOf(TIMEOUT_PER_SHUTDOWN_PHASE to "${required(environment).toMillis()}ms")),
        )
    }

    companion object {
        const val TIMEOUT_PER_SHUTDOWN_PHASE: String = "spring.lifecycle.timeout-per-shutdown-phase"
        const val SOURCE: String = "wowShutdownTimeout"

        /** `wow.wait.max-timeout + wow.cluster.drain-timeout`, as [environment] sets them or by default. */
        fun required(environment: Environment): Duration {
            val binder = Binder.get(environment)
            val defaults = WowProperties()
            val maxWait = binder.bind("wow.wait.max-timeout", Duration::class.java).orElse(null)
                ?: defaults.wait.maxTimeout
            val drain = binder.bind("wow.cluster.drain-timeout", Duration::class.java).orElse(null)
                ?: defaults.cluster.drainTimeout
            return maxWait + drain
        }

        /** The timeout per shutdown phase [environment] sets; null when it sets none. */
        fun configured(environment: Environment): Duration? =
            Binder.get(environment).bind(TIMEOUT_PER_SHUTDOWN_PHASE, Duration::class.java).orElse(null)
    }
}

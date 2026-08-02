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

package me.ahoo.wow.spring.boot.starter.metrics

import me.ahoo.wow.metrics.Metrics
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role
import org.springframework.core.env.Environment
import java.util.concurrent.atomic.AtomicBoolean

@AutoConfiguration
@ConditionalOnWowEnabled
class MetricsAutoConfiguration {
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    internal fun metricsEnabledSynchronizer(environment: Environment): MetricsEnabledSynchronizer {
        return MetricsEnabledSynchronizer(
            environment.getProperty(ConditionalOnMetricsEnabled.ENABLED_KEY)
                ?.toBoolean()
                ?: true,
        )
    }

    @Bean
    @ConditionalOnMetricsEnabled
    @ConditionalOnMissingBean
    fun metricsBeanPostProcessor(): MetricsBeanPostProcessor {
        return MetricsBeanPostProcessor()
    }
}

internal class MetricsEnabledSynchronizer(
    private val enabled: Boolean,
) : BeanPostProcessor,
    DisposableBean {
    private val active = AtomicBoolean(true)

    init {
        MetricsEnabledContexts.register(enabled)
    }

    override fun destroy() {
        if (active.compareAndSet(true, false)) {
            MetricsEnabledContexts.unregister()
        }
    }
}

private object MetricsEnabledContexts {
    private val monitor = Any()
    private var initialEnabled = Metrics.enabled
    private var activeEnabled = Metrics.enabled
    private var activeContextCount = 0

    fun register(enabled: Boolean) = synchronized(monitor) {
        if (activeContextCount == 0) {
            initialEnabled = Metrics.enabled
            activeEnabled = enabled
        } else {
            require(activeEnabled == enabled) {
                "Conflicting [${ConditionalOnMetricsEnabled.ENABLED_KEY}] values across active " +
                    "Spring application contexts: expected [$activeEnabled], but found [$enabled]. " +
                    "Wow metrics enablement is process-wide."
            }
        }
        activeContextCount++
        Metrics.configureEnabled(activeEnabled)
    }

    fun unregister() = synchronized(monitor) {
        check(activeContextCount > 0) {
            "No active Spring application context is registered for Wow metrics."
        }
        activeContextCount--
        Metrics.configureEnabled(
            if (activeContextCount == 0) {
                initialEnabled
            } else {
                activeEnabled
            },
        )
    }
}

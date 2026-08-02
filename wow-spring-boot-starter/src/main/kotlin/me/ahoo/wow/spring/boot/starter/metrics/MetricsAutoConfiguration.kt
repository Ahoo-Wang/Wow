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

import io.micrometer.core.instrument.MeterRegistry
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered
import org.springframework.core.env.Environment

@AutoConfiguration
@ConditionalOnWowEnabled
class MetricsAutoConfiguration {
    @Bean
    fun wowMetricsEnablementBeanPostProcessor(
        environment: Environment,
    ): WowMetricsEnablementBeanPostProcessor =
        WowMetricsEnablementBeanPostProcessor(environment.isMetricsEnabled())

    @Bean
    @ConditionalOnMissingBean
    fun wowMetrics(
        meterRegistry: ObjectProvider<MeterRegistry>,
        environment: Environment,
    ): WowMetrics {
        if (!environment.isMetricsEnabled()) {
            return WowMetrics.NONE
        }
        return meterRegistry.getIfAvailable()?.let(::WowMetrics) ?: WowMetrics.NONE
    }

    @Bean
    @ConditionalOnMetricsEnabled
    @ConditionalOnMissingBean
    fun metricsBeanPostProcessor(metrics: WowMetrics): MetricsBeanPostProcessor =
        MetricsBeanPostProcessor(metrics)
}

/** Enforces `wow.metrics.enabled` as a global kill switch for auto-configured and custom metrics. */
class WowMetricsEnablementBeanPostProcessor(
    private val enabled: Boolean,
) : BeanPostProcessor,
    PriorityOrdered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any = if (!enabled && bean is WowMetrics) WowMetrics.NONE else bean
}

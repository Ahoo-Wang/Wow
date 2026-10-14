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

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import me.ahoo.wow.opentelemetry.NodeMetrics
import me.ahoo.wow.opentelemetry.OtelObserver
import me.ahoo.wow.opentelemetry.OtelQueryObserver
import me.ahoo.wow.opentelemetry.ParkedMetrics
import me.ahoo.wow.opentelemetry.TracingTopicProducer
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicAdmin
import me.ahoo.wow.spi.transport.TopicProducer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import kotlin.time.toKotlinDuration

/**
 * Tracing and metrics (wow-opentelemetry, decision D174), active when the application has wow-opentelemetry on its
 * classpath, unless `wow.opentelemetry.enabled=false` ([ObservabilityProperties]).
 *
 * The spans go through the application's [OpenTelemetry] bean (Spring Boot's, with an SDK and its exporters), else
 * [GlobalOpenTelemetry] (the Java agent's, or a no-op one); the metrics go to the application's [MeterRegistry] bean
 * (Spring Boot Actuator's), else Micrometer's global registry.
 *
 * - **Node**: an [OtelObserver], unless the application has an [Observer] of its own: the node's admissions,
 *   executions, decisions, appends, subscription handlings (consumptions of a topic among them) and timer firings
 *   are spans, in the trace each request, entry or message carries, with the commit latency, subscription lag and
 *   timer delay as timers.
 * - **Queries**: an [OtelQueryObserver] among the [QueryObserver]s the query plane takes: a span and a timer each.
 * - **Kafka produce**: every [TopicProducer] bean (Kafka's) is wrapped in a [TracingTopicProducer]: a span per send.
 * - **Parked entries**: a [ParkedMetrics] among the [ParkedNotifier]s the node takes; and [NodeMetrics], which samples
 *   every `wow.opentelemetry.sample-interval` how many entries each subscription has parked and where each
 *   subscription of the log stands (its position, backlog and progress: a rebuild's), from the node's start to its
 *   end, when there is a node.
 */
@AutoConfiguration(after = [WowAutoConfiguration::class])
@ConditionalOnClass(name = [OPENTELEMETRY_MODULE])
@ConditionalOnProperty(prefix = ObservabilityProperties.PREFIX, name = ["enabled"], matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties::class)
class ObservabilityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(Observer::class)
    fun wowObserver(openTelemetry: ObjectProvider<OpenTelemetry>, meters: ObjectProvider<MeterRegistry>): Observer =
        OtelObserver(openTelemetry.orGlobal(), meters.orGlobal())

    @Bean
    fun wowOtelQueryObserver(
        openTelemetry: ObjectProvider<OpenTelemetry>,
        meters: ObjectProvider<MeterRegistry>,
    ): QueryObserver = OtelQueryObserver(openTelemetry.orGlobal(), meters.orGlobal())

    @Bean
    fun wowParkedMetrics(meters: ObjectProvider<MeterRegistry>): ParkedNotifier = ParkedMetrics(meters.orGlobal())

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnBean(ServiceNode::class)
    fun wowNodeMetrics(
        node: ServiceNode,
        meters: ObjectProvider<MeterRegistry>,
        properties: ObservabilityProperties,
    ): NodeMetrics = NodeMetrics(node, meters.orGlobal(), properties.sampleInterval.toKotlinDuration())

    companion object {
        /**
         * Wraps every [TopicProducer] bean in a [TracingTopicProducer]: a span for each Kafka produce. A bean that is
         * also another port of the transport (an in-memory one is a [PartitionedSource] and a [TopicAdmin] too) is
         * left as it is: the wrapper would hide those.
         */
        @Bean
        @JvmStatic
        fun wowTracingTopicProducers(openTelemetry: ObjectProvider<OpenTelemetry>): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                    if (bean is TopicProducer && traceable(bean)) {
                        TracingTopicProducer(bean, openTelemetry.orGlobal())
                    } else {
                        bean
                    }
            }

        /** Whether [producer] is wrapped: neither traced already nor another port of its transport. */
        private fun traceable(producer: TopicProducer): Boolean =
            producer !is TracingTopicProducer && producer !is PartitionedSource && producer !is TopicAdmin

        private fun ObjectProvider<OpenTelemetry>.orGlobal(): OpenTelemetry = getIfUnique { GlobalOpenTelemetry.get() }

        private fun ObjectProvider<MeterRegistry>.orGlobal(): MeterRegistry = getIfUnique { Metrics.globalRegistry }
    }
}

/** A class of wow-opentelemetry, present only when the application has the module. */
internal const val OPENTELEMETRY_MODULE = "me.ahoo.wow.opentelemetry.OtelObserver"

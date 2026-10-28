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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.observability.NodeMetrics
import me.ahoo.wow.observability.OtelObserver
import me.ahoo.wow.observability.OtelQueryObserver
import me.ahoo.wow.observability.ParkedMetrics
import me.ahoo.wow.observability.QueryLimitMetrics
import me.ahoo.wow.observability.TracingTopicProducer
import me.ahoo.wow.query.observe.QueryObserver
import me.ahoo.wow.query.store.QueryStores
import me.ahoo.wow.runtime.node.NodeSampler
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicAdmin
import me.ahoo.wow.spi.transport.TopicProducer
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Tracing and metrics (wow-observability), active when the application has wow-observability on its
 * classpath, unless `wow.observability.enabled=false` ([ObservabilityProperties]).
 *
 * The spans go through the application's [OpenTelemetry] bean (Spring Boot's, with an SDK and its exporters), else
 * [GlobalOpenTelemetry] (the Java agent's, or a no-op one); the metrics go to the application's [MeterRegistry] bean
 * (Spring Boot Actuator's), else Micrometer's global registry.
 *
 * - **Node**: an [OtelObserver], beside any [Observer] of the application's (the node tells every one): the node's admissions,
 *   executions, decisions, appends, subscription handlings (consumptions of a topic among them) and timer firings
 *   are spans, in the trace each request, entry or message carries, with the commit latency, subscription lag and
 *   timer delay as timers.
 * - **Queries**: an [OtelQueryObserver] among the [QueryObserver]s the query plane takes: a span and a timer each;
 *   and the stores' query bulkheads ([QueryLimitMetrics]): their limits, write pressure and decreases.
 * - **Kafka produce**: every [TopicProducer] bean (Kafka's) is wrapped in a [TracingTopicProducer]: a span per send.
 * - **Parked entries**: a [ParkedMetrics] among the [ParkedNotifier]s the node takes; and [NodeMetrics], which samples
 *   every `wow.node.sample-interval` how many entries each subscription has parked and where each
 *   subscription of the log stands (its position, backlog and progress: a rebuild's), from the node's start to its
 *   end, when there is a node.
 */
@AutoConfiguration(after = [WowAutoConfiguration::class])
@ConditionalOnClass(name = [OBSERVABILITY_MODULE])
@ConditionalOnProperty(prefix = ObservabilityProperties.PREFIX, name = ["enabled"], matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties::class)
class ObservabilityAutoConfiguration {
    @Bean
    fun wowObserver(
        openTelemetry: ObjectProvider<OpenTelemetry>,
        meters: ObjectProvider<MeterRegistry>,
        beans: ListableBeanFactory,
    ): Observer = OtelObserver(openTelemetry.orGlobal(), meters.orGlobal(), messagingSystem = messagingSystem(beans))

    @Bean
    fun wowOtelQueryObserver(
        openTelemetry: ObjectProvider<OpenTelemetry>,
        meters: ObjectProvider<MeterRegistry>,
    ): QueryObserver = OtelQueryObserver(openTelemetry.orGlobal(), meters.orGlobal())

    @Bean
    fun wowParkedMetrics(meters: ObjectProvider<MeterRegistry>): ParkedNotifier = ParkedMetrics(meters.orGlobal())

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnBean(ServiceNode::class)
    @InternalWowApi
    fun wowNodeMetrics(
        node: ServiceNode,
        meters: ObjectProvider<MeterRegistry>,
        sampler: NodeSampler,
    ): NodeMetrics = NodeMetrics(node, meters.orGlobal(), sampler)

    /**
     * The meters of the stores' query bulkheads: each one's limit and, of those that adapt, the write
     * pressure and the limit's decreases, registered once every bean exists, when the node queries its stores.
     */
    @Bean
    @InternalWowApi
    fun wowQueryLimitMetrics(
        stores: ObjectProvider<QueryStores>,
        meters: ObjectProvider<MeterRegistry>,
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        stores.ifAvailable?.let { QueryLimitMetrics.register(it.all, it.limits, meters.orGlobal()) }
    }

    companion object {
        /**
         * Wraps every [TopicProducer] bean in a [TracingTopicProducer]: a span for each Kafka produce. A bean that is
         * also another port of the transport (an in-memory one is a [PartitionedSource] and a [TopicAdmin] too) is
         * left as it is: the wrapper would hide those.
         */
        @Bean
        @JvmStatic
        fun wowTracingTopicProducers(
            openTelemetry: ObjectProvider<OpenTelemetry>,
            beans: ObjectProvider<ListableBeanFactory>,
        ): BeanPostProcessor =
            object : BeanPostProcessor {
                override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                    if (bean is TopicProducer && traceable(bean)) {
                        TracingTopicProducer(bean, openTelemetry.orGlobal(), messagingSystem(beans.getObject()))
                    } else {
                        bean
                    }
            }

        /** Whether [producer] is wrapped: neither traced already nor another port of its transport. */
        private fun traceable(producer: TopicProducer): Boolean =
            producer !is TracingTopicProducer && producer !is PartitionedSource && producer !is TopicAdmin

        /** The `messaging.system` of the node's topics: its transport provider's type, `memory` for the in-memory one. */
        private fun messagingSystem(beans: ListableBeanFactory): String =
            beans.getBeanProvider(SelectedTransport::class.java).ifAvailable?.provider?.type ?: OtelObserver.MEMORY_SYSTEM

        private fun ObjectProvider<OpenTelemetry>.orGlobal(): OpenTelemetry = getIfUnique { GlobalOpenTelemetry.get() }

        private fun ObjectProvider<MeterRegistry>.orGlobal(): MeterRegistry = getIfUnique { Metrics.globalRegistry }
    }
}

/** A class of wow-observability, present only when the application has the module. */
internal const val OBSERVABILITY_MODULE = "me.ahoo.wow.observability.OtelObserver"

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

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicAdmin
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.transport.TopicTransport
import me.ahoo.wow.spi.transport.TransportConfig
import me.ahoo.wow.spi.transport.TransportProvider
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import kotlin.time.toKotlinDuration

/**
 * The node's topic transport (decisions D134, D135, finding R5.B-5): the one a [TransportProvider] gives, found on the
 * classpath or among the application's beans ([PortProviders]), configured by its keys (`wow.kafka.*` for Wow's Kafka
 * transport). The host builds nothing of a broker's own: it takes the transport's ports.
 *
 * - **Transport**: the [TopicTransport] the configured provider opens, closed with the context after the node stopped;
 *   its [TopicProducer], [PartitionedSource] and [TopicAdmin] as beans, unless the application has its own.
 * - **Sink**: [PublishedSinks.topics] over the producer, one subscription per stream the Manifest publishes, each
 *   written to its topic after `wow.kafka.topic-prefix`.
 * - **Source**: the [SourceTopics] over the source, the producer and the admin, after the same prefix, with
 *   `wow.kafka.command-intake`, `wow.kafka.topics.ddl` and `wow.kafka.topics.partition-refresh`: the subscriptions
 *   of other contexts' streams read through it, and a saga's commands to other contexts go through it.
 * - **Warnings**: once the application is ready, what the transport warns about the node's command topics
 *   ([TopicTransport.warnings]), such as one that does not survive the loss of a broker (decision D143).
 * - **Offset gaps**: the reads that started past their partition's retention ([PartitionedSource.offsetGaps]), as the
 *   counter [OFFSET_GAPS] when the application has Micrometer: each is an error to alert on.
 */
@AutoConfiguration(after = [InMemoryTopicsAutoConfiguration::class])
@EnableConfigurationProperties(KafkaProperties::class)
class TransportAutoConfiguration {
    /** The topic transports and the Sinks of the node: on the classpath, and the application's provider beans. */
    @Bean
    internal fun wowPortProviders(
        transports: ObjectProvider<TransportProvider>,
        sinks: ObjectProvider<SinkProvider>,
    ): PortProviders = PortProviders.load(
        Thread.currentThread().contextClassLoader,
        transports.orderedStream().toList(),
        sinks.orderedStream().toList(),
    )

    @Configuration(proxyBeanMethods = false)
    @Conditional(TransportAutoConfiguration.OnTransport::class)
    class Transport {
        @Bean(destroyMethod = "close")
        internal fun wowTopicTransport(
            providers: PortProviders,
            environment: Environment,
            beanFactory: ListableBeanFactory,
        ): TopicTransport {
            val (provider, config) = configNotNull(
                providers.transportConfig(environment, BeanStoreResources(beanFactory)),
                "wow.<transport>",
                "Configure the transport of a provider: ${providers.transports.keys.joinToString { "wow.$it.*" }}.",
            ) { "A transport provider is declared, but no transport is configured." }
            logger.info { "The node's topic transport is ${provider.type} (${config.key})." }
            return PortProviders.configured(config.key) { provider.open(config) }
        }

        // The transport closes its ports itself: they are not closed again as beans.
        @Bean(destroyMethod = "")
        @ConditionalOnMissingBean(TopicProducer::class)
        fun wowTopicProducer(transport: TopicTransport): TopicProducer = transport.producer

        @Bean(destroyMethod = "")
        @ConditionalOnMissingBean(PartitionedSource::class)
        fun wowPartitionedSource(transport: TopicTransport): PartitionedSource = transport.source

        @Bean(destroyMethod = "")
        @ConditionalOnMissingBean(TopicAdmin::class)
        fun wowTopicAdmin(transport: TopicTransport): TopicAdmin = transport.admin

        @Bean(TRANSPORT_SINKS)
        fun wowTransportSinks(
            manifest: Manifest,
            producer: TopicProducer,
            properties: KafkaProperties
        ): PublishedSinks =
            PublishedSinks.topics(manifest, producer, properties.topicPrefix)

        @Bean
        @ConditionalOnMissingBean
        fun wowSourceTopics(
            source: PartitionedSource,
            producer: TopicProducer,
            admin: TopicAdmin,
            properties: KafkaProperties,
        ): SourceTopics = SourceTopics(
            source,
            properties.topicPrefix,
            producer,
            properties.commandIntake,
            admin,
            properties.topics.ddl,
            properties.topics.partitionRefresh.toKotlinDuration(),
        )

        /** Logs, once the application is ready, what the transport warns about the node's command topics. */
        @Bean
        internal fun wowTransportWarnings(
            transport: TopicTransport,
            node: ObjectProvider<ServiceNode>,
            topics: ObjectProvider<SourceTopics>,
        ): ApplicationListener<ApplicationReadyEvent> = ApplicationListener {
            val names = node.ifAvailable?.contexts?.keys.orEmpty()
                .mapNotNull { context -> topics.ifAvailable?.commandsOf(context) }.toSet()
            runBlocking { transport.warnings(names) }.forEach { logger.warn { it } }
        }
    }

    /**
     * The offset gaps the node's source met (finding A2-11), as the counter [OFFSET_GAPS], when the application has
     * Micrometer.
     *
     * The counting function captures [sources], so the meter holds it strongly (finding R2.3-4): Micrometer only
     * weakly references a function counter's object.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = [MICROMETER])
    @Conditional(TransportAutoConfiguration.OnTransport::class)
    class TransportMetrics {
        @Bean
        fun wowOffsetGaps(
            sources: ObjectProvider<PartitionedSource>,
            meters: ObjectProvider<MeterRegistry>
        ): FunctionCounter =
            FunctionCounter.builder(OFFSET_GAPS, sources) { _ -> sources.getIfUnique()?.offsetGaps?.toDouble() ?: 0.0 }
                .description("Offsets a read was to start from that its partition no longer holds.")
                .register(meters.getIfUnique { Metrics.globalRegistry })
    }

    /**
     * Whether a topic transport is configured: a provider on the classpath finds its settings under `wow.<type>`, or
     * the application declares a [TransportProvider] bean. The in-memory topics (`wow.topics.in-memory`) are not one.
     */
    internal class OnTransport : SpringBootCondition() {
        override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
            val providers = PortProviders.load(context.classLoader)
            val configured = providers.transports.values.filter { provider ->
                provider.available(
                    TransportConfig.of(
                        provider.type,
                        provider.type,
                        PortProviders.optionsOf(context.environment, provider.type)
                    ),
                )
            }
            val beans = context.beanFactory?.getBeanNamesForType(TransportProvider::class.java, true, false).orEmpty()
            return when {
                configured.isNotEmpty() -> ConditionOutcome.match(
                    "the transport ${configured.map { it.type }} is configured"
                )
                beans.isNotEmpty() -> ConditionOutcome.match("the transport providers ${beans.toList()} are declared")
                else -> ConditionOutcome.noMatch("no topic transport is configured")
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** The counter of the offset gaps the node's source met. */
        const val OFFSET_GAPS: String = "wow.kafka.offset.gaps"

        private const val MICROMETER = "io.micrometer.core.instrument.MeterRegistry"

        /** The name of the transport's [PublishedSinks] bean. */
        const val TRANSPORT_SINKS: String = "wowTransportSinks"
    }
}

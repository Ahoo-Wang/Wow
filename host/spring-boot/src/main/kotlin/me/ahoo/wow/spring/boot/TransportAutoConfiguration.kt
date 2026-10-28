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
import io.micrometer.core.instrument.Tags
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.observability.WowMetrics
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.config.configNotNull
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicAdmin
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.transport.TopicTransport
import me.ahoo.wow.spi.transport.TopicTransportOptions
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
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import kotlin.time.Duration

/**
 * The node's topic transport: the one a [TransportProvider] gives, found on the
 * classpath or among the application's beans ([PortProviders]), configured by its keys (`wow.kafka.*` for Wow's Kafka
 * transport). The host builds nothing of a broker's own: it takes the transport's ports.
 *
 * - **Transport**: the [TopicTransport] the configured provider opens, closed with the context after the node stopped;
 *   its [TopicProducer], [PartitionedSource] and [TopicAdmin] as beans, unless the application has its own.
 * - **Sink**: [PublishedSinks.topics] over the producer, one subscription per stream the Manifest publishes, each
 *   written to its topic after the transport's `topic-prefix` (`wow.kafka.topic-prefix`).
 * - **Source**: the [SourceTopics] over the source, the producer and the admin, after the same prefix, with the
 *   transport's `command-intake.enabled`, `ddl` and `topics.partition-refresh` ([TopicTransportOptions], which every
 *   topic transport has): the subscriptions of other contexts' streams read through it, and a saga's commands to
 *   other contexts go through it.
 * - **Warnings**: once the application is ready, what the transport warns about the node's command topics
 *   ([TopicTransport.warnings]), such as one that does not survive the loss of a broker.
 * - **Offset gaps**: the reads that started past their partition's retention ([PartitionedSource.offsetGaps]), as the
 *   counter [WowMetrics.OFFSET_GAPS] when the application has Micrometer: each is an error to alert on.
 */
@AutoConfiguration(after = [InMemoryTopicsAutoConfiguration::class])
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
        /** The provider of the node's transport and the configuration it opens it from. */
        @Bean
        internal fun wowSelectedTransport(
            providers: PortProviders,
            environment: Environment,
            beanFactory: ListableBeanFactory,
            development: ObjectProvider<DevelopmentMode>,
            properties: ObjectProvider<WowProperties>,
        ): SelectedTransport {
            val named = (properties.ifAvailable?.transport ?: TransportSelection.AUTO).trim().lowercase()
                .takeUnless { it == TransportSelection.AUTO }
            val (provider, config) = configNotNull(
                providers.transportConfig(
                    environment,
                    BeanStoreResources(beanFactory),
                    development.ifAvailable?.active == true,
                    named,
                ),
                named?.let { "wow.$it" } ?: "wow.<transport>",
                "Configure the transport of a provider: ${providers.transports.keys.joinToString { "wow.$it.*" }}.",
            ) {
                named?.let { "wow.transport is $it, but no transport of that type is configured." }
                    ?: "A transport provider is declared, but no transport is configured."
            }
            logger.info { "The node's topic transport is ${provider.type} (${config.key})." }
            return SelectedTransport(provider, config)
        }

        @Bean(destroyMethod = "close")
        internal fun wowTopicTransport(selected: SelectedTransport): TopicTransport =
            PortProviders.configured(selected.config.key) { selected.provider.open(selected.config) }

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
        internal fun wowTransportSinks(
            manifest: Manifest,
            producer: TopicProducer,
            selected: SelectedTransport,
        ): PublishedSinks =
            PublishedSinks.topics(manifest, producer, selected.topicPrefix)

        @Bean
        @ConditionalOnMissingBean
        internal fun wowSourceTopics(
            source: PartitionedSource,
            producer: TopicProducer,
            admin: TopicAdmin,
            selected: SelectedTransport,
            wow: ObjectProvider<WowProperties>,
        ): SourceTopics = SourceTopics(
            source,
            selected.topicPrefix,
            producer,
            selected.commandIntake,
            admin,
            selected.ddl ?: wow.ifAvailable?.ddl ?: Ddl.AUTO,
            selected.partitionRefresh,
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
     * The offset gaps the node's source met, as the counter [WowMetrics.OFFSET_GAPS], when the application has
     * Micrometer.
     *
     * The counting function captures [sources], so the meter holds it strongly: Micrometer only
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
        ): FunctionCounter = WowMetrics.OFFSET_GAPS.functionCounter(
            meters.getIfUnique { Metrics.globalRegistry },
            sources,
            Tags.empty(),
        ) { _ -> sources.getIfUnique()?.offsetGaps?.toDouble() ?: 0.0 }
    }

    /**
     * Whether `wow.transport` selects a provider's transport ([TransportSelection.PROVIDER]): one a provider on the
     * classpath finds configured under `wow.<type>`, a [TransportProvider] bean of the application, or the type it names.
     */
    internal class OnTransport : SpringBootCondition() {
        override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
            val selection = TransportSelection.of(context)
            return if (selection == TransportSelection.PROVIDER) {
                ConditionOutcome.match("wow.transport selects a provider's transport")
            } else {
                ConditionOutcome.noMatch("wow.transport selects $selection")
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        private const val MICROMETER = "io.micrometer.core.instrument.MeterRegistry"

        /** The name of the transport's [PublishedSinks] bean. */
        const val TRANSPORT_SINKS: String = "wowTransportSinks"
    }
}

/**
 * The [provider] of the node's topic transport and the [config] it is opened from, whose settings every topic
 * transport has ([TopicTransportOptions]) the host reads for the node's topics.
 */
internal class SelectedTransport(val provider: TransportProvider, val config: TransportConfig) {
    val topicPrefix: String get() = config.option(TopicTransportOptions.TOPIC_PREFIX)

    val commandIntake: Boolean get() = config.option(TopicTransportOptions.COMMAND_INTAKE)

    val partitionRefresh: Duration get() = config.option(TopicTransportOptions.PARTITION_REFRESH)

    /** The transport's `ddl`, null when it sets none. */
    val ddl: Ddl? get() = config.option(TopicTransportOptions.DDL)?.let { text ->
        val key = "${config.key}.${TopicTransportOptions.DDL.key}"
        configNotNull(
            Ddl.entries.firstOrNull { it.name.equals(text, ignoreCase = true) },
            key,
            "Set it to auto, validate or none.",
            text,
        ) { "$key is $text, which is no DDL mode." }
    }
}

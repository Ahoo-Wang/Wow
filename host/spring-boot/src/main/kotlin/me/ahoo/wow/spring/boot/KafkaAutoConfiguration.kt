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
import me.ahoo.wow.kafka.KafkaOptions
import me.ahoo.wow.kafka.KafkaPartitionedSource
import me.ahoo.wow.kafka.KafkaTopicAdmin
import me.ahoo.wow.kafka.KafkaTopicProducer
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.spi.transport.PartitionedSource
import me.ahoo.wow.spi.transport.TopicAdmin
import me.ahoo.wow.spi.transport.TopicProducer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import kotlin.time.toKotlinDuration

/**
 * The Kafka transport, the Kafka Sink and the Kafka-sourced subscriptions (wow-kafka; decisions D31, D58, D132, D134,
 * D135), active when the application has wow-kafka on its classpath and sets `wow.kafka.bootstrap-servers`
 * ([KafkaProperties]):
 *
 * - **Producer**: a [KafkaTopicProducer], idempotent with `acks=all`, unless the application has a [TopicProducer]
 *   bean of its own; closed with the context, after the node has stopped.
 * - **Sink**: [PublishedSinks.topics] over it, one subscription per stream the Manifest publishes
 *   (`ContextManifest.published`), each written to its topic after `wow.kafka.topic-prefix`: a
 *   [PublishedSinks] bean, which the node takes with its subscriptions.
 * - **Source**: a [KafkaPartitionedSource], unless the application has a [PartitionedSource] bean of its own, as the
 *   node's [SourceTopics] after the same prefix: the subscriptions of other contexts' published streams read their
 *   topics through it, partitions assigned by the node's leases and offsets kept in its checkpoint store, with no
 *   consumer group. Closed with the context, after the node has stopped.
 * - **Commands across contexts** (decisions D33, D135–D137): the same [SourceTopics] carry the producer, so a saga's
 *   command to another context is sent to its command topic, `wow.<context>.commands`, and the rejections that come
 *   back are read from `wow.<context>.receipts` for the sagas with `@OnRejected` handlers. With
 *   `wow.kafka.command-intake=true`, each context the node hosts also executes the commands of its own command topic,
 *   which must have as many partitions as the context's log (`wow.log.partitions`): the node does not start otherwise.
 *   So does each context with an aggregate type routed through Kafka (decision D143).
 *   A read whose partition no longer holds its offset (retention, a topic created again) is an offset gap, handled as
 *   `wow.kafka.offset-gap` says and counted in [OFFSET_GAPS] (finding A2-11).
 * - **Topics** (decision D168): a [KafkaTopicAdmin], unless the application has a [TopicAdmin] bean of its own,
 *   through which the node validates or creates the topics its contexts own at its start, as `wow.kafka.topics.ddl`
 *   says. Closed with the context.
 */
@AutoConfiguration
@ConditionalOnClass(name = [KAFKA_MODULE])
@EnableConfigurationProperties(KafkaProperties::class)
class KafkaAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = KafkaProperties.PREFIX, name = ["bootstrap-servers"])
    class KafkaTransport {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(TopicProducer::class)
        fun wowKafkaTopicProducer(properties: KafkaProperties): KafkaTopicProducer =
            KafkaTopicProducer.create(options(properties))

        @Bean(KAFKA_SINKS)
        fun wowKafkaSinks(manifest: Manifest, producer: TopicProducer, properties: KafkaProperties): PublishedSinks =
            PublishedSinks.topics(manifest, producer, properties.topicPrefix)

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(PartitionedSource::class)
        fun wowKafkaPartitionedSource(properties: KafkaProperties): KafkaPartitionedSource =
            KafkaPartitionedSource.create(options(properties), properties.offsetGap)

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(TopicAdmin::class)
        fun wowKafkaTopicAdmin(properties: KafkaProperties): KafkaTopicAdmin = KafkaTopicAdmin.create(
            options(properties),
            properties.topics.replicationFactor,
            properties.topics.creationConfigs(),
        )

        /**
         * Checks, once the application is ready, that the command topics of the node's contexts survive the loss of a
         * broker (decision D143, review A3-15): it warns about each that does not, and about a configuration that
         * would create such topics, unless a dev profile is active.
         */
        @Bean
        fun wowKafkaDurabilityCheck(
            properties: KafkaProperties,
            environment: Environment,
            node: ObjectProvider<ServiceNode>,
            topics: ObjectProvider<SourceTopics>,
            admin: ObjectProvider<TopicAdmin>,
        ): ApplicationListener<ApplicationReadyEvent> = ApplicationListener {
            val durability = KafkaDurability(properties.topics, environment.activeProfiles.toSet())
            durability.configured().forEach { logger.warn { it } }
            val kafka = admin.ifAvailable as? KafkaTopicAdmin ?: return@ApplicationListener
            val names = node.ifAvailable?.contexts?.keys.orEmpty()
                .mapNotNull { context -> topics.ifAvailable?.commandsOf(context) }.toSet()
            durability.checkQuietly { kafka.durability(names) }.forEach { logger.warn { it } }
        }

        @Bean
        @ConditionalOnMissingBean
        fun wowKafkaSourceTopics(
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
    }

    /**
     * The offset gaps the Kafka source met (finding A2-11), as the counter [OFFSET_GAPS], when the application has
     * Micrometer: each gap is an error to alert on, whatever `wow.kafka.offset-gap` does with it.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = [MICROMETER])
    @ConditionalOnProperty(prefix = KafkaProperties.PREFIX, name = ["bootstrap-servers"])
    class KafkaMetrics {
        /** Counts the gaps of the node's source when it is Kafka's; an application's own source counts none. */
        @Bean
        fun wowKafkaOffsetGaps(
            sources: ObjectProvider<PartitionedSource>,
            meters: ObjectProvider<MeterRegistry>,
        ): FunctionCounter =
            FunctionCounter.builder(OFFSET_GAPS, sources) {
                (it.getIfUnique() as? KafkaPartitionedSource)?.offsetGaps?.toDouble() ?: 0.0
            }
                .description("Offsets a Kafka read was to start from that its partition no longer holds.")
                .register(meters.getIfUnique { Metrics.globalRegistry })
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** The counter of the offset gaps the Kafka source met. */
        const val OFFSET_GAPS: String = "wow.kafka.offset.gaps"

        private const val MICROMETER = "io.micrometer.core.instrument.MeterRegistry"

        /** The name of the sinks' [PublishedSinks] bean. */
        const val KAFKA_SINKS: String = "wowKafkaSinks"

        /** The transport's options from [properties]. */
        fun options(properties: KafkaProperties): KafkaOptions = KafkaOptions(
            bootstrapServers = checkNotNull(properties.bootstrapServers) { "wow.kafka.bootstrap-servers is not set." },
            properties = properties.properties.toMap(),
            pollTimeout = properties.pollTimeout.toKotlinDuration(),
            metadataMaxAge = properties.topics.partitionRefresh.toKotlinDuration(),
        )
    }
}

/** A class of wow-kafka, present only when the application has the module. */
internal const val KAFKA_MODULE = "me.ahoo.wow.kafka.KafkaModule"

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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kafka.KafkaOptions
import me.ahoo.wow.kafka.KafkaTopicProducer
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.ContextNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.spi.transport.TopicProducer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * The Kafka transport and the Kafka Sink (wow-kafka; decisions D31, D58, D132, D135), active when the application has
 * wow-kafka on its classpath and sets `wow.kafka.bootstrap-servers` ([KafkaProperties]):
 *
 * - **Producer**: a [KafkaTopicProducer], idempotent with `acks=all`, unless the application has a [TopicProducer]
 *   bean of its own; closed with the context, after the node has stopped.
 * - **Sink**: [PublishedSinks.topics] over it, one subscription per stream the Manifest publishes
 *   (`ContextManifest.published`), each written to its topic after `wow.kafka.topic-prefix`.
 * - **Subscriptions**: the node takes one [SubscriptionFactory] besides the annotated processors' bindings, so the
 *   sinks are given to it as the primary one, followed by the application's own [SubscriptionFactory] beans, if any
 *   ([SinksAndApplication]).
 */
@AutoConfiguration
@ConditionalOnClass(name = [KAFKA_MODULE])
@EnableConfigurationProperties(KafkaProperties::class)
class KafkaAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = KafkaProperties.PREFIX, name = ["bootstrap-servers"])
    class KafkaSink {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(TopicProducer::class)
        fun wowKafkaTopicProducer(properties: KafkaProperties): KafkaTopicProducer =
            KafkaTopicProducer.create(options(properties))

        @Bean(KAFKA_SINKS)
        @Primary
        fun wowKafkaSinks(
            manifest: Manifest,
            producer: TopicProducer,
            properties: KafkaProperties,
            factories: ObjectProvider<SubscriptionFactory>,
        ): SubscriptionFactory =
            SinksAndApplication(PublishedSinks.topics(manifest, producer, properties.topicPrefix), factories)
    }

    /**
     * [sinks], then every other [SubscriptionFactory] bean of the application in its order, resolved when the node is
     * assembled.
     */
    internal class SinksAndApplication(
        private val sinks: PublishedSinks,
        private val factories: ObjectProvider<SubscriptionFactory>,
    ) : SubscriptionFactory {
        private val combined: SubscriptionFactory by lazy {
            factories.orderedStream().toList().filter { it !== this }.fold<SubscriptionFactory, SubscriptionFactory>(
                sinks,
            ) { all, factory -> all + factory }
        }

        override fun subscriptions(context: ContextNode): List<Subscription> = combined.subscriptions(context)

        override fun viewTypes(context: String, models: List<AggregateModel<*>>): Set<AggregateType> =
            combined.viewTypes(context, models)

        override fun stateReaders(context: String, models: List<AggregateModel<*>>): Map<String, Set<AggregateType>> =
            combined.stateReaders(context, models)
    }

    companion object {
        /** The name of the sinks' [SubscriptionFactory] bean. */
        const val KAFKA_SINKS: String = "wowKafkaSinks"

        /** The transport's options from [properties]. */
        fun options(properties: KafkaProperties): KafkaOptions = KafkaOptions(
            bootstrapServers = checkNotNull(properties.bootstrapServers) { "wow.kafka.bootstrap-servers is not set." },
            properties = properties.properties.toMap(),
        )
    }
}

/** A class of wow-kafka, present only when the application has the module. */
internal const val KAFKA_MODULE = "me.ahoo.wow.kafka.KafkaModule"

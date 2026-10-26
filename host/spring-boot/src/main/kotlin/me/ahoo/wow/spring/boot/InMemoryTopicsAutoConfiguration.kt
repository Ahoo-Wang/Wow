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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.TopicDdl
import me.ahoo.wow.runtime.sink.PublishedSinks
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/**
 * The node's topics in its JVM, for trying a service out without Kafka (`wow.topics.in-memory`, DX gap G27): one
 * [InMemoryTopics] of `wow.log.partitions` partitions, unless the application has one of its own, as wow-kafka's beans
 * give them on Kafka:
 *
 * - the [SourceTopics] with the producer and the intake, so each context the node hosts executes its command topic,
 *   whose topics the node creates at its start ([TopicDdl.AUTO]);
 * - the [PublishedSinks] of the streams the Manifest publishes.
 *
 * It warns at startup that nothing leaves the JVM, and fails with `wow.kafka.bootstrap-servers` set too.
 */
@AutoConfiguration(before = [KafkaAutoConfiguration::class])
@ConditionalOnProperty(prefix = InMemoryTopicsAutoConfiguration.PREFIX, name = ["in-memory"], havingValue = "true")
@EnableConfigurationProperties(WowProperties::class)
@InternalWowApi
class InMemoryTopicsAutoConfiguration(environment: Environment) {
    init {
        configCheck(
            environment.getProperty("${KafkaProperties.PREFIX}.bootstrap-servers").isNullOrBlank(),
            "wow.topics.in-memory",
            true,
            "Unset one of them: the node's topics are either in its JVM, for local development, or on Kafka.",
        ) { "wow.topics.in-memory and wow.kafka.bootstrap-servers are both set." }
    }

    @Bean
    @ConditionalOnMissingBean
    fun wowInMemoryTopics(properties: WowProperties): InMemoryTopics = InMemoryTopics(properties.log.partitions)

    @Bean
    @ConditionalOnMissingBean
    fun wowInMemorySourceTopics(topics: InMemoryTopics): SourceTopics =
        SourceTopics(topics, producer = topics, intake = true, admin = topics, ddl = TopicDdl.AUTO)

    @Bean(IN_MEMORY_SINKS)
    fun wowInMemorySinks(manifest: Manifest, topics: InMemoryTopics): PublishedSinks =
        PublishedSinks.topics(manifest, topics)

    /** Warns, once the application is ready, that the node's topics are in its JVM. */
    @Bean
    fun wowInMemoryTopicsWarning(): ApplicationListener<ApplicationReadyEvent> = ApplicationListener {
        logger.warn { WARNING }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        const val PREFIX: String = "wow.topics"

        /** The name of the in-memory sinks' [PublishedSinks] bean. */
        const val IN_MEMORY_SINKS: String = "wowInMemorySinks"

        /** What the node warns at startup with in-memory topics. */
        const val WARNING: String = "wow.topics.in-memory: the node's command topics and published streams are in " +
            "its JVM, not on Kafka. Other services do not see them, and nothing survives a restart: for local " +
            "development and tests only."
    }
}

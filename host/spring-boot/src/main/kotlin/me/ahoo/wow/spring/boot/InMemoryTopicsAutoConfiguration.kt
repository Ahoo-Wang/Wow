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
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.spi.Ddl
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * The node's topics in its JVM, for trying a service out without Kafka (DX gap G27), when `wow.transport` selects them
 * ([TransportSelection.MEMORY]: `memory`, or `auto` in development without a configured transport): one
 * [InMemoryTopics] of `wow.log.partitions` partitions, each keeping its last `wow.memory-topic-retention` messages,
 * unless the application has one of its own, as wow-kafka's beans give them on Kafka:
 *
 * - the [SourceTopics] with the producer and the intake, so each context the node hosts executes its command topic,
 *   whose topics the node creates at its start ([Ddl.AUTO]);
 * - the [PublishedSinks] of the streams the Manifest publishes.
 *
 * It warns at startup that nothing leaves the JVM. `wow.transport=memory` outside development is refused.
 */
@AutoConfiguration
@Conditional(InMemoryTopicsAutoConfiguration.OnMemory::class)
@EnableConfigurationProperties(WowProperties::class)
@InternalWowApi
class InMemoryTopicsAutoConfiguration(environment: Environment) {
    init {
        val properties = WowProperties.of(environment)
        DevelopmentMode.of(properties, environment).require(
            "wow.transport",
            properties.transport,
            "keeps the node's topics in its JVM, where other services do not see them and nothing survives a restart",
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun wowInMemoryTopics(properties: WowProperties): InMemoryTopics =
        InMemoryTopics(properties.log.partitions).apply { retention = properties.memoryTopicRetention }

    @Bean
    @ConditionalOnMissingBean
    fun wowInMemorySourceTopics(topics: InMemoryTopics): SourceTopics =
        SourceTopics(topics, producer = topics, intake = true, admin = topics, ddl = Ddl.AUTO)

    @Bean(IN_MEMORY_SINKS)
    fun wowInMemorySinks(manifest: Manifest, topics: InMemoryTopics): PublishedSinks =
        PublishedSinks.topics(manifest, topics)

    /** Warns, once the application is ready, that the node's topics are in its JVM. */
    @Bean
    fun wowInMemoryTopicsWarning(): ApplicationListener<ApplicationReadyEvent> = ApplicationListener {
        logger.warn { WARNING }
    }

    /** Whether `wow.transport` keeps the node's topics in its JVM ([TransportSelection.MEMORY]). */
    internal class OnMemory : SpringBootCondition() {
        override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
            val selection = TransportSelection.of(context)
            return if (selection == TransportSelection.MEMORY) {
                ConditionOutcome.match("wow.transport selects the topics in the JVM")
            } else {
                ConditionOutcome.noMatch("wow.transport selects $selection")
            }
        }
    }

    @InternalWowApi
    companion object {
        private val logger = KotlinLogging.logger {}

        /** The name of the in-memory sinks' [PublishedSinks] bean. */
        const val IN_MEMORY_SINKS: String = "wowInMemorySinks"

        /** What the node warns at startup with in-memory topics. */
        const val WARNING: String = "wow.transport=memory: the node's command topics and published streams are in " +
            "its JVM, not on Kafka. Other services do not see them, and nothing survives a restart: for local " +
            "development and tests only."
    }
}

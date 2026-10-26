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
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.transport.TransportConfig
import me.ahoo.wow.spi.transport.TransportProvider
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.env.Environment

/**
 * The node's topic transport, as `wow.transport` selects it (design K9: derived, the key an override):
 *
 * - [PROVIDER]: `auto` with a transport a provider on the classpath finds configured, or a [TransportProvider] bean of
 *   the application; `kafka` or another provider's type names it.
 * - [MEMORY]: `memory`, or `auto` without a configured transport in development ([DevelopmentMode]): the topics are in
 *   the node's JVM ([InMemoryTopicsAutoConfiguration]).
 * - [NONE]: `auto` without a configured transport outside development. A node whose Manifest needs topics refuses to
 *   start then ([TransportNeeds]).
 */
@InternalWowApi
enum class TransportSelection {
    PROVIDER,
    MEMORY,
    NONE,
    ;

    @InternalWowApi
    companion object {
        /** The value of `wow.transport` that derives the transport. */
        const val AUTO: String = "auto"

        /** The value of `wow.transport` that keeps the topics in the JVM. */
        const val MEMORY_TYPE: String = "memory"

        /** The selection of [context], a condition's: the environment, the classpath's providers and the beans. */
        fun of(context: ConditionContext): TransportSelection {
            val beans = context.beanFactory?.getBeanNamesForType(TransportProvider::class.java, true, false).orEmpty()
            return of(context.environment, PortProviders.load(context.classLoader), beans.isNotEmpty())
        }

        /** The selection of [environment], with [providers] on the classpath and [beans] of the application's. */
        internal fun of(environment: Environment, providers: PortProviders, beans: Boolean): TransportSelection {
            val properties = WowProperties.of(environment)
            return when (properties.transport.trim().lowercase()) {
                MEMORY_TYPE -> MEMORY
                AUTO, "" -> when {
                    beans || configured(environment, providers).isNotEmpty() -> PROVIDER
                    DevelopmentMode.of(properties, environment).active -> MEMORY
                    else -> NONE
                }

                else -> PROVIDER
            }
        }

        /** The types of the transports of [providers] that [environment] configures. */
        internal fun configured(environment: Environment, providers: PortProviders): List<String> =
            providers.transports.values.filter { provider ->
                provider.available(
                    TransportConfig.of(
                        provider.type,
                        provider.type,
                        PortProviders.optionsOf(environment, provider.type)
                    )
                )
            }.map { it.type }
    }
}

/**
 * What needs a topic transport in a node without one ([TransportSelection.NONE]), in words: the streams its contexts
 * publish (each to its topic), its aggregate types routed through Kafka (`wow.aggregates.[..].routing=kafka`), its
 * failure ledger published to the ops context (`wow.ops.publish.enabled`), and the command intake
 * (`wow.kafka.command-intake.enabled`). A sourced subscription or a saga's receipts need it too, which the node itself
 * refuses when it is built.
 */
@InternalWowApi
object TransportNeeds {
    private val logger = KotlinLogging.logger {}

    /** What needs a transport in a node of [manifest] serving [contexts], with the settings given; empty for none. */
    fun of(
        manifest: Manifest,
        contexts: Set<String>,
        kafkaRouted: Set<AggregateType>,
        ledger: Boolean,
        intake: Boolean,
    ): List<String> = buildList {
        val published = manifest.contexts.filter { it.name in contexts }
            .flatMap { context -> context.published.map { "${context.name}.${it.aggregate}" } }
        if (published.isNotEmpty()) add("it publishes the streams $published")
        if (kafkaRouted.isNotEmpty()) add("it routes ${kafkaRouted.joinToString()} through Kafka")
        if (ledger) add("it publishes its failure ledger (wow.ops.publish.enabled)")
        if (intake) add("it takes commands from its command topics (wow.kafka.command-intake.enabled)")
    }

    /** Refuses a node without a transport whose [needs] are not empty; logs that it needs none otherwise. */
    fun check(needs: List<String>, transport: String) {
        if (needs.isEmpty()) {
            logger.info { "This service needs no topic transport: it publishes no stream and takes no topic." }
            return
        }
        throw WowConfigurationException(
            "wow.transport",
            transport,
            "The service needs a topic transport, but none is configured: ${needs.joinToString("; ")}.",
            "Configure Kafka (wow.kafka.bootstrap-servers) or another transport provider, or drop what needs it. " +
                "A node in development keeps its topics in its JVM instead (wow.dev-profiles).",
        )
    }
}

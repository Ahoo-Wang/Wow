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
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.sink.StreamSinks
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/**
 * The Sinks of the streams the service publishes (decision D132, finding R5.B-5): those the [me.ahoo.wow.spi.sink]
 * providers on the classpath or among the application's beans find configured ([PortProviders]), the ClickHouse Sink by
 * `wow.clickhouse.endpoint` (unless `wow.clickhouse.sink.enabled=false`). Each is one subscription per published
 * stream, named `<processor>.<aggregate>`, which the node takes with its subscription factories ([ProvidedSinks]).
 * Wow's ClickHouse Sink also reads each aggregate type's `wow.aggregates.[<context>.<aggregate>].clickhouse.history.*`.
 */
@AutoConfiguration(after = [TransportAutoConfiguration::class, ClickHouseAutoConfiguration::class])
@EnableConfigurationProperties(WowProperties::class)
class SinkAutoConfiguration {
    @Bean(destroyMethod = "close")
    internal fun wowProvidedSinks(
        providers: PortProviders,
        environment: Environment,
        beanFactory: ListableBeanFactory,
        manifest: Manifest,
        properties: ObjectProvider<WowProperties>,
    ): ProvidedSinks {
        val wow = properties.ifAvailable
        val configs = providers.sinkConfigs(environment, BeanStoreResources(beanFactory)) { type ->
            mapOf(DDL to (wow?.ddl ?: Ddl.AUTO).name.lowercase()) + aggregateOptions(type, wow?.aggregates.orEmpty())
        }
        val published = PublishedSinks.publishedOf(manifest)
        return ProvidedSinks(
            configs.map { (provider, config) ->
                logger.info { "The node writes its published streams to the Sink ${provider.type} (${config.key})." }
                val sinks = PortProviders.configured(config.key) { provider.open(config) }
                sinks to PublishedSinks(published, sinks.processor) { context, stream ->
                    sinks.sinkOf(context, stream.aggregate)
                }
            },
        )
    }

    internal companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * The per-aggregate settings of the Sinks of [type] under `wow.aggregates.[<context>.<aggregate>].<type>.*`, as
         * options `aggregates.<context>.<aggregate>.*`: the ClickHouse Sink's history today.
         */
        fun aggregateOptions(type: String, aggregates: Map<String, WowProperties.Aggregate>): Map<String, String> =
            if (type != CLICKHOUSE) {
                emptyMap()
            } else {
                buildMap {
                    aggregates.forEach { (key, aggregate) ->
                        val history = aggregate.clickhouse.history
                        history.enabled?.let { put("aggregates.$key.history.enabled", it.toString()) }
                        history.ttl?.let { put("aggregates.$key.history.ttl", it.toString()) }
                    }
                }
            }

        private const val CLICKHOUSE = "clickhouse"

        /** The option of a Sink's DDL, `wow.ddl` unless `wow.<type>.ddl` sets its own. */
        private const val DDL = "ddl"
    }
}

/** The Sinks the node's providers opened, each its subscriptions' factory; closed with the context. */
@InternalWowApi
class ProvidedSinks internal constructor(private val opened: List<Pair<StreamSinks, PublishedSinks>>) : AutoCloseable {
    /** Each Sink's published streams, as the node takes them. */
    val sinks: List<PublishedSinks> get() = opened.map { it.second }

    override fun close() {
        opened.forEach { (sinks, _) ->
            runCatching { sinks.close() }.onFailure { logger.warn(it) { "Closing $sinks failed." } }
        }
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}

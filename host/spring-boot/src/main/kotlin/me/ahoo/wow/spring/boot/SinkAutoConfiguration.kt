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
import me.ahoo.wow.manifest.PublishedManifest
import me.ahoo.wow.runtime.sink.PublishedSinks
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.sink.SinkConfig
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.sink.StreamSinks
import me.ahoo.wow.spi.store.StoreResources
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/**
 * The Sinks of the streams the service publishes: those the [me.ahoo.wow.spi.sink]
 * providers on the classpath or among the application's beans find configured ([PortProviders]), the ClickHouse Sink by
 * `wow.clickhouse.endpoint` (unless `wow.clickhouse.sink.enabled=false`). Each is one subscription per published
 * stream, named `<processor>.<aggregate>`, which the node takes with its subscription factories ([ProvidedSinks]).
 *
 * A Sink that writes into one of the node's stores starts by itself, with no key of its own: for each store
 * that an aggregate type's `wow.aggregates.[<context>.<aggregate>].query.events.store` names, a provider of the
 * store's type (Wow's Elasticsearch event search Sink) writes exactly those types' streams into it, so that store
 * serves their `events` queries ([storeSinkConfigs]).
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
        backends: ObjectProvider<HostBackends>,
    ): ProvidedSinks {
        val wow = properties.ifAvailable
        val resources = BeanStoreResources(beanFactory)
        val configs = providers.sinkConfigs(environment, resources) { type ->
            mapOf(DDL to (wow?.ddl ?: Ddl.AUTO).name.lowercase()) + aggregateOptions(type, wow?.aggregates.orEmpty())
        } + storeSinkConfigs(providers, wow, backends.ifAvailable, resources)
        val published = PublishedSinks.publishedOf(manifest)
        return ProvidedSinks(
            configs.map { (provider, config) ->
                val streams = config.streams?.let(::streamsOf) ?: published
                logger.info {
                    "The node writes ${config.streams ?: "its published streams"} to the Sink ${provider.type} " +
                        "(${config.key})."
                }
                val sinks = PortProviders.configured(config.key) { provider.open(config) }
                sinks to PublishedSinks(streams, sinks.processor) { context, stream ->
                    sinks.sinkOf(context, stream.aggregate)
                }
            },
        )
    }

    internal companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * The Sinks that write into a store of the node: for each store of [backends] that an aggregate type's
         * `query.events.store` names ([HostEventStores]) and whose type a Sink provider of [providers] has, one Sink of
         * exactly those types, over the opened store; a provider that finds it unavailable opens none.
         */
        fun storeSinkConfigs(
            providers: PortProviders,
            wow: WowProperties?,
            backends: HostBackends?,
            resources: StoreResources,
        ): List<Pair<SinkProvider, SinkConfig>> {
            backends ?: return emptyList()
            return HostEventStores.configured(wow, backends.stores.keys).entries
                .groupBy({ it.value }, { it.key })
                .mapNotNull { (name, types) ->
                    val store = backends.stores.getValue(name)
                    val provider = providers.sinks[store.type] ?: return@mapNotNull null
                    val config = SinkConfig.of("stores.$name", store.type, emptyMap(), resources, types.toSet(), store)
                    (provider to config).takeIf { PortProviders.configured(config.key) { provider.available(config) } }
                }
        }

        /** [types] as the streams of a Sink, by context: each with its events alone, the state not needed. */
        fun streamsOf(types: Set<AggregateType>): Map<String, List<PublishedManifest>> =
            types.groupBy { it.context }.mapValues { (context, ofContext) ->
                ofContext.map { PublishedManifest(it.name, PublishedManifest.defaultTopic(context, it.name), false) }
            }

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

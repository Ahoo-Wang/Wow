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

import com.clickhouse.client.api.Client
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.clickhouse.query.ClickHouseDatasetOptions
import me.ahoo.wow.clickhouse.query.ClickHouseDatasetQueryBackendFactory
import me.ahoo.wow.clickhouse.sink.AggregateSinkOptions
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkOptions
import me.ahoo.wow.clickhouse.sink.ClickHouseSinks
import me.ahoo.wow.clickhouse.sink.DdlMode
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.dataset.DatasetQueryBackendFactory
import me.ahoo.wow.runtime.sink.PublishedSinks
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.toKotlinDuration

/**
 * The ClickHouse Sink and dataset backend (wow-clickhouse, decisions D142, D145, D164), active when the application
 * has wow-clickhouse on its classpath and sets `wow.clickhouse.endpoint` ([ClickHouseProperties]).
 *
 * - **Client**: a client-v2 [Client] of the endpoint and credentials, unless the application has one of its own;
 *   closed with the context.
 * - **Sink** (unless `wow.clickhouse.sink.enabled=false`): one [ClickHouseSinks] sink per stream the Manifest
 *   publishes (`ContextManifest.published`), as subscriptions named `clickhouse-sink.<aggregate>`: a
 *   [PublishedSinks] bean, which the node takes with every subscription factory. Each sink creates or
 *   validates its tables before its first write, as `wow.clickhouse.sink.ddl` says.
 * - **Datasets** (unless `wow.clickhouse.query.enabled=false` or the application has a [DatasetQueryBackendFactory]
 *   of its own): a [ClickHouseDatasetQueryBackendFactory] over the same client, a dataset's bare `table` in
 *   `wow.clickhouse.database`, where the Sink writes. The query plane ([QueryAutoConfiguration]) serves the datasets
 *   of the dataset files over it, such as a `<context>_<aggregate>_state_flat` view the Sink keeps.
 */
@AutoConfiguration
@ConditionalOnClass(name = [CLICKHOUSE_MODULE])
@EnableConfigurationProperties(ClickHouseProperties::class)
class ClickHouseAutoConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = ClickHouseProperties.PREFIX, name = ["endpoint"])
    class ClickHouseEndpoint {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(Client::class)
        fun wowClickHouseClient(properties: ClickHouseProperties): Client = Client.Builder()
            .addEndpoint(checkNotNull(properties.endpoint) { "wow.clickhouse.endpoint is not set." })
            .setUsername(properties.username)
            .setPassword(properties.password)
            .build()

        @Bean(CLICKHOUSE_SINKS)
        @ConditionalOnProperty(
            prefix = "${ClickHouseProperties.PREFIX}.sink",
            name = ["enabled"],
            matchIfMissing = true
        )
        fun wowClickHouseSinks(
            manifest: Manifest,
            client: Client,
            properties: ClickHouseProperties,
            wow: ObjectProvider<WowProperties>,
        ): PublishedSinks {
            val sinks = ClickHouseSinks(client, manifest, options(properties, wow.ifAvailable?.aggregates.orEmpty()))
            return PublishedSinks(PublishedSinks.publishedOf(manifest), ClickHouseSinks.PROCESSOR) { context, stream ->
                sinks.sinkOf(context, stream.aggregate)
            }
        }

        @Bean
        @ConditionalOnMissingBean(DatasetQueryBackendFactory::class)
        @ConditionalOnProperty(
            prefix = "${ClickHouseProperties.PREFIX}.query",
            name = ["enabled"],
            matchIfMissing = true
        )
        fun wowClickHouseDatasets(
            client: Client,
            properties: ClickHouseProperties
        ): ClickHouseDatasetQueryBackendFactory =
            ClickHouseDatasetQueryBackendFactory(client, datasetOptions(properties))
    }

    companion object {
        /** The name of the ClickHouse Sink's [PublishedSinks] bean. */
        const val CLICKHOUSE_SINKS: String = "wowClickHouseSinks"

        /** The dataset backend's options from [properties]. */
        fun datasetOptions(properties: ClickHouseProperties): ClickHouseDatasetOptions = ClickHouseDatasetOptions(
            database = properties.database,
            metadataTtl = properties.query.metadataTtl,
            settings = properties.query.settings.toMap(),
        )

        /** The Sink's options from [properties]. */
        fun options(properties: ClickHouseProperties): ClickHouseSinkOptions = options(properties, emptyMap())

        /**
         * The Sink's options from [properties], each aggregate type's history settings from [aggregates]
         * (`wow.aggregates.[<context>.<aggregate>].clickhouse.history.*`, the one style of per-aggregate keys, review
         * A5-10) over `wow.clickhouse.sink.aggregates.<context>.<aggregate>.history.*`, which is still read.
         */
        fun options(
            properties: ClickHouseProperties,
            aggregates: Map<String, WowProperties.Aggregate>
        ): ClickHouseSinkOptions {
            val sink = properties.sink
            val ddl = DdlMode.entries.firstOrNull { it.name.equals(sink.ddl, ignoreCase = true) }
            requireNotNull(ddl) { "wow.clickhouse.sink.ddl is auto, validate or none, not ${sink.ddl}." }
            return ClickHouseSinkOptions(
                database = properties.database,
                ddl = ddl,
                batchSize = sink.batchSize,
                deduplicationWindow = sink.deduplicationWindow,
                aggregates = sinkAggregates(sink, aggregates),
            )
        }

        private fun sinkAggregates(
            sink: ClickHouseProperties.Sink,
            aggregates: Map<String, WowProperties.Aggregate>,
        ): Map<AggregateType, AggregateSinkOptions> {
            val legacy = sink.aggregates.flatMap { (context, byName) ->
                byName.map { (name, aggregate) -> AggregateType(context, name) to aggregate.history }
            }.toMap()
            val unified = aggregates.mapNotNull { (key, aggregate) ->
                val history = aggregate.clickhouse.history
                if (history.enabled == null && history.ttl == null) {
                    return@mapNotNull null
                }
                val context = key.substringBefore('.', "")
                val name = key.substringAfter('.', "")
                require(context.isNotBlank() && name.isNotBlank()) {
                    "wow.aggregates.[$key] names no aggregate type: <context>.<aggregate>."
                }
                AggregateType(context, name) to history
            }.toMap()
            return (legacy.keys + unified.keys).associateWith { type ->
                val old = legacy[type]
                val new = unified[type]
                AggregateSinkOptions(
                    history = new?.enabled ?: old?.enabled ?: true,
                    historyTtl = (new?.ttl ?: old?.ttl)?.toKotlinDuration(),
                )
            }
        }
    }
}

/** A class of wow-clickhouse, present only when the application has the module. */
internal const val CLICKHOUSE_MODULE = "me.ahoo.wow.clickhouse.ClickHouseModule"

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

package me.ahoo.wow.clickhouse.sink

import com.clickhouse.client.api.Client
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.clickhouse.ClickHouseClients
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.sink.SinkConfig
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.sink.StreamSinks
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The ClickHouse Sink, type `clickhouse`: switched on by `sink.enabled: true` alone, since writing every event to a
 * warehouse is a cost a service chooses; neither a client nor an endpoint switches it on. It writes every stream the
 * service's Manifest (a resource of the application) publishes, through the application's [Client] when it has one,
 * which it never closes, else through one of `endpoint` and the credentials, which it closes with the sinks
 * ([ClickHouseClients.clientOf]); with neither, it fails the start naming both. Its settings, relative to its configuration (`wow.clickhouse.*`), are its [options]: the
 * connection's, `ddl` ([Ddl], `auto` by default; the host gives `wow.ddl` when it is not set), `sink.batch-size`,
 * `sink.deduplication-window`, and each aggregate type's `history.enabled` and `history.ttl` under
 * `aggregates.<context>.<aggregate>`, which the host gives from
 * `wow.aggregates.[<context>.<aggregate>].clickhouse.history.*` ([ClickHouseSinkOptions]).
 */
class ClickHouseSinkProvider : SinkProvider {
    override val type: String = TYPE

    override val requirement: String = "sink.enabled: true"

    override val options: List<ProviderOption<*>> = listOf(
        ClickHouseClients.ENDPOINT,
        ClickHouseClients.USERNAME,
        ClickHouseClients.PASSWORD,
        ClickHouseClients.DATABASE,
        DDL,
        ENABLED,
        BATCH_SIZE,
        DEDUPLICATION_WINDOW,
        AGGREGATES,
    )

    override fun available(config: SinkConfig): Boolean = config.option(ENABLED)

    override fun open(config: SinkConfig): StreamSinks {
        val manifest = config.resources.resource(Manifest::class.java, null, config.key)
        val client = ClickHouseClients.clientOf(config.resources, config.key, "The ClickHouse Sink", config::option)
        val sinks = ClickHouseSinks(client.client, manifest, options(config))
        return object : StreamSinks {
            override val processor: String = ClickHouseSinks.PROCESSOR

            override fun sinkOf(context: String, aggregate: String): Sink = sinks.sinkOf(context, aggregate)

            override fun close() {
                client.close()
            }

            override fun toString(): String = "ClickHouseSinks(${options(config).database})"
        }
    }

    companion object {
        /** The type of the ClickHouse Sink. */
        const val TYPE: String = "clickhouse"

        /** How the Sink manages its tables, over `wow.ddl`. */
        @JvmField
        val DDL: ProviderOption<Ddl> = ProviderOption.enumeration(
            "ddl",
            "how the Sink manages its tables: auto creates what is missing, then validates; validate creates nothing " +
                "and refuses a table that is missing or has other columns; none creates and checks nothing. wow.ddl " +
                "when not set.",
            Ddl::class.java,
        )

        /** Whether the published streams are written to ClickHouse: the Sink's one switch, off by default. */
        @JvmField
        val ENABLED: ProviderOption.Defaulted<Boolean> = ProviderOption.boolean(
            "sink.enabled",
            "whether the Manifest's published streams are written to ClickHouse; off unless set, so a ClickHouse " +
                "client or endpoint alone writes nothing.",
            false,
        )

        /** The most records one insert carries. */
        @JvmField
        val BATCH_SIZE: ProviderOption.Defaulted<Int> = ProviderOption.int(
            "sink.batch-size",
            "the most records one insert carries.",
            ClickHouseSinkOptions.DEFAULT_BATCH_SIZE,
        )

        /** How many recent inserts each table remembers to drop a retried one. */
        @JvmField
        val DEDUPLICATION_WINDOW: ProviderOption.Defaulted<Int> = ProviderOption.int(
            "sink.deduplication-window",
            "how many recent inserts each table remembers to drop a retried one: the " +
                "non_replicated_deduplication_window of the tables auto creates.",
            ClickHouseSinkOptions.DEFAULT_DEDUPLICATION_WINDOW,
        )

        /** Each aggregate type's history, which the host gives the Sink; not set under `wow.clickhouse`. */
        @JvmField
        val AGGREGATES: ProviderOption<Map<String, String>> = ProviderOption.map(
            "aggregates",
            "each aggregate type's history settings, <context>.<aggregate>.history.enabled and .ttl, given by the " +
                "host from wow.aggregates.[<context>.<aggregate>].clickhouse.history.",
        ).perStore()

        private const val HISTORY = ".history."

        /** The Sink's `ddl` when neither it nor the host (`wow.ddl`) sets one. */
        private val DEFAULT_DDL: Ddl = Ddl.AUTO

        /** The Sink's options from [config]'s settings. */
        fun options(config: SinkConfig): ClickHouseSinkOptions {
            val ddl = config.option(DDL) ?: DEFAULT_DDL
            return ClickHouseSinkOptions(
                database = config.option(ClickHouseClients.DATABASE),
                ddl = ddl,
                batchSize = config.option(BATCH_SIZE),
                deduplicationWindow = config.option(DEDUPLICATION_WINDOW),
                aggregates = aggregates(config),
            )
        }

        /** Each aggregate type's history settings, `aggregates.<context>.<aggregate>.history.*`. */
        private fun aggregates(config: SinkConfig): Map<AggregateType, AggregateSinkOptions> =
            histories(config, config.options(AGGREGATES)).mapValues { (_, history) ->
                AggregateSinkOptions(
                    history = history["enabled"]?.toBooleanStrict() ?: true,
                    historyTtl = history["ttl"]?.let(::duration),
                )
            }

        /** `<context>.<aggregate>.history.<name>` settings of [settings], by aggregate type, then name. */
        private fun histories(
            config: SinkConfig,
            settings: Map<String, String>,
        ): Map<AggregateType, Map<String, String>> = settings.entries
            .filter { it.key.contains(HISTORY) && it.value.isNotBlank() }
            .groupBy({ it.key.substringBeforeLast(HISTORY) }, { it.key.substringAfterLast(HISTORY) to it.value })
            .mapKeys { (key, _) ->
                val context = key.substringBefore('.', "")
                val aggregate = key.substringAfter('.', "")
                require(context.isNotBlank() && aggregate.isNotBlank()) {
                    "${config.key}.${AGGREGATES.key}.$key names no aggregate type: name it <context>.<aggregate>."
                }
                AggregateType(context, aggregate)
            }
            .mapValues { (_, values) -> values.toMap() }

        /** A duration setting: `7d`, `10s`, ISO-8601 (`P7D`), or a number of milliseconds. */
        private fun duration(text: String): Duration = text.trim().toLongOrNull()?.milliseconds ?: Duration.parse(text)
    }
}

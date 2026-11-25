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
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.clickhouse.ClickHouseClients
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.policy.GovernedAggregates
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.config.configCheck
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
 * `sink.insert-interval`, `sink.deduplication-window`, and each aggregate type's `history.enabled` and `history.ttl` under
 * `aggregates.<context>.<aggregate>`, which the host gives from
 * `wow.aggregates.[<context>.<aggregate>].clickhouse.history.*` ([ClickHouseSinkOptions]).
 *
 * Incubating, as all of wow-clickhouse: it may change without a deprecation cycle until the dataset and analytics
 * modules are released stable.
 */
@ExperimentalWowApi
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
        INSERT_INTERVAL,
        DEDUPLICATION_WINDOW,
        AGGREGATES,
    )

    override fun available(config: SinkConfig): Boolean = config.settings.option(ENABLED)

    override fun open(config: SinkConfig): StreamSinks {
        val manifest = config.resources.resource(Manifest::class.java, null, config.key)
        val client =
            ClickHouseClients.clientOf(config.resources, config.key, "The ClickHouse Sink", config.settings::option)
        // Which aggregates an access policy governs, as the host's query engine says: recorded in wow_catalog.
        val governed = GovernedAggregates::class.java.takeIf(config.resources::has)
            ?.let { config.resources.resource(it, null, config.key) }
        val sinks = ClickHouseSinks(ClientStatements(client.client), manifest, options(config), governed)
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
            "how the Sink manages its tables: auto creates what is missing, _state's computed columns included, " +
                "then validates; validate creates nothing and refuses a table that is missing, is not of the Sink's " +
                "format (its comment's wow-sink-format:1), has other columns or lacks a computed column; none " +
                "creates and checks nothing. wow.ddl when not set.",
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

        /** The least time between the starts of two inserts of one aggregate type's Sink on a node. */
        @JvmField
        val INSERT_INTERVAL: ProviderOption.Defaulted<Duration> = ProviderOption.duration(
            "sink.insert-interval",
            "the least time between the starts of two inserts of one aggregate type's Sink on a node, unless " +
                "batch-size records are waiting; the commits arriving meanwhile join the next insert. At most half " +
                "the subscription's handler timeout; 0 inserts as often as one returns.",
            ClickHouseSinkOptions.DEFAULT_INSERT_INTERVAL,
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
        ).perInstance()

        private const val HISTORY = ".history."

        /** The Sink's `ddl` when neither it nor the host (`wow.ddl`) sets one. */
        private val DEFAULT_DDL: Ddl = Ddl.AUTO

        /** The Sink's options from [config]'s settings. */
        fun options(config: SinkConfig): ClickHouseSinkOptions {
            val ddl = config.settings.option(DDL) ?: DEFAULT_DDL
            return ClickHouseSinkOptions(
                database = config.settings.option(ClickHouseClients.DATABASE),
                ddl = ddl,
                batchSize = atLeast(config, BATCH_SIZE, 1),
                insertInterval = config.settings.option(INSERT_INTERVAL).also { interval ->
                    configCheck(
                        !interval.isNegative(),
                        "${config.key}.${INSERT_INTERVAL.key}",
                        interval,
                        "Set it to 0 or more: 0 inserts as often as one returns.",
                    ) { "The insert interval of the ClickHouse Sink cannot be $interval." }
                },
                deduplicationWindow = atLeast(config, DEDUPLICATION_WINDOW, 1),
                aggregates = aggregates(config),
            )
        }

        /** [option] of [config], refused naming its key when it is below [least]. */
        private fun atLeast(config: SinkConfig, option: ProviderOption.Defaulted<Int>, least: Int): Int =
            config.settings.option(option).also { value ->
                configCheck(value >= least, "${config.key}.${option.key}", value, "Set it to $least or more.") {
                    "The ${option.key} of the ClickHouse Sink cannot be $value."
                }
            }

        /** Each aggregate type's history settings, `aggregates.<context>.<aggregate>.history.*`. */
        private fun aggregates(config: SinkConfig): Map<AggregateType, AggregateSinkOptions> =
            histories(config, config.settings.options(AGGREGATES)).mapValues { (_, history) ->
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

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
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.sink.SinkConfig
import me.ahoo.wow.spi.sink.SinkProvider
import me.ahoo.wow.spi.sink.StreamSinks
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The ClickHouse Sink, type `clickhouse`: available once `endpoint` is set,
 * unless `sink.enabled` is `false`. It writes every stream the service's Manifest (a resource of the application)
 * publishes, through the application's ClickHouse [Client] when it has one, else through one of `endpoint`,
 * `username` (`default`) and `password` (empty) it closes with the sinks. Its settings, relative to its
 * configuration (`wow.clickhouse.*`): `database`, `ddl` ([Ddl], `auto` by default; the host gives `wow.ddl` when it
 * is not set), `sink.batch-size`, `sink.deduplication-window`, and each aggregate type's `history.enabled` and
 * `history.ttl`, under `aggregates.<context>.<aggregate>` ([ClickHouseSinkOptions]).
 */
class ClickHouseSinkProvider : SinkProvider {
    override val type: String = TYPE

    override val requirement: String = "endpoint, the ClickHouse HTTP endpoint"

    override fun available(config: SinkConfig): Boolean =
        config.option(ENDPOINT) != null && config.option("sink.enabled")?.toBooleanStrict() != false

    override fun open(config: SinkConfig): StreamSinks {
        val manifest = config.resources.resource(Manifest::class.java, null, config.key)
        val owned = if (config.resources.has(Client::class.java)) null else clientOf(config)
        val client = owned ?: config.resources.resource(Client::class.java, null, "${config.key}.$ENDPOINT")
        val sinks = ClickHouseSinks(client, manifest, options(config))
        return object : StreamSinks {
            override val processor: String = ClickHouseSinks.PROCESSOR

            override fun sinkOf(context: String, aggregate: String): Sink = sinks.sinkOf(context, aggregate)

            override fun close() {
                owned?.close()
            }

            override fun toString(): String = "ClickHouseSinks(${options(config).database})"
        }
    }

    companion object {
        /** The type of the ClickHouse Sink. */
        const val TYPE: String = "clickhouse"

        private const val ENDPOINT = "endpoint"
        private const val HISTORY = ".history."
        private const val AGGREGATES = "aggregates"

        /** A client of [config]'s endpoint and credentials. */
        fun clientOf(config: SinkConfig): Client = Client.Builder()
            .addEndpoint(requireNotNull(config.option(ENDPOINT)) { "${config.key}.$ENDPOINT is not set." })
            .setUsername(config.option("username") ?: "default")
            .setPassword(config.options["password"].orEmpty())
            .build()

        /** The Sink's options from [config]'s settings. */
        fun options(config: SinkConfig): ClickHouseSinkOptions {
            val ddlText = config.option("ddl") ?: "auto"
            val ddl = requireNotNull(Ddl.entries.firstOrNull { it.name.equals(ddlText, ignoreCase = true) }) {
                "${config.key}.ddl is $ddlText, which is no DDL mode: set it to auto, validate or none."
            }
            return ClickHouseSinkOptions(
                database = config.option("database") ?: ClickHouseSinkOptions.DEFAULT_DATABASE,
                ddl = ddl,
                batchSize = config.option("sink.batch-size")?.toInt() ?: ClickHouseSinkOptions.DEFAULT_BATCH_SIZE,
                deduplicationWindow = config.option("sink.deduplication-window")?.toInt()
                    ?: ClickHouseSinkOptions.DEFAULT_DEDUPLICATION_WINDOW,
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
                    "${config.key}.$AGGREGATES.$key names no aggregate type: name it <context>.<aggregate>."
                }
                AggregateType(context, aggregate)
            }
            .mapValues { (_, values) -> values.toMap() }

        /** A duration setting: `7d`, `10s`, ISO-8601 (`P7D`), or a number of milliseconds. */
        private fun duration(text: String): Duration = text.trim().toLongOrNull()?.milliseconds ?: Duration.parse(text)
    }
}

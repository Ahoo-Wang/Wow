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

/**
 * The ClickHouse Sinks of a service (decisions D142, D164): [sinkOf] gives the [ClickHouseSink] of an aggregate the
 * [manifest] declares, its tables in [options]' database, its flat view's columns from the aggregate's state schema
 * in the Manifest's `$defs` ([FlatColumns]).
 *
 * The runtime assembles them as the published streams' sinks, `PublishedSinks(published, PROCESSOR) { context,
 * stream -> sinks.sinkOf(context, stream.aggregate) }`, so each is a sink subscription named
 * `clickhouse-sink.<aggregate>`.
 */
class ClickHouseSinks internal constructor(
    private val statements: ClickHouseStatements,
    private val manifest: Manifest,
    val options: ClickHouseSinkOptions = ClickHouseSinkOptions(),
) {
    /** The Sinks over a client-v2 [client]; the client is the caller's to close. */
    constructor(client: Client, manifest: Manifest, options: ClickHouseSinkOptions = ClickHouseSinkOptions()) :
        this(ClientStatements(client), manifest, options)

    /** The Sink of [aggregate] of [context]. */
    fun sinkOf(context: String, aggregate: String): ClickHouseSink =
        ClickHouseSink(schemaOf(AggregateType(context, aggregate)), statements, options.ddl, options.batchSize)

    /** The tables and views of [type]. */
    fun schemaOf(type: AggregateType): ClickHouseSinkSchema {
        val aggregate = manifest.contexts.firstOrNull { it.name == type.context }
            ?.aggregates?.firstOrNull { it.name == type.name }
        requireNotNull(aggregate) { "The Manifest declares no aggregate $type." }
        @Suppress("UNCHECKED_CAST")
        val schema = manifest.defs[Manifest.schemaKey(aggregate.state.schema)] as? Map<String, Any?>
        return ClickHouseSinkSchema(
            type = type,
            database = options.database,
            aggregate = options.aggregate(type),
            flatColumns = FlatColumns.of(schema, FLAT_RESERVED),
            deduplicationWindow = options.deduplicationWindow,
        )
    }

    override fun toString(): String = "ClickHouseSinks(${options.database})"

    companion object {
        /** The processor name of the ClickHouse Sink's subscriptions: `clickhouse-sink.<aggregate>`. */
        const val PROCESSOR: String = "clickhouse-sink"

        private val FLAT_RESERVED: Set<String> = ClickHouseSinkSchema.FLAT_SYSTEM_COLUMNS.mapTo(HashSet()) { it.name }
    }
}

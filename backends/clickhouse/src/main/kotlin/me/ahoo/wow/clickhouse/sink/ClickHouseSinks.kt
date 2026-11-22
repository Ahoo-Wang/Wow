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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.policy.GovernedAggregates

/**
 * The ClickHouse Sinks of a service: [sinkOf] gives the [ClickHouseSink] of an aggregate the
 * [manifest] declares, its tables in [options]' database, its flat view's columns from the aggregate's state schema
 * in the Manifest's `$defs` ([FlatColumns]).
 *
 * The runtime assembles them as the published streams' sinks, `PublishedSinks(published, PROCESSOR, masks = null)
 * { context, stream -> sinks.sinkOf(context, stream.aggregate) }`, so each is a sink subscription named
 * `clickhouse-sink.<aggregate>`, writing the values as committed: a read of the tables masks what it returns.
 */
@InternalWowApi
class ClickHouseSinks internal constructor(
    /** The statements the Sinks run: over a client-v2 client, [ClientStatements], which stays the caller's to close. */
    private val statements: ClickHouseStatements,
    manifest: Manifest,
    val options: ClickHouseSinkOptions = ClickHouseSinkOptions(),
    /** Which aggregates an access policy governs, which `wow_catalog` records; null: unknown, empty in each row. */
    private val governed: GovernedAggregates? = null,
) {
    private val tables = SinkTables(manifest, options)

    init {
        val collisions = ClickHouseSinkSchema.collisionsOf(tables.types)
        check(collisions.isEmpty()) {
            "The ClickHouse Sink would write several aggregates of the Manifest into the same tables: " +
                collisions.entries.joinToString("; ") { (name, shared) -> "$name of ${shared.joinToString(" and ")}" } +
                ". Table names are the aggregate's `<context>_<aggregate>` in lower case, other characters than " +
                "[a-z0-9_] as `_`, and are part of the storage format: rename one of the aggregates."
        }
    }

    /** The Sink of [aggregate] of [context]. */
    fun sinkOf(context: String, aggregate: String): ClickHouseSink {
        val type = AggregateType(context, aggregate)
        val schema = schemaOf(type)
        // The entries are asked for when the Sink describes its tables: whether a policy governs the aggregate is the
        // query engine's to say, which the node assembling its sinks has not built yet.
        return ClickHouseSink(
            schema,
            statements,
            options.ddl,
            options.batchSize,
            options.insertInterval,
            catalog = { catalogOf(schema) },
        )
    }

    /** The `wow_catalog` entries of [type]'s tables. */
    fun catalogOf(type: AggregateType): List<CatalogEntry> = catalogOf(schemaOf(type))

    private fun catalogOf(schema: ClickHouseSinkSchema): List<CatalogEntry> =
        tables.catalogOf(schema, accessOf(schema.type))

    /** [type]'s [CatalogEntry.access]: whether its policy decides its rows, empty when [governed] is unknown. */
    private fun accessOf(type: AggregateType): String = when {
        governed == null -> ""
        governed.governs(type) -> ClickHouseCatalog.ACCESS_POLICY
        else -> ClickHouseCatalog.ACCESS_DEFAULT
    }

    /** The tables and views of [type]. */
    fun schemaOf(type: AggregateType): ClickHouseSinkSchema = tables.schemaOf(type)

    override fun toString(): String = "ClickHouseSinks(${options.database})"

    @InternalWowApi
    companion object {
        /** The processor name of the ClickHouse Sink's subscriptions: `wow.clickhouse-sink.<aggregate>`. */
        const val PROCESSOR: String = "wow.clickhouse-sink"
    }
}

/**
 * The tables the ClickHouse Sink writes for the aggregates of [manifest] in [options]' database, read without a
 * client: each one's [schemaOf] (names, flat columns) and the `wow_catalog` entries of its tables ([catalogOf]). The
 * Sink writes by them; the dataset backend tells by them what a Sink's table holds (`ClickHouseDatasetSources`).
 */
internal class SinkTables(private val manifest: Manifest, private val options: ClickHouseSinkOptions) {
    /** The aggregate types of [manifest]. */
    val types: List<AggregateType> = manifest.contexts.flatMap { context ->
        context.aggregates.map { AggregateType(context.name, it.name) }
    }

    /** The `wow_catalog` entries of [schema]'s tables, their rows read as [access] says ([CatalogEntry.access]). */
    fun catalogOf(schema: ClickHouseSinkSchema, access: String = ""): List<CatalogEntry> =
        CatalogEntries.of(manifest, aggregateOf(schema.type), schema, access)

    fun aggregateOf(type: AggregateType): AggregateManifest {
        val aggregate = manifest.contexts.firstOrNull { it.name == type.context }
            ?.aggregates?.firstOrNull { it.name == type.name }
        return requireNotNull(aggregate) { "The Manifest declares no aggregate $type." }
    }

    /** The tables and views of [type]. */
    fun schemaOf(type: AggregateType): ClickHouseSinkSchema {
        val aggregate = aggregateOf(type)

        @Suppress("UNCHECKED_CAST")
        val schema = manifest.defs[Manifest.schemaKey(aggregate.state.schema)] as? Map<String, Any?>
        val access = AccessColumns.of(aggregate.access)
        // A flat column takes no name `_state` has already: a stored column's or an access column's.
        val reserved = STORED_NAMES + access.map { it.name }
        return ClickHouseSinkSchema(
            type = type,
            database = options.database,
            aggregate = options.aggregate(type),
            flatColumns = FlatColumns.of(schema, reserved),
            deduplicationWindow = options.deduplicationWindow,
            accessColumns = access,
        )
    }

    private companion object {
        val STORED_NAMES: Set<String> = ClickHouseSinkSchema.STATE_COLUMNS.mapTo(HashSet()) { it.name }
    }
}

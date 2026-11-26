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

package me.ahoo.wow.clickhouse.query

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.clickhouse.sink.CatalogEntries
import me.ahoo.wow.clickhouse.sink.CatalogEntry
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkOptions
import me.ahoo.wow.clickhouse.sink.ClickHouseSinkSchema
import me.ahoo.wow.clickhouse.sink.SinkTables
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetSource
import me.ahoo.wow.query.dataset.QueryDatasetSources
import me.ahoo.wow.query.schema.QueryMask
import me.ahoo.wow.spi.json.WowJson
import java.util.concurrent.ConcurrentHashMap

/**
 * What the tables the ClickHouse Sink writes for the aggregates of [manifest] in [database] hold, as the Sink describes
 * them in `wow_catalog`: the same function ([CatalogEntries]) over the same Manifest, so a dataset over one of them
 * inherits the protection its state declares (`@Sensitive`), never a second copy of it.
 *
 * | Table | Column | Its source |
 * | --- | --- | --- |
 * | `_state_flat` | a flat column | the state's top-level property, masked as the state masks it |
 * | `_state` | a flat column (computed on it) | the same |
 * | `_state`, `_state_events` | `state`, `state_numbers` | the state's document, whole: protected paths in it |
 * | `_state_events` | `events` | each event's document, whole |
 * | `_events` | `body` | the event's document, whole |
 *
 * A column of a whole document is [QueryDatasetSource.Opaque] when its source protects a path in it, as is
 * `state_numbers` when the state protects a top-level value. Another table, or a table of another database, is
 * unknown (null), as is a table name two aggregates of the Manifest share.
 */
@InternalWowApi
class ClickHouseDatasetSources(manifest: Manifest, private val database: String) : QueryDatasetSources {
    private val tables: Map<String, QueryDatasetSource> = SinkTables(manifest, ClickHouseSinkOptions(database))
        .let { sink -> sink.types.flatMap { sourcesOf(sink, it) } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size == 1 }
        .mapValues { it.value.single() }

    override fun sourceOf(dataset: QueryDataset): QueryDatasetSource? {
        val storage = ClickHouseDatasetStorage.of(dataset, database)
        return if (storage.database == database) tables[storage.table] else null
    }

    private fun sourcesOf(sink: SinkTables, type: AggregateType): List<Pair<String, QueryDatasetSource>> {
        val schema = sink.schemaOf(type)
        val entries = sink.catalogOf(schema).associateBy { it.role }
        val flat = flatOf(schema, entries[CatalogEntries.STATE_FLAT])
        fun source(table: String, columns: Map<String, QueryDatasetSource.Column>) =
            table to QueryDatasetSource("$database.$table, the ClickHouse Sink's table of $type", columns)
        return listOfNotNull(
            source(schema.stateFlat, flat),
            source(schema.state, flat + documentsOf(entries[CatalogEntries.STATE])),
            entries[CatalogEntries.STATE_EVENTS]?.let { source(schema.stateEvents, documentsOf(it)) },
            entries[CatalogEntries.EVENTS]?.let { source(schema.events, documentsOf(it)) },
        )
    }

    /** Each flat column the state protects: its property's value, at its level and mask. */
    private fun flatOf(schema: ClickHouseSinkSchema, entry: CatalogEntry?): Map<String, QueryDatasetSource.Column> =
        entry?.sensitive.orEmpty().associate { mark ->
            val property = schema.flatColumns.first { it.name == mark.path }.property
            mark.path to QueryDatasetSource.Masked(
                "$STATE.$property",
                levelOf(mark.path, mark.level),
                maskOf(mark.mask),
            )
        }

    /** The mask `wow_catalog` records, the state's `x-wow-mask` as written: read as a snapshot read reads it. */
    private fun maskOf(mask: String): QueryMask =
        if (mask.isBlank()) QueryMask.FULL else QueryMask.ofKeyword(WowJson.mapper.readValue(mask, Map::class.java))

    /**
     * Each column of a whole document with protected paths in it, by the path's first segment: `state`, `events`,
     * `body`; and `state_numbers`, which holds the text of the state's top-level numbers, when the state protects a
     * top-level value.
     */
    private fun documentsOf(entry: CatalogEntry?): Map<String, QueryDatasetSource.Column> {
        val paths = entry?.sensitive.orEmpty().map { it.path }.distinct()
        val documents = paths.groupBy { it.substringBefore('.') }
        val topLevel = documents[STATE].orEmpty().filter { it.count { char -> char == '.' } == 1 }
        return (documents + listOfNotNull(topLevel.takeIf { it.isNotEmpty() }?.let { STATE_NUMBERS to it }))
            .mapValues { (_, protected) -> QueryDatasetSource.Opaque(protected) }
    }

    override fun toString(): String = "ClickHouseDatasetSources($database, ${tables.keys})"

    internal companion object {
        const val STATE = "state"
        const val STATE_NUMBERS = "state_numbers"
        private val logger = KotlinLogging.logger {}

        /** The levels this JVM's sources read that this version does not know, each warned of once. */
        internal val unknownLevels: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * The level [path] is marked at, by its wire name. One this version does not know is read as
         * [SensitivityLevel.CONFIDENTIAL], the strongest it enforces, masked and never compared: a level added within
         * 10.x protects no more ([SensitivityLevel]). Warned of once per value.
         */
        fun levelOf(path: String, level: String): SensitivityLevel =
            WireNames.entryOf<SensitivityLevel>(level) ?: SensitivityLevel.CONFIDENTIAL.also {
                if (unknownLevels.add(level)) {
                    logger.warn {
                        "The sensitivity level $level of $path is not one this version of Wow knows: read as " +
                            "confidential, the strongest it enforces, masked and never compared."
                    }
                }
            }
    }
}

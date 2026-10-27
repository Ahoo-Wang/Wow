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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.clickhouse.sink.ClickHouseSql.identifier
import me.ahoo.wow.spi.json.WowJson
import java.security.MessageDigest

/** A sensitive value of a Wow table: its [path], the [event] and [revision] it is of (empty and 0 for none), its [level] and [mask]. */
@InternalWowApi
data class CatalogSensitive(
    val path: String,
    val level: String,
    val mask: String = "",
    val event: String = "",
    val revision: Int = 0,
)

/** A value of a Wow table that refers to an aggregate: its [path], and the aggregate's [context] and [aggregate]. */
@InternalWowApi
data class CatalogReference(val path: String, val context: String, val aggregate: String)

/**
 * What the Sink adds to the warehouse's own metadata of one of its tables, one row of `wow_catalog`
 * ([ClickHouseCatalog]): who the table is of, by the Manifest and never by its name.
 *
 * - [table], [role]: the table and what it is of the aggregate (`state_events`, `state`, `events`, `state_flat`).
 * - [service], [context], [aggregate]: the aggregate's identity; [ownership] its ownership (`OWNER` or
 *   `TENANT`).
 * - [tenantColumn], [ownerColumn], [spaceColumn]: the scope's columns; the owner's is empty for a tenant's resource.
 * - [sensitive]: each sensitive value by its path from the column that holds it (`state.customer.phone`,
 *   `payload.card`, a flat column's name), an event's by its name and revision too.
 * - [references]: each value that refers to an aggregate, by path.
 * - [eventRevisions]: the revision of each event of the aggregate.
 * - [columnsHash]: [ClickHouseCatalog.columnsHash] of the columns the Sink expects the table to have.
 * - [fingerprint]: a digest of all of the above, the key a row replaces an older one of the same content by.
 */
@InternalWowApi
data class CatalogEntry(
    val table: String,
    val role: String,
    val service: String,
    val context: String,
    val aggregate: String,
    val ownership: String,
    val tenantColumn: String,
    val ownerColumn: String,
    val spaceColumn: String,
    val sensitive: List<CatalogSensitive>,
    val references: List<CatalogReference>,
    val eventRevisions: Map<String, Int>,
    val columnsHash: String,
) {
    val fingerprint: String by lazy {
        ClickHouseCatalog.sha256(
            WowJson.mapper.writeValueAsString(
                listOf(
                    table, role, service, context, aggregate, ownership, tenantColumn, ownerColumn, spaceColumn,
                    sensitive.map { listOf(it.path, it.level, it.mask, it.event, it.revision) },
                    references.map { listOf(it.path, it.context, it.aggregate) },
                    eventRevisions.toSortedMap(),
                    columnsHash,
                ),
            ),
        )
    }
}

/**
 * The `wow_catalog` table of a database: the Sink appends one row per table it keeps
 * once it created or validated them, the warehouse's metadata (`system.tables`, `system.columns`) staying the base of
 * the analytics catalog. A `ReplacingMergeTree(written_at)` ordered by `(database, table, fingerprint)`: a row
 * of the same content replaces the one before it, a changed one is appended beside it, so several rows of one table
 * stand side by side during a rolling upgrade, and the catalog takes the one that matches the table's columns.
 *
 * `writer` is `MATERIALIZED currentUser()`: the account that inserted the row, which no insert can set, and which the
 * catalog trusts or not (`wow.analytics.catalog.trusted-writers`). `table_uuid` is the table's `system.tables.uuid`
 * when the row was written, so a table dropped and created again is not described by its predecessor's row.
 *
 * With `ddl=none` the Sink needs only the INSERT privilege on this table; a failed write is logged and never fails the
 * Sink's writes. The Sink, part of the data plane, only appends here: it never calls the catalog.
 */
@InternalWowApi
object ClickHouseCatalog {
    /** The table's name. */
    const val TABLE: String = "wow_catalog"

    /** The format of its rows; raised only by an incompatible change. */
    const val FORMAT: Int = 1

    /** The columns of `wow_catalog`, in order, with their ClickHouse types. */
    val COLUMNS: List<ClickHouseColumn> = listOf(
        ClickHouseColumn("format", "UInt32"),
        ClickHouseColumn("database", "String"),
        ClickHouseColumn("table", "String"),
        ClickHouseColumn("table_uuid", "UUID"),
        ClickHouseColumn("role", "LowCardinality(String)"),
        ClickHouseColumn("service", "String"),
        ClickHouseColumn("context", "String"),
        ClickHouseColumn("aggregate", "String"),
        ClickHouseColumn("ownership", "LowCardinality(String)"),
        ClickHouseColumn("tenant_column", "String"),
        ClickHouseColumn("owner_column", "String"),
        ClickHouseColumn("space_column", "String"),
        ClickHouseColumn(
            "sensitive",
            "Array(Tuple(path String, level String, mask String, event String, revision UInt32))",
        ),
        ClickHouseColumn("references", "Array(Tuple(path String, context String, aggregate String))"),
        ClickHouseColumn("event_revisions", "Map(String, UInt32)"),
        ClickHouseColumn("fingerprint", "String"),
        ClickHouseColumn("columns_hash", "String"),
        ClickHouseColumn("written_at", "DateTime64(3, 'UTC') DEFAULT now64(3)"),
        ClickHouseColumn("writer", "String MATERIALIZED currentUser()"),
    )

    /** The statement that creates [database]'s `wow_catalog` when it is missing. */
    fun ddl(database: String): String =
        "CREATE TABLE IF NOT EXISTS ${identifier(database)}.${identifier(TABLE)}\n(\n" +
            COLUMNS.joinToString(",\n") { "    ${identifier(it.name)} ${it.type}" } +
            "\n)\nENGINE = ReplacingMergeTree(written_at)\nORDER BY (`database`, `table`, fingerprint)"

    /**
     * The digest of [columns] the catalog compares with the table's own: SHA-256, in lower-case
     * hex, of each column's `<name> <type>`, as `system.columns` reads them, in order, one per line.
     */
    fun columnsHash(columns: List<ClickHouseColumn>): String = sha256(
        columns.joinToString("\n") { "${it.name} ${it.type}" }
    )

    /** The `JSONEachRow` row of [entry] of the table [uuid] in [database]; `written_at` and `writer` are the server's. */
    fun rowOf(database: String, entry: CatalogEntry, uuid: String): String = WowJson.mapper.writeValueAsString(
        WowJson.mapper.createObjectNode().apply {
            put("format", FORMAT)
            put("database", database)
            put("table", entry.table)
            put("table_uuid", uuid)
            put("role", entry.role)
            put("service", entry.service)
            put("context", entry.context)
            put("aggregate", entry.aggregate)
            put("ownership", entry.ownership)
            put("tenant_column", entry.tenantColumn)
            put("owner_column", entry.ownerColumn)
            put("space_column", entry.spaceColumn)
            putArray("sensitive").apply {
                entry.sensitive.forEach {
                    addObject().put("path", it.path).put("level", it.level).put("mask", it.mask)
                        .put("event", it.event).put("revision", it.revision)
                }
            }
            putArray("references").apply {
                entry.references.forEach {
                    addObject().put("path", it.path).put("context", it.context).put("aggregate", it.aggregate)
                }
            }
            putObject(
                "event_revisions"
            ).apply { entry.eventRevisions.forEach { (name, revision) -> put(name, revision) } }
            put("fingerprint", entry.fingerprint)
            put("columns_hash", entry.columnsHash)
        },
    )

    internal fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }
}

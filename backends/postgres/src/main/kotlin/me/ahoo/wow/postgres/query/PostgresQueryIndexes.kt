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

package me.ahoo.wow.postgres.query

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.postgres.PostgresIdentifiers
import me.ahoo.wow.postgres.PostgresSchema
import me.ahoo.wow.postgres.autoCommitted
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.schema.QueryCardinality
import me.ahoo.wow.spi.Ddl
import org.slf4j.LoggerFactory
import java.sql.Connection
import javax.sql.DataSource

/** A planned expression index: its physical name, its table (quoted) and the statement that creates it. */
@InternalWowApi
data class PostgresQueryIndex(val name: String, val table: String, val ddl: String)

/** What [PostgresSnapshotQueryStorage.manageIndexes] did: the indexes it created, and the ones it found missing or invalid. */
@InternalWowApi
data class PostgresQueryIndexReport(
    val created: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val invalid: List<String> = emptyList(),
)

/**
 * The expression indexes of an aggregate type's snapshot table. Each index the aggregate
 * declares in the Manifest becomes
 *
 * ```sql
 * CREATE INDEX CONCURRENTLY IF NOT EXISTS "<table>_<name>" ON "<table>" (tenant_id, <key> NULLS FIRST, ...)
 *     WHERE NOT deleted
 * ```
 *
 * on that aggregate's own table, led by the tenant, which admission adds to every query, and partial on the snapshots
 * that are not deleted, its default scope. A key is a system column or the typed expression of a `state.*` path, exactly the text
 * the SQL compiler compares and sorts by ([PostgresSnapshotQueryExpressions.value], with the type of the binding the
 * query model compiles for it), so the planner matches the two; `NULLS FIRST` is the compiler's ascending order. The
 * name is [PostgresIdentifiers.limited], since PostgreSQL index names are unique per schema, not per table.
 */
@InternalWowApi
object PostgresQueryIndexes {
    private val log = LoggerFactory.getLogger(PostgresQueryIndexes::class.java)

    /**
     * The indexes of [plan] on the snapshot table of [aggregate] in [context], keyed by the expressions of its keys'
     * physical fields. Fails on a key with many values (an array): a B-tree indexes one.
     */
    fun plan(context: String, aggregate: String, plan: QueryIndexPlan): List<PostgresQueryIndex> {
        val tables = PostgresSnapshotTables(context, aggregate)
        return plan.indexes.map { index ->
            val keys = index.keys.map { key(index, it) }
            val name = PostgresIdentifiers.limited(tables.base + "_" + index.name)
            PostgresQueryIndex(
                name,
                tables.snapshot,
                "CREATE INDEX CONCURRENTLY IF NOT EXISTS \"$name\" ON ${tables.snapshot} " +
                    "(tenant_id, ${keys.joinToString(", ")}) WHERE NOT deleted",
            )
        }
    }

    private fun key(index: QueryIndexPlan.Index, key: QueryIndexPlan.Key): String {
        require(key.cardinality == QueryCardinality.SINGLE) {
            "Index [${index.name}] has the key [${key.field.path}], which holds many values: a B-tree indexes one."
        }
        val type = PostgresQueryType.require(key.storageTypes, key.field)
        val expression = PostgresSnapshotQueryExpressions.value(key.physicalField, type)
        val column = if (expression in PostgresSnapshotQueryExpressions.COLUMNS.values) expression else "($expression)"
        return "$column NULLS FIRST"
    }

    /** Manages [indexes] as [mode] says, table by table; see [Ddl]. */
    internal fun manage(
        dataSource: DataSource,
        mode: Ddl,
        indexes: List<PostgresQueryIndex>
    ): PostgresQueryIndexReport {
        if (!mode.checks) return PostgresQueryIndexReport()
        val reports = indexes.groupBy { it.table }.map { (table, planned) -> manage(dataSource, mode, table, planned) }
        return PostgresQueryIndexReport(
            created = reports.flatMap { it.created },
            missing = reports.flatMap { it.missing },
            invalid = reports.flatMap { it.invalid },
        )
    }

    private fun manage(
        dataSource: DataSource,
        mode: Ddl,
        table: String,
        indexes: List<PostgresQueryIndex>,
    ): PostgresQueryIndexReport = dataSource.autoCommitted { connection ->
        val valid = validity(connection, table, indexes)
        val missing = indexes.filter { it.name !in valid }
        val invalid = indexes.filter { valid[it.name] == false }
        if (!mode.creates) {
            // Validating reports every missing or invalid index at once: the host refuses the startup naming them.
            return@autoCommitted PostgresQueryIndexReport(
                missing = missing.map { it.name },
                invalid = invalid.map { it.name },
            )
        }
        // CONCURRENTLY runs outside a transaction and does not block the snapshot writes while it builds. It still
        // waits for the transactions on the table, at most the lock wait, then tries again: an attempt
        // that timed out leaves an invalid index, which the next one drops first. It holds the table's schema lock, so
        // another node's schema step of the table waits for it rather than deadlocking with it, and
        // checks the index again under it: a node that started meanwhile may have built it.
        (invalid + missing).forEach { index ->
            val wait = PostgresSchema.LockWait.DEFAULT
            PostgresSchema.retrying(connection, table, wait) {
                PostgresSchema.locked(connection, table, wait) {
                    if (validity(connection, table, listOf(index))[index.name] != true) {
                        log.info("Creating the query index {} on {}.", index.name, table)
                        PostgresSchema.boundSessionLockWaits(connection, wait) {
                            connection.createStatement().use { statement ->
                                statement.execute("DROP INDEX CONCURRENTLY IF EXISTS \"${index.name}\"")
                                statement.execute(index.ddl)
                            }
                        }
                    }
                }
            }
        }
        PostgresQueryIndexReport(
            created = (invalid + missing).map { it.name },
            missing = missing.map { it.name },
            invalid = invalid.map { it.name },
        )
    }

    /** Whether each of [indexes] on [table] that exists is valid, by name. */
    private fun validity(
        connection: Connection,
        table: String,
        indexes: List<PostgresQueryIndex>
    ): Map<String, Boolean> =
        connection.prepareStatement(
            "SELECT c.relname, i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid " +
                "WHERE i.indrelid = to_regclass(?) AND c.relname = ANY(?)",
        ).use { query ->
            query.setString(1, table)
            query.setArray(2, connection.createArrayOf("text", indexes.map { it.name }.toTypedArray()))
            query.executeQuery().use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getBoolean(2)) }
            }
        }
}

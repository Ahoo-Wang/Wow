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
import me.ahoo.wow.postgres.PostgresEventLogTables
import me.ahoo.wow.postgres.PostgresIdentifiers
import me.ahoo.wow.postgres.PostgresSchema
import me.ahoo.wow.postgres.autoCommitted
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.schema.QueryCardinality
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.json.query.SnapshotFields
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource

/** A planned expression index: its physical name, its table (quoted) and the statement that creates it. */
@InternalWowApi
data class PostgresQueryIndex(val name: String, val table: String, val ddl: String) {
    /**
     * The fingerprint of the planned definition, recorded on the index as its comment when it is built: an existing
     * index of the name whose comment differs (another definition, or none: built before) is drift. It hashes the DDL
     * text, so any edit of that text (this planner's, or a key's expression) rebuilds every declared index of every
     * deployment at its next start under `auto`, and is refused under `validate` until then.
     */
    val fingerprint: String
        get() = "wow:" + MessageDigest.getInstance("SHA-256").digest(ddl.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(FINGERPRINT_CHARS)

    private companion object {
        const val FINGERPRINT_CHARS = 32
    }
}

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
 * CREATE INDEX CONCURRENTLY IF NOT EXISTS "<table>_<name>" ON "<table>" (tenant_id, <key> NULLS FIRST, ..., aggregate_id)
 *     WHERE NOT deleted
 * ```
 *
 * on that aggregate's own table, led by the tenant, which admission adds to every query, and partial on the snapshots
 * that are not deleted, its default scope. A key is a system column or the typed expression of a `state.*` path, exactly the text
 * the SQL compiler compares and sorts by ([PostgresSnapshotQueryExpressions.value], with the type of the binding the
 * query model compiles for it), so the planner matches the two; `NULLS FIRST` is the compiler's ascending order. The
 * name is [PostgresIdentifiers.limited], since PostgreSQL index names are unique per schema, not per table.
 *
 * A B-tree index ends with the columns the compiler ends a snapshot sort with ([PostgresQueryLayout.SNAPSHOT]'s
 * tie-breakers but the tenant): `aggregate_id`. A sort by its keys whose tie-breaker runs in the same direction is the
 * index's order, forward or backward, with no sort: the compiler ends a sort with the tie-breaker in the direction of
 * its last field ([me.ahoo.wow.query.backend.tieBreakerDirection]), so a sort by the keys in one direction is read in
 * index order. An existing index whose definition is not the planned one is drift
 * ([PostgresQueryIndex.fingerprint]).
 *
 * An index of one key that holds many values (a multiple access attribute) is a GIN index of its `jsonb` node,
 * `USING gin ((<node>) jsonb_path_ops) WHERE NOT deleted`: it serves the containment `@>` the SQL compiler writes for
 * an equality, a membership and a containment of such a key, each value one `@>`. A GIN index takes no tenant;
 * the planner ANDs it with the scope's.
 *
 * The access attributes are indexed on the log tables too ([planLog]), for the `events` model's queries under an
 * access policy: the same keys, partial on the key holding a value.
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
        // The columns the compiler ends a snapshot sort with, but the tenant, which leads every index already.
        val tieBreakers = PostgresQueryLayout.SNAPSHOT.tieBreakers
            .filterNot { (field, _) -> field == SnapshotFields.TENANT_ID }
            .map { (_, column) -> column }
        return plan(tables.base, tables.snapshot, plan, PostgresSnapshotQueryExpressions, tieBreakers) { "NOT deleted" }
    }

    /**
     * The indexes of [plan], the access attributes of [aggregate] in [context], on its log table, keyed as the
     * `events` model's SQL compiler reads them ([PostgresEventQueryExpressions]) and each partial on its key holding a
     * value (`WHERE <key> IS NOT NULL`) rather than on the snapshots not deleted: a stream not stamped, or of an
     * aggregate without an owner or a space, adds nothing to an index, and every comparison an access policy makes
     * implies the key holds one. No tie-breaker ends them: they narrow the streams an access policy lets a reader see,
     * and are not read for an order, which for an `events` query ends with the stream key (tenant, aggregate, version).
     */
    fun planLog(context: String, aggregate: String, plan: QueryIndexPlan): List<PostgresQueryIndex> {
        val table = PostgresEventLogTables(context).aggregate(aggregate)
        return plan(
            table.base,
            table.log,
            plan,
            PostgresEventQueryExpressions,
            emptyList()
        ) { key -> "$key IS NOT NULL" }
    }

    private fun plan(
        base: String,
        table: String,
        plan: QueryIndexPlan,
        expressions: PostgresQueryExpressions,
        tieBreakers: List<String>,
        predicate: (key: String) -> String,
    ): List<PostgresQueryIndex> = plan.indexes.map { index ->
        val name = PostgresIdentifiers.limited(base + "_" + index.name)
        val many = index.keys.singleOrNull()?.takeIf { it.cardinality == QueryCardinality.MANY }
        val (columns, first) = if (many != null) {
            val node = expressions.node(many.physicalField)
            "USING gin ($node jsonb_path_ops)" to node
        } else {
            val keys = index.keys.map { key(index, it, expressions) }
            // Ended by the tie-breakers, NOT NULL columns the compiler orders plainly: a sort by the keys whose
            // tie-breaker runs in the same direction is the index's order, forward or backward.
            val columns = keys.map { "$it NULLS FIRST" } + tieBreakers.filterNot { it in keys }
            "(tenant_id, ${columns.joinToString(", ")})" to keys.first()
        }
        PostgresQueryIndex(
            name,
            table,
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS \"$name\" ON $table $columns WHERE ${predicate(first)}",
        )
    }

    private fun key(index: QueryIndexPlan.Index, key: QueryIndexPlan.Key, expressions: PostgresQueryExpressions): String {
        require(key.cardinality == QueryCardinality.SINGLE) {
            "Index [${index.name}] has the key [${key.field.path}], which holds many values: a B-tree indexes one, " +
                "and a GIN index holds that key alone."
        }
        val type = PostgresQueryType.require(key.storageTypes, key.field)
        val expression = expressions.value(key.physicalField, type)
        return if (expression in expressions.COLUMNS.values) expression else "($expression)"
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
            // Validating reports every missing, invalid or drifted index at once: the host refuses the startup naming them.
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
                                // A node stopped before this leaves no fingerprint: the next start rebuilds the index.
                                statement.execute("COMMENT ON INDEX \"${index.name}\" IS '${index.fingerprint}'")
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

    /**
     * Whether each of [indexes] on [table] that exists is the planned one, by name: valid (a build that timed out
     * leaves an invalid one) and recording the planned definition's fingerprint (another definition is drift).
     */
    private fun validity(
        connection: Connection,
        table: String,
        indexes: List<PostgresQueryIndex>
    ): Map<String, Boolean> {
        val planned = indexes.associateBy { it.name }
        return connection.prepareStatement(
            "SELECT c.relname, i.indisvalid, obj_description(i.indexrelid, 'pg_class') FROM pg_index i " +
                "JOIN pg_class c ON c.oid = i.indexrelid WHERE i.indrelid = to_regclass(?) AND c.relname = ANY(?)",
        ).use { query ->
            query.setString(1, table)
            query.setArray(2, connection.createArrayOf("text", indexes.map { it.name }.toTypedArray()))
            query.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val name = rows.getString(1)
                        put(name, rows.getBoolean(2) && rows.getString(3) == planned.getValue(name).fingerprint)
                    }
                }
            }
        }
    }
}

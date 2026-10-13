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

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.postgres.snapshot.PostgresIdentifiers
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryCardinality
import me.ahoo.wow.query.schema.QueryModelSchema
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/** One `x-wow-index` entry of an aggregate's state schema (decision D151): its name and its keys, logical paths. */
data class QueryIndexDeclaration(val name: String, val keys: List<String>) {
    init {
        require(name.isNotEmpty()) { "An index needs a name." }
        require(keys.isNotEmpty()) { "Index [$name] needs at least one key." }
    }

    companion object {
        /** The keyword `manifest-tools` writes the indexes under, at the root of the state schema. */
        const val KEYWORD: String = "x-wow-index"

        /** The indexes the state of [aggregate] in [context] declares in [manifest]; none when it declares none. */
        fun of(manifest: Manifest, context: String, aggregate: String): List<QueryIndexDeclaration> {
            val state = manifest.contexts.firstOrNull { it.name == context }
                ?.aggregates?.firstOrNull { it.name == aggregate }?.state ?: return emptyList()
            val schema = manifest.defs[Manifest.schemaKey(state.schema)] as? Map<*, *> ?: return emptyList()
            val entries = schema[KEYWORD] ?: return emptyList()
            require(entries is List<*>) { "$KEYWORD of $context.$aggregate must be an array." }
            return entries.map { entry ->
                require(entry is Map<*, *>) { "An entry of $KEYWORD of $context.$aggregate must be an object." }
                val name = entry["name"]
                val keys = entry["keys"]
                require(name is String && keys is List<*> && keys.all { it is String }) {
                    "An entry of $KEYWORD of $context.$aggregate needs a name and string keys."
                }
                QueryIndexDeclaration(name, keys.map { it as String })
            }
        }
    }
}

/** A planned expression index: its physical name, its table (quoted) and the statement that creates it. */
data class PostgresQueryIndex(val name: String, val table: String, val ddl: String)

/** What [PostgresSnapshotQueryStorage.manageIndexes] did: the indexes it created, and the ones it found missing or invalid. */
data class PostgresQueryIndexReport(
    val created: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val invalid: List<String> = emptyList(),
)

/**
 * The expression indexes of an aggregate type's snapshot table (decisions D151, D161). Each index the aggregate
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
object PostgresQueryIndexes {
    private val log = LoggerFactory.getLogger(PostgresQueryIndexes::class.java)

    /** The capabilities whose binding an index key takes its expression from: those an index serves. */
    private val INDEXED = listOf(QueryCapability.SORT, QueryCapability.RANGE, QueryCapability.EXACT_MATCH)

    /**
     * The indexes of [declarations] on the snapshot table of [aggregate] in [context], keyed by the expressions of
     * [schema], the aggregate's snapshot query model on this backend. Fails on a key the model does not know or
     * cannot index: one without an exact match, a range or a sort, or one with many values (an array).
     */
    fun plan(
        context: String,
        aggregate: String,
        schema: QueryModelSchema,
        declarations: List<QueryIndexDeclaration>,
    ): List<PostgresQueryIndex> {
        val tables = PostgresSnapshotTables(context, aggregate)
        return declarations.map { declaration ->
            val keys = declaration.keys.map { key(schema, declaration, it) }
            val name = PostgresIdentifiers.limited(tables.base + "_" + declaration.name)
            PostgresQueryIndex(
                name,
                tables.snapshot,
                "CREATE INDEX CONCURRENTLY IF NOT EXISTS \"$name\" ON ${tables.snapshot} " +
                    "(tenant_id, ${keys.joinToString(", ")}) WHERE NOT deleted",
            )
        }
    }

    private fun key(schema: QueryModelSchema, declaration: QueryIndexDeclaration, key: String): String {
        val field = requireNotNull(schema.field(QueryField(key))) {
            "Index [${declaration.name}] has the key [$key], which the query model does not know."
        }
        val binding = requireNotNull(INDEXED.firstNotNullOfOrNull(field::binding)) {
            "Index [${declaration.name}] has the key [$key], which PostgreSQL neither matches, ranges nor sorts."
        }
        require(field.value.cardinality == QueryCardinality.SINGLE) {
            "Index [${declaration.name}] has the key [$key], which holds many values: a B-tree indexes one."
        }
        val type = PostgresQueryType.require(binding.storageTypes, field.logicalField)
        val expression = PostgresSnapshotQueryExpressions.value(binding.physicalField, type)
        val column = if (expression in PostgresSnapshotQueryExpressions.COLUMNS.values) expression else "($expression)"
        return "$column NULLS FIRST"
    }

    /** Manages [indexes] as [mode] says, table by table; see [QueryIndexDdl]. */
    internal fun manage(
        dataSource: DataSource,
        mode: QueryIndexDdl,
        indexes: List<PostgresQueryIndex>
    ): PostgresQueryIndexReport {
        if (mode == QueryIndexDdl.NONE) return PostgresQueryIndexReport()
        val reports = indexes.groupBy { it.table }.map { (table, planned) -> manage(dataSource, mode, table, planned) }
        return PostgresQueryIndexReport(
            created = reports.flatMap { it.created },
            missing = reports.flatMap { it.missing },
            invalid = reports.flatMap { it.invalid },
        )
    }

    private fun manage(
        dataSource: DataSource,
        mode: QueryIndexDdl,
        table: String,
        indexes: List<PostgresQueryIndex>,
    ): PostgresQueryIndexReport = dataSource.connection.use { connection ->
        val valid = connection.prepareStatement(
            "SELECT c.relname, i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid " +
                "WHERE i.indrelid = to_regclass(?) AND c.relname = ANY(?)",
        ).use { query ->
            query.setString(1, table)
            query.setArray(2, connection.createArrayOf("text", indexes.map { it.name }.toTypedArray()))
            query.executeQuery().use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getBoolean(2)) }
            }
        }
        val missing = indexes.filter { it.name !in valid }
        val invalid = indexes.filter { valid[it.name] == false }
        if (mode == QueryIndexDdl.VALIDATE) {
            if (missing.isNotEmpty() || invalid.isNotEmpty()) {
                log.warn(
                    "Query indexes of {} are missing {} and invalid {} ({}=validate).",
                    table,
                    missing.map { it.name },
                    invalid.map { it.name },
                    QueryIndexDdl.PROPERTY,
                )
            }
            return@use PostgresQueryIndexReport(missing = missing.map { it.name }, invalid = invalid.map { it.name })
        }
        // CONCURRENTLY runs outside a transaction and does not block the snapshot writes while it builds.
        connection.createStatement().use { statement ->
            invalid.forEach { statement.execute("DROP INDEX CONCURRENTLY IF EXISTS \"${it.name}\"") }
            (invalid + missing).forEach { index ->
                log.info("Creating the query index {} on {}.", index.name, table)
                statement.execute(index.ddl)
            }
        }
        PostgresQueryIndexReport(
            created = (invalid + missing).map { it.name },
            missing = missing.map { it.name },
            invalid = invalid.map { it.name },
        )
    }
}

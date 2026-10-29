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
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.schema.QueryStorageType
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.operationValues
import me.ahoo.wow.spi.json.query.SnapshotFields

/**
 * The SQL type a query field is compared, sorted and aggregated as: the storage type of its bindings
 * ([PostgresSnapshotStorageAdapter]), and the cast of its expression ([PostgresSnapshotQueryExpressions]).
 */
internal enum class PostgresQueryType(val sql: String, val catalogType: String) {
    /** A string, compared by code point (`COLLATE "C"`). */
    TEXT("text", "text"),

    /** Any JSON number, integral or not: `numeric` holds every one exactly. */
    NUMERIC("numeric", "numeric"),
    BOOLEAN("boolean", "bool"),

    /** The `integer` column `version`. */
    INTEGER("integer", "int4"),

    /** The `bigint` columns of the event times. */
    BIGINT("bigint", "int8"),

    /** An object, an array, or a value of mixed types: compared as `jsonb`. */
    JSONB("jsonb", "jsonb"),
    ;

    /** This type as the storage type of a binding. */
    val storageType: QueryStorageType = QueryStorageType(sql)

    /** Whether values of this type are numbers. */
    val numeric: Boolean
        get() = this == NUMERIC || this == INTEGER || this == BIGINT

    companion object {
        /** The type a binding states, or `null` when it states none of these. */
        fun of(storageTypes: Set<QueryStorageType>?): PostgresQueryType? =
            storageTypes?.singleOrNull()?.let { type -> entries.firstOrNull { it.storageType == type } }

        /** The type a binding of [field] states; a binding of this storage always states one. */
        fun require(storageTypes: Set<QueryStorageType>?, field: QueryField<*>): PostgresQueryType =
            of(storageTypes) ?: throw QueryExecutionException("Field [$field] has no PostgreSQL type.")

        /**
         * The type a `state.*` value is compared as, derived from its declared [QueryValueType]s: STRING is
         * [TEXT], INTEGER and DECIMAL are [NUMERIC], BOOLEAN is [BOOLEAN], and anything else (an object, an array
         * of objects, a mix of types) is [JSONB]. An array's type is its items' type.
         */
        fun of(value: QueryValueSchema): PostgresQueryType {
            val scalars = value.operationValues().filter { it.kind != QueryValueKind.NULL }
            if (scalars.isEmpty() || scalars.any { it.kind != QueryValueKind.SCALAR }) return JSONB
            val types = scalars.flatMapTo(hashSetOf()) { it.valueTypes }
            return when {
                types == setOf(QueryValueType.STRING) -> TEXT
                types == setOf(QueryValueType.BOOLEAN) -> BOOLEAN
                types.isNotEmpty() && (types - NUMBER_TYPES).isEmpty() -> NUMERIC
                else -> JSONB
            }
        }

        private val NUMBER_TYPES = setOf(QueryValueType.INTEGER, QueryValueType.DECIMAL)
    }
}

/**
 * The SQL expressions of the snapshot table's query fields: the one source of the text the SQL
 * compiler emits and the expression indexes are created with, so an index on a field is the index of the expression
 * every query on it compares. Expressions are deterministic for a physical path and a [PostgresQueryType], and
 * contain no parameter: the path segments, checked against [SEGMENT], are the only text they take from a query.
 *
 * A system field is its column (`tenantId` is `tenant_id`). A path below `state`, `tags`, `operator`,
 * `firstOperator` or `schedules`, all `jsonb`, is
 * - its node, `(state #> '{a,b}')`, which presence, containment and array operators read; and
 * - its typed value: the node's text cast to the type when the node holds a JSON value of that type, otherwise
 *   `NULL`, so a value of another type never matches, never fails a cast, and never fails the save of a snapshot
 *   under an expression index. Text is `COLLATE "C"`, ordered by code point:
 *
 * | Type | Expression |
 * | --- | --- |
 * | [PostgresQueryType.TEXT] | `((CASE WHEN jsonb_typeof(state #> '{a}') = 'string' THEN state #>> '{a}' END) COLLATE "C")` |
 * | [PostgresQueryType.NUMERIC] | `(CASE WHEN jsonb_typeof(state #> '{a}') = 'number' THEN (state #>> '{a}')::numeric END)` |
 * | [PostgresQueryType.BOOLEAN] | `(CASE WHEN jsonb_typeof(state #> '{a}') = 'boolean' THEN (state #>> '{a}')::boolean END)` |
 */
internal object PostgresSnapshotQueryExpressions : PostgresQueryExpressions(
    columns = mapOf(
        SnapshotFields.TENANT_ID to "tenant_id",
        SnapshotFields.AGGREGATE_ID to "aggregate_id",
        SnapshotFields.VERSION to "version",
        SnapshotFields.OWNER_ID to "owner_id",
        SnapshotFields.SPACE_ID to "space_id",
        SnapshotFields.DELETED to "deleted",
        SnapshotFields.TAGS to "tags",
        SnapshotFields.FIRST_EVENT_TIME to "first_event_time",
        SnapshotFields.LAST_EVENT_TIME to "last_event_time",
        SnapshotFields.OPERATOR to "operator",
        SnapshotFields.FIRST_OPERATOR to "first_operator",
        SnapshotFields.SCHEDULES to "schedules",
        SnapshotFields.STATE to "state",
    ),
    columnTypes = mapOf(
        SnapshotFields.TENANT_ID to PostgresQueryType.TEXT,
        SnapshotFields.AGGREGATE_ID to PostgresQueryType.TEXT,
        SnapshotFields.VERSION to PostgresQueryType.INTEGER,
        SnapshotFields.OWNER_ID to PostgresQueryType.TEXT,
        SnapshotFields.SPACE_ID to PostgresQueryType.TEXT,
        SnapshotFields.DELETED to PostgresQueryType.BOOLEAN,
        SnapshotFields.TAGS to PostgresQueryType.JSONB,
        SnapshotFields.FIRST_EVENT_TIME to PostgresQueryType.BIGINT,
        SnapshotFields.LAST_EVENT_TIME to PostgresQueryType.BIGINT,
        SnapshotFields.OPERATOR to PostgresQueryType.JSONB,
        SnapshotFields.FIRST_OPERATOR to PostgresQueryType.JSONB,
        SnapshotFields.SCHEDULES to PostgresQueryType.JSONB,
        SnapshotFields.STATE to PostgresQueryType.JSONB,
    ),
)

/**
 * The SQL expressions of one query table's fields: [columns] holds the column of each top-level physical field, and
 * [columnTypes] its type, `jsonb` for a column a path may go below (see [PostgresSnapshotQueryExpressions], whose
 * table the snapshot queries read; the events model reads the log tables, [PostgresEventQueryExpressions]).
 */
internal open class PostgresQueryExpressions(
    columns: Map<String, String>,
    columnTypes: Map<String, PostgresQueryType>,
) {
    /** The columns of the system fields, by physical name. */
    @Suppress("VariableNaming") // Kept the snapshot object's constant names.
    val COLUMNS: Map<String, String> = columns

    /** The types of the system columns, by physical name. */
    @Suppress("VariableNaming")
    val COLUMN_TYPES: Map<String, PostgresQueryType> = columnTypes

    /** The characters a path segment may hold, as the query model's path segments do. */
    @Suppress("VariableNaming")
    val SEGMENT: Regex = Regex("[A-Za-z0-9_@-]+")

    private val jsonbColumns = columnTypes.filterValues { it == PostgresQueryType.JSONB }.keys

    /** The typed value of the field at the absolute physical [path] of a snapshot row. */
    fun value(path: QueryField<*>, type: PostgresQueryType): String {
        val (head, rest) = split(path)
        val column = column(head)
        if (head !in jsonbColumns) {
            requireExecution(rest.isEmpty()) { "Field [$path] is below the scalar column [$column]." }
            return column
        }
        return if (rest.isEmpty()) column else value(column, rest, type)
    }

    /** The `jsonb` node of the field at the absolute physical [path] of a snapshot row; a scalar column as `jsonb`. */
    fun node(path: QueryField<*>): String {
        val (head, rest) = split(path)
        val column = column(head)
        if (head !in jsonbColumns) {
            requireExecution(rest.isEmpty()) { "Field [$path] is below the scalar column [$column]." }
            // A scalar column always holds its field: a NULL owner is a JSON null, as the snapshot record shows it.
            return "coalesce(to_jsonb($column), 'null'::jsonb)"
        }
        return node(column, rest)
    }

    /** The `jsonb` node at [segments] below the `jsonb` expression [base]. */
    fun node(base: String, segments: List<String>): String =
        if (segments.isEmpty()) base else "($base #> '${pathLiteral(segments)}')"

    /** The typed value at [segments] below the `jsonb` expression [base]: see the table of this object. */
    fun value(base: String, segments: List<String>, type: PostgresQueryType): String {
        val node = node(base, segments)
        val text = if (segments.isEmpty()) "$base #>> '{}'" else "$base #>> '${pathLiteral(segments)}'"
        return when (type) {
            PostgresQueryType.TEXT -> "((CASE WHEN jsonb_typeof($node) = 'string' THEN $text END) COLLATE \"C\")"
            PostgresQueryType.NUMERIC,
            PostgresQueryType.INTEGER,
            PostgresQueryType.BIGINT,
            -> "(CASE WHEN jsonb_typeof($node) = 'number' THEN ($text)::numeric END)"
            PostgresQueryType.BOOLEAN -> "(CASE WHEN jsonb_typeof($node) = 'boolean' THEN ($text)::boolean END)"
            PostgresQueryType.JSONB -> node
        }
    }

    /** The column of the system field [field]. */
    fun column(field: String): String =
        COLUMNS[field] ?: throw QueryExecutionException("Field [$field] is not a field of the query table.")

    private fun split(path: QueryField<*>): Pair<String, List<String>> {
        val segments = path.path.split('.')
        return segments.first() to segments.drop(1)
    }

    /** The `text[]` literal of [segments]; each is checked against [SEGMENT], so it needs no quoting. */
    private fun pathLiteral(segments: List<String>): String {
        segments.forEach { segment ->
            requireExecution(SEGMENT.matches(segment)) { "Path segment [$segment] is not a valid query path segment." }
        }
        return segments.joinToString(",", "{", "}")
    }
}

internal inline fun requireExecution(condition: Boolean, message: () -> String) {
    if (!condition) throw QueryExecutionException(message())
}

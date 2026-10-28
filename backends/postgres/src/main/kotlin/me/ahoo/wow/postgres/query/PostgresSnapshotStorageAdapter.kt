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

import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryCardinality
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryStorageFamily
import me.ahoo.wow.query.schema.QueryStorageFamilyRules
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.alternativesOrSelf
import me.ahoo.wow.query.schema.operationValues
import me.ahoo.wow.query.schema.storageFamilies

/**
 * What a PostgreSQL snapshot table proves about a snapshot query model, from the types of its columns
 * ([facts] with the catalog's column types; [PostgresQuerySchemaAdapter] reads them). This object's own [facts] assume
 * the table this module creates. Every logical path is stored under its own name, a system field in its column and anything else in the `state` or `tags` `jsonb`; each
 * binding states its [PostgresQueryType] as its storage type, which the SQL compiler and the expression indexes read.
 *
 * A path is granted the capabilities the storage-family table ([storageFamilies]) allows for its values and its type
 * provides, with these exceptions, which admission rejects as unsupported:
 * - no full-text search (FULL_TEXT_TERMS, FULL_TEXT_PHRASE): PostgreSQL text search ranks and stems, which the query
 *   protocol does not define;
 * - a date string ([me.ahoo.wow.query.schema.Temporal.Date]) only exists: an ISO-8601 instant does not order as text
 *   (its fraction and offset vary), and the cast from text to `timestamptz` depends on the session's `DateStyle` and
 *   `TimeZone`, so it is not immutable and cannot be indexed. An epoch is a number and supports everything;
 * - a multi-valued path (an array) is not sortable or aggregatable: its elements have no single value;
 * - a value of mixed types (`jsonb`) only exists;
 * - a path whose column does not have the type this module creates it with (a system column of another type, a
 *   `state` or `tags` column that is not `jsonb`) only exists: its values may not be what the compiler casts.
 *
 * `jsonb` states no type per path, so a `state.*` path is typed from its declared value types, as 9.x MongoDB typed a
 * path its collection's validator did not declare. Indexes prove no capability: PostgreSQL sorts, ranges and
 * aggregates every typed expression with or without one; an index only makes it fast ([PostgresQueryIndexes]).
 */
internal object PostgresSnapshotStorageAdapter : QueryStorageAdapter {
    /** The storage-family rules of this storage: a date string is no operand (see the class). */
    val RULES: QueryStorageFamilyRules = QueryStorageFamilyRules(dateOperands = false)

    /** How the storage pages and aggregates: everything natively, but dense fill and the windows, which the core runs. */
    val SUPPORT: StorageSupport = StorageSupport(
        aggregation = AggregationSupport(
            having = SupportMode.NATIVE,
            topN = SupportMode.NATIVE,
            denseFill = SupportMode.RESIDUAL,
            percentile = SupportMode.NATIVE,
            distinctCount = SupportMode.NATIVE,
            firstLast = SupportMode.NATIVE,
            rootGroups = SupportMode.NATIVE,
        ),
        parallelArraySort = SupportMode.NONE,
    )

    private val VALUE_CAPABILITIES = listOf(
        QueryCapability.EXACT_MATCH,
        QueryCapability.LITERAL_MATCH,
        QueryCapability.RANGE,
        QueryCapability.SORT,
        QueryCapability.CURSOR_SORT,
        QueryCapability.AGGREGATE_TERMS,
        QueryCapability.AGGREGATE_NUMERIC,
        QueryCapability.AGGREGATE_TEMPORAL,
    )

    /** What a multi-valued path cannot do: it has no single value to order or group by. */
    private val SINGLE_VALUE_CAPABILITIES = setOf(
        QueryCapability.SORT,
        QueryCapability.CURSOR_SORT,
        QueryCapability.AGGREGATE_TERMS,
        QueryCapability.AGGREGATE_NUMERIC,
        QueryCapability.AGGREGATE_TEMPORAL,
    )

    /** The catalog type (`pg_type.typname`) of each column, as this module creates the snapshot table. */
    val COLUMNS: Map<String, String> = PostgresSnapshotQueryExpressions.COLUMNS.entries.associate { (field, column) ->
        column to PostgresSnapshotQueryExpressions.COLUMN_TYPES.getValue(field).catalogType
    }

    override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts = facts(logicalSchema, COLUMNS)

    /**
     * The facts of a table whose columns have the catalog types [columns]; [provisional] for a table that does not
     * exist yet, described by the one it will be created as.
     */
    fun facts(
        logicalSchema: LogicalQuerySchema,
        columns: Map<String, String>,
        provisional: Boolean = false,
    ): QueryStorageFacts = QueryStorageFacts(
        bindings = logicalSchema.values.filterKeys { it.segments.isNotEmpty() }.mapValues { (path, value) ->
            val type = typeOf(path, value)
            val storageTypes = setOf(type.storageType)
            val capabilities = if (proven(
                    path,
                    columns
                )
            ) {
                capabilitiesOf(value, type)
            } else {
                setOf(QueryCapability.PRESENCE)
            }
            QueryValueBindings(capabilities.associateWith { QueryFieldBindingTemplate(path, storageTypes) }, path, path)
        },
        storage = SUPPORT,
        provisional = provisional,
    )

    /** Whether the column [path] is stored in has the type this module creates it with. */
    private fun proven(path: QueryPathTemplate, columns: Map<String, String>): Boolean {
        val column = PostgresSnapshotQueryExpressions.COLUMNS[(path.segments.first() as? QueryPathSegment.Property)?.name]
        return column != null && columns[column] == COLUMNS[column]
    }

    /** The type of [path]: its column's, for a system field, else derived from its [value]. */
    fun typeOf(path: QueryPathTemplate, value: QueryValueSchema): PostgresQueryType {
        val column = (path.segments.singleOrNull() as? QueryPathSegment.Property)?.name
        return column?.let(PostgresSnapshotQueryExpressions.COLUMN_TYPES::get) ?: PostgresQueryType.of(value)
    }

    /** The capabilities [value], compared as [type], is granted (see the class). */
    fun capabilitiesOf(value: QueryValueSchema, type: PostgresQueryType): Set<QueryCapability> {
        val capabilities = linkedSetOf(QueryCapability.PRESENCE)
        if (value.alternativesOrSelf().any { it.kind == QueryValueKind.ARRAY }) {
            capabilities += QueryCapability.ELEMENT_SCOPE
        }
        val provided = FAMILIES[type].orEmpty()
        if (provided.isEmpty()) return capabilities
        val scalars = value.operationValues().filter { it.kind != QueryValueKind.NULL }
        val single = value.cardinality == QueryCardinality.SINGLE
        VALUE_CAPABILITIES.filter { single || it !in SINGLE_VALUE_CAPABILITIES }.forEach { capability ->
            val proven = scalars.all { scalar ->
                val families = scalar.storageFamilies(capability, RULES)
                families.isNotEmpty() && families.all { needed -> needed.any(provided::contains) }
            }
            if (proven) capabilities += capability
        }
        return capabilities
    }

    private val FAMILIES: Map<PostgresQueryType, Set<QueryStorageFamily>> = mapOf(
        PostgresQueryType.TEXT to setOf(QueryStorageFamily.STRING, QueryStorageFamily.EXACT_STRING),
        PostgresQueryType.BOOLEAN to setOf(QueryStorageFamily.BOOLEAN),
        PostgresQueryType.NUMERIC to NUMBER_FAMILIES,
        PostgresQueryType.INTEGER to NUMBER_FAMILIES,
        PostgresQueryType.BIGINT to NUMBER_FAMILIES,
    )
}

private val NUMBER_FAMILIES = setOf(
    QueryStorageFamily.INTEGRAL,
    QueryStorageFamily.SIGNED_INTEGRAL,
    QueryStorageFamily.NUMERIC,
)

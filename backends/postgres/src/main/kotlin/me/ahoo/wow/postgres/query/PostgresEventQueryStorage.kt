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

import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.postgres.PostgresEventLogTables
import me.ahoo.wow.query.AbstractQueryBackendFactory
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.spi.VirtualThreads
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.json.query.EventFields
import me.ahoo.wow.spi.json.query.EventStreamFields
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * The `events` query storage of one bounded context on PostgreSQL (decision D235): the [PostgresQueryBackend] of each
 * of its aggregate types over that aggregate's own log table (`wow_log__<ctx>__<agg>`, decision D162), which
 * `PostgresEventLog` appends to, with the facts of [PostgresEventStorageAdapter]. One record per stream, as stored.
 *
 * The log is the write store: its only indexes are the version key `(aggregate_id, tenant_id, version)`, the request
 * key `(tenant_id, request_id)` and the position `(partition, sequence)`, so a query that does not pin the aggregate
 * (or the request) scans the aggregate type's table. The entry's budget bounds it (`statement_timeout`, sizes, the
 * count limit); an analytics-heavy deployment serves the model from ClickHouse or Elasticsearch.
 */
@InternalWowApi
class PostgresEventQueryStorage @JvmOverloads constructor(
    private val dataSource: DataSource,
    val context: String,
    val options: PostgresQueryOptions = PostgresQueryOptions(),
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : AbstractQueryBackendFactory<PostgresQueryBackend>() {
    private val tables = PostgresEventLogTables(context)

    /** The log table of [aggregateName], quoted. */
    fun table(aggregateName: String): String = tables.aggregate(aggregateName).log

    override fun createBinding(aggregateType: AggregateType): QueryBackendBinding<PostgresQueryBackend> {
        require(aggregateType.context == context) { "$aggregateType is not in context $context." }
        return QueryBackendBinding(
            PostgresQueryBackend(
                aggregateType,
                dataSource,
                table(aggregateType.name),
                options,
                dispatcher,
                PostgresEventQueryLayout.EVENTS,
            ),
            PostgresEventStorageAdapter,
        )
    }
}

/**
 * The fields of a log table (`AggregateLogTable`, decision D162), by physical name: each system field of a stream its
 * column, the operator's three columns as `operatorId`, `operatorTenantId` and `operatorCrossScope`, the tags the
 * nullable `jsonb` column `tags` (decision D259), and the events
 * the one `jsonb` column `events`, each `{name, revision, payload}`.
 */
internal object PostgresEventQueryExpressions : PostgresQueryExpressions(
    columns = mapOf(
        EventStreamFields.TENANT_ID to "tenant_id",
        EventStreamFields.AGGREGATE_ID to "aggregate_id",
        EventStreamFields.VERSION to "version",
        EventStreamFields.COMMIT_TIME to "commit_time",
        EventStreamFields.COMMAND_ID to "command_id",
        EventStreamFields.REQUEST_ID to "request_id",
        EventStreamFields.COMMAND_TYPE to "command_type",
        EventStreamFields.OWNER_ID to "owner_id",
        EventStreamFields.SPACE_ID to "space_id",
        PostgresEventQueryLayout.OPERATOR_ID to "operator_id",
        PostgresEventQueryLayout.OPERATOR_TENANT_ID to "operator_tenant_id",
        PostgresEventQueryLayout.OPERATOR_CROSS_SCOPE to "operator_cross_scope",
        EventStreamFields.TAGS to "tags",
        EventStreamFields.EVENTS to "events",
    ),
    columnTypes = mapOf(
        EventStreamFields.TENANT_ID to PostgresQueryType.TEXT,
        EventStreamFields.AGGREGATE_ID to PostgresQueryType.TEXT,
        EventStreamFields.VERSION to PostgresQueryType.INTEGER,
        EventStreamFields.COMMIT_TIME to PostgresQueryType.BIGINT,
        EventStreamFields.COMMAND_ID to PostgresQueryType.TEXT,
        EventStreamFields.REQUEST_ID to PostgresQueryType.TEXT,
        EventStreamFields.COMMAND_TYPE to PostgresQueryType.TEXT,
        EventStreamFields.OWNER_ID to PostgresQueryType.TEXT,
        EventStreamFields.SPACE_ID to PostgresQueryType.TEXT,
        PostgresEventQueryLayout.OPERATOR_ID to PostgresQueryType.TEXT,
        PostgresEventQueryLayout.OPERATOR_TENANT_ID to PostgresQueryType.TEXT,
        PostgresEventQueryLayout.OPERATOR_CROSS_SCOPE to PostgresQueryType.BOOLEAN,
        EventStreamFields.TAGS to PostgresQueryType.JSONB,
        EventStreamFields.EVENTS to PostgresQueryType.JSONB,
    ),
)

/** How a log table is read as the `events` model's records (decision D235). */
internal object PostgresEventQueryLayout {
    const val OPERATOR_ID: String = "operatorId"
    const val OPERATOR_TENANT_ID: String = "operatorTenantId"
    const val OPERATOR_CROSS_SCOPE: String = "operatorCrossScope"

    /** The key the log stores an event's body under. */
    const val PAYLOAD: String = "payload"

    private const val COLUMNS = "tenant_id, aggregate_id, version, commit_time, command_id, request_id, command_type, " +
        "owner_id, space_id, operator_id, operator_tenant_id, operator_cross_scope, tags"
    private val nodes: JsonNodeFactory = JsonNodeFactory.instance

    /** The log table: by tenant, aggregate and version, its key; the events only read when the projection keeps them. */
    val EVENTS: PostgresQueryLayout = PostgresQueryLayout(
        expressions = PostgresEventQueryExpressions,
        select = { projection -> "SELECT $COLUMNS" + if (projection.keepsEvents()) ", events" else "" },
        tieBreakers = listOf(
            EventStreamFields.TENANT_ID to "tenant_id",
            EventStreamFields.AGGREGATE_ID to "aggregate_id",
            EventStreamFields.VERSION to "version",
        ),
        record = ::record,
    )

    private fun Projection.keepsEvents(): Boolean = if (include.isEmpty()) {
        exclude.none { it.path == EventStreamFields.EVENTS }
    } else {
        include.any { it.path == EventStreamFields.EVENTS }
    }

    /** The stream of [row] as stored, its events `{name, revision, body}`; the events only when [projection] keeps them. */
    fun record(row: ResultSet, projection: Projection): ObjectNode = nodes.objectNode().apply {
        put(EventStreamFields.AGGREGATE_ID, row.getString("aggregate_id"))
        put(EventStreamFields.TENANT_ID, row.getString("tenant_id"))
        put(EventStreamFields.VERSION, row.getInt("version"))
        put(EventStreamFields.COMMIT_TIME, row.getLong("commit_time"))
        put(EventStreamFields.COMMAND_ID, row.getString("command_id"))
        put(EventStreamFields.REQUEST_ID, row.getString("request_id"))
        put(EventStreamFields.COMMAND_TYPE, row.getString("command_type"))
        put(EventStreamFields.OWNER_ID, row.getString("owner_id"))
        put(EventStreamFields.SPACE_ID, row.getString("space_id"))
        val operator = row.getString("operator_id")
        if (operator == null) {
            putNull(EventStreamFields.OPERATOR)
        } else {
            putObject(EventStreamFields.OPERATOR)
                .put("id", operator)
                .put("tenantId", row.getString("operator_tenant_id"))
                .put("crossScope", row.getBoolean("operator_cross_scope"))
        }
        val tags = row.getString("tags")
        if (tags == null) {
            putNull(EventStreamFields.TAGS)
        } else {
            set(EventStreamFields.TAGS, WowJson.mapper.readTree(tags))
        }
        if (projection.keepsEvents()) {
            val events = putArray(EventStreamFields.EVENTS)
            WowJson.mapper.readTree(row.getString("events")).forEach { event ->
                events.addObject()
                    .put(EventFields.NAME, event.get(EventFields.NAME).asString())
                    .put(EventFields.REVISION, event.get(EventFields.REVISION).asInt())
                    .set(EventFields.BODY, event.get(PAYLOAD))
            }
        }
    }
}

/**
 * What a log table proves about an `events` query model (decision D235), for the table this module creates: each
 * logical path bound to its physical field ([PostgresEventQueryExpressions]): a system field to its column,
 * `operator.id`, `operator.tenantId` and `operator.crossScope` to the operator's columns (`operator` itself to the id,
 * for its presence), and a path below `events[].body` to the stored `payload`. Each path is granted what the snapshot
 * table grants a path of its type ([PostgresSnapshotStorageAdapter.capabilitiesOf]): no full-text search, no sort or
 * aggregation of a multi-valued path, element scopes on `events` and their expansion.
 */
internal object PostgresEventStorageAdapter : QueryStorageAdapter {
    private val OPERATOR = QueryPathSegment.Property(EventStreamFields.OPERATOR)
    private val EVENTS = QueryPathSegment.Property(EventStreamFields.EVENTS)

    /** `events[].body`, which the log stores as `events[].payload`. */
    private val BODY = listOf(EVENTS, QueryPathSegment.Item, QueryPathSegment.Property(EventFields.BODY))
    private val PAYLOAD = listOf(
        EVENTS,
        QueryPathSegment.Item,
        QueryPathSegment.Property(PostgresEventQueryLayout.PAYLOAD)
    )
    private val OPERATOR_COLUMNS = mapOf(
        "id" to PostgresEventQueryLayout.OPERATOR_ID,
        "tenantId" to PostgresEventQueryLayout.OPERATOR_TENANT_ID,
        "crossScope" to PostgresEventQueryLayout.OPERATOR_CROSS_SCOPE,
    )

    override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts = QueryStorageFacts(
        bindings = logicalSchema.values.filterKeys { it.segments.isNotEmpty() }.mapValues { (path, value) ->
            val physical = physical(path)
            val type = (physical.segments.singleOrNull() as? QueryPathSegment.Property)?.name
                ?.let(PostgresEventQueryExpressions.COLUMN_TYPES::get)
                ?: PostgresQueryType.of(value)
            val capabilities = if (path.segments == listOf(OPERATOR)) {
                setOf(QueryCapability.PRESENCE)
            } else {
                PostgresSnapshotStorageAdapter.capabilitiesOf(value, type)
            }
            val binding = QueryFieldBindingTemplate(physical, setOf(type.storageType))
            QueryValueBindings(capabilities.associateWith { binding }, path, path)
        },
        storage = PostgresSnapshotStorageAdapter.SUPPORT,
    )

    /** The physical path of [path]: see the class. */
    private fun physical(path: QueryPathTemplate): QueryPathTemplate {
        val segments = path.segments
        if (segments.firstOrNull() == OPERATOR) {
            val column = (segments.getOrNull(1) as? QueryPathSegment.Property)?.name?.let(OPERATOR_COLUMNS::get)
                ?: PostgresEventQueryLayout.OPERATOR_ID
            return QueryPathTemplate(listOf(QueryPathSegment.Property(column)))
        }
        if (segments.take(BODY.size) == BODY) {
            return QueryPathTemplate(PAYLOAD + segments.drop(BODY.size))
        }
        return path
    }
}

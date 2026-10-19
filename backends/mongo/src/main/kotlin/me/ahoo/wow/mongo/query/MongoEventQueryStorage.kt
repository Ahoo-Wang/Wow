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

package me.ahoo.wow.mongo.query

import com.mongodb.ReadPreference
import com.mongodb.client.MongoClient
import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.mongo.ExactBson
import me.ahoo.wow.mongo.MongoNames
import me.ahoo.wow.mongo.MongoOperators
import me.ahoo.wow.mongo.VirtualThreads
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.query.AbstractQueryBackendFactory
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.schema.EventFields
import me.ahoo.wow.query.schema.EventStreamFields
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import org.bson.Document
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * The `events` query storage of one bounded context on MongoDB (decision D235): the [MongoSnapshotQueryBackend] of
 * each of its aggregate types over that aggregate's own log collection (`wow_log__<ctx>__<agg>`, decision D162), which
 * `MongoEventLog` appends to, its documents read as streams ([MongoEventDocuments]), with the facts of
 * [MongoEventStorageAdapter]. One record per stream, as stored.
 *
 * The log is the write store: its only indexes are `(aggregate_id, tenant_id, version)`, `(tenant_id, request_id)` and
 * `(partition, sequence)`, so a query that does not pin the aggregate (or the request) scans the aggregate type's
 * collection. The entry's budget bounds it (`maxTimeMS`, sizes, the count limit). [readPreference] moves the reads
 * off the primary (`secondaryPreferred`, say), at the cost of reading a little behind the appends; the primary's by
 * default. An analytics-heavy deployment serves the model from ClickHouse or Elasticsearch.
 */
@InternalWowApi
class MongoEventQueryStorage @JvmOverloads constructor(
    client: MongoClient,
    databaseName: String,
    val context: String,
    val options: MongoQueryOptions = MongoQueryOptions(),
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
    private val readPreference: ReadPreference? = null,
) : AbstractQueryBackendFactory<MongoSnapshotQueryBackend>() {
    private val database = client.getDatabase(databaseName)

    /** The log collection of [aggregateName]. */
    fun collection(aggregateName: String): String = MongoNames.of(MongoEventLog.PREFIX, context, aggregateName)

    override fun createBinding(aggregateType: AggregateType): QueryBackendBinding<MongoSnapshotQueryBackend> {
        require(aggregateType.context == context) { "$aggregateType is not in context $context." }
        val collection = database.getCollection(collection(aggregateType.name))
            .let { collection -> readPreference?.let(collection::withReadPreference) ?: collection }
        return QueryBackendBinding(
            MongoSnapshotQueryBackend(aggregateType, collection, options, dispatcher, MongoEventDocuments),
            MongoEventStorageAdapter,
        )
    }
}

/**
 * A stream document `MongoEventLog` writes, as the `events` model reads it (decisions D162, D235): each system field
 * of a stream a top-level field under its stored name ([PHYSICAL]), the operator the document `{id, tenantId,
 * crossScope}` or absent (decision D163), and the events the array `events`, each `{name, revision, payload}`, whose
 * `payload` a record names `body`. The position, causation, headers and transport are never part of a record.
 */
internal object MongoEventDocuments : MongoQueryDocuments {
    /** The stored name of each top-level field of a stream record, in the record's order. */
    private val PHYSICAL: Map<String, String> = linkedMapOf(
        EventStreamFields.AGGREGATE_ID to "aggregate_id",
        EventStreamFields.TENANT_ID to "tenant_id",
        EventStreamFields.VERSION to "version",
        EventStreamFields.COMMIT_TIME to "commit_time",
        EventStreamFields.COMMAND_ID to "command_id",
        EventStreamFields.REQUEST_ID to "request_id",
        EventStreamFields.COMMAND_TYPE to "command_type",
        EventStreamFields.OWNER_ID to "owner_id",
        EventStreamFields.SPACE_ID to "space_id",
        EventStreamFields.OPERATOR to "operator",
        EventStreamFields.EVENTS to "events",
    )
    private val LOGICAL: Map<String, String> = PHYSICAL.entries.associate { (logical, physical) -> physical to logical }

    private const val PAYLOAD = "payload"
    private val EVENTS = QueryPathSegment.Property(EventStreamFields.EVENTS)
    private val BODY = listOf(EVENTS, QueryPathSegment.Item, QueryPathSegment.Property(EventFields.BODY))
    private val STORED_BODY = listOf(EVENTS, QueryPathSegment.Item, QueryPathSegment.Property(PAYLOAD))
    private val nodes: JsonNodeFactory = JsonNodeFactory.instance

    override val systemTypes: Map<String, Set<String>> = mapOf(
        "aggregate_id" to STRING_TYPES,
        "tenant_id" to STRING_TYPES,
        "version" to setOf("int"),
        "commit_time" to setOf("long"),
        "command_id" to STRING_TYPES,
        "request_id" to STRING_TYPES,
        "command_type" to STRING_TYPES,
        "owner_id" to STRING_TYPES,
        "space_id" to STRING_TYPES,
        "operator" to OBJECT_TYPES,
        "events" to ARRAY_TYPES,
    )

    /** The tenant, the aggregate ID and the version: the unique key of a stream. */
    override val tieBreakers: List<String> = listOf("tenant_id", "aggregate_id", "version")

    override val edgeTieBreakers: List<String> = listOf("aggregate_id", "tenant_id", "version")

    override fun physical(path: QueryPathTemplate): QueryPathTemplate {
        val renamed = renameRoot(path, PHYSICAL)
        return if (renamed.segments.take(BODY.size) == BODY) {
            QueryPathTemplate(STORED_BODY + renamed.segments.drop(BODY.size))
        } else {
            renamed
        }
    }

    override fun logical(path: QueryPathTemplate): QueryPathTemplate {
        val renamed = renameRoot(path, LOGICAL)
        return if (renamed.segments.take(STORED_BODY.size) == STORED_BODY) {
            QueryPathTemplate(BODY + renamed.segments.drop(STORED_BODY.size))
        } else {
            renamed
        }
    }

    override fun record(document: Document): ObjectNode = nodes.objectNode().apply {
        PHYSICAL.forEach { (logical, physical) ->
            if (!document.containsKey(physical)) {
                // A stream of an anonymous caller stores no operator; one without a scope stores null.
                if (logical == EventStreamFields.OPERATOR && document.containsKey("tenant_id")) putNull(logical)
                return@forEach
            }
            when (logical) {
                EventStreamFields.EVENTS -> {
                    val events = putArray(logical)
                    document.getList(physical, Document::class.java).forEach { event ->
                        events.addObject()
                            .put(EventFields.NAME, event.getString(EventFields.NAME))
                            .put(EventFields.REVISION, event.getInteger(EventFields.REVISION))
                            .set(EventFields.BODY, ExactBson.node(event[PAYLOAD]))
                    }
                }
                EventStreamFields.OPERATOR -> set(
                    logical,
                    MongoOperators.of(document.get(physical, Document::class.java))?.let { operator ->
                        nodes.objectNode()
                            .put(MongoOperators.ID, operator.id)
                            .put(MongoOperators.TENANT_ID, operator.tenantId)
                            .put(MongoOperators.CROSS_SCOPE, operator.crossScope)
                    } ?: nodes.nullNode(),
                )
                else -> set(logical, ExactBson.node(document[physical]))
            }
        }
    }
}

/**
 * What a log collection proves about an `events` query model (decision D235), for the collection `MongoEventLog`
 * creates (it declares no validator): the BSON types the log writes for each system field
 * ([MongoEventDocuments.systemTypes]), each logical path bound to its document path ([MongoEventDocuments.physical]),
 * compiled as a snapshot collection's facts are ([MongoQuerySchemaAdapter.facts]).
 */
internal object MongoEventStorageAdapter : QueryStorageAdapter {
    override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts =
        MongoQuerySchemaAdapter.facts(logicalSchema, emptyList(), null, documents = MongoEventDocuments)
}

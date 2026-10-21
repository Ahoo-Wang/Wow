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

package me.ahoo.wow.migrate

import com.mongodb.ErrorCategory
import com.mongodb.MongoBulkWriteException
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.InsertManyOptions
import org.bson.Document
import org.bson.types.Decimal128
import tools.jackson.databind.JsonNode
import java.util.concurrent.ConcurrentHashMap

/**
 * The 9.x MongoDB event store of [database] written as 9.x's `MongoEventStore` writes it: one document per stream in
 * `<aggregateName>_event_stream`, the stream's id as `_id`, its event count as `size`, each event with the `bodyType`
 * 9.x recorded for its name and revision (9.x resolves an event's class by its name first, so a type it never stored
 * keeps the event's name), decimals as `Decimal128` so they read back exact.
 *
 * A stream is written only into a collection that has 9.x's unique `(aggregateId, version)` index, which 9.x's schema
 * initializer creates: the index is what makes a stream the store already holds a duplicate instead of a second copy.
 */
class MongoNineXEventTarget(private val database: MongoDatabase) : NineXEventTarget {
    private val bodyTypes = ConcurrentHashMap<NineXAggregate, Map<NineXEventType, String>>()
    private val indexed = ConcurrentHashMap.newKeySet<NineXAggregate>()

    override fun stored(
        aggregate: NineXAggregate,
        keys: Collection<NineXStreamKey>,
    ): Map<NineXStreamKey, NineXStoredStream> {
        if (keys.isEmpty()) {
            return emptyMap()
        }
        val filter = Filters.or(
            keys.map { Filters.and(Filters.eq(AGGREGATE_ID, it.aggregateId), Filters.eq(VERSION, it.version)) }
        )
        return collectionOf(aggregate).find(filter)
            .projection(Document(AGGREGATE_ID, 1).append(VERSION, 1).append(TENANT_ID, 1).append(COMMAND_ID, 1))
            .associate { document ->
                NineXStreamKey(document.getString(AGGREGATE_ID), (document.get(VERSION) as Number).toInt()) to
                    NineXStoredStream(document.getString(TENANT_ID), document.getString(COMMAND_ID))
            }
    }

    override fun insert(aggregate: NineXAggregate, streams: List<NineXStream>): Set<NineXStreamKey> {
        if (streams.isEmpty()) {
            return emptySet()
        }
        requireUniqueIndex(aggregate)
        val types = bodyTypes.computeIfAbsent(aggregate, ::bodyTypesOf)
        return try {
            collectionOf(
                aggregate
            ).insertMany(streams.map { documentOf(it, types) }, InsertManyOptions().ordered(false))
            emptySet()
        } catch (failure: MongoBulkWriteException) {
            val duplicates = failure.writeErrors.filter { ErrorCategory.fromErrorCode(it.code) == ErrorCategory.DUPLICATE_KEY }
            if (duplicates.size != failure.writeErrors.size) {
                throw failure
            }
            duplicates.map {
                streams[it.index].let { stream ->
                    NineXStreamKey(
                        stream.aggregateId.id,
                        stream.version
                    )
                }
            }.toSet()
        }
    }

    private fun requireUniqueIndex(aggregate: NineXAggregate) {
        if (aggregate in indexed) {
            return
        }
        val collection = collectionOf(aggregate)
        val unique = collection.listIndexes().any { index ->
            index.getBoolean("unique", false) &&
                index.get("key", Document::class.java)?.keys?.toList() == listOf(AGGREGATE_ID, VERSION)
        }
        if (!unique) {
            throw MigrationException(
                "The 9.x collection ${collection.namespace.collectionName} has no unique (aggregateId, version) index, " +
                    "which 9.x creates at its start: start 9.x once, or create it with " +
                    "db.getCollection(\"${collection.namespace.collectionName}\").createIndex(" +
                    "{ $AGGREGATE_ID: 1, $VERSION: 1 }, { unique: true }), then export again.",
            )
        }
        indexed += aggregate
    }

    override fun revisionsOf(aggregate: NineXAggregate, name: String): List<String> =
        bodyTypes.computeIfAbsent(aggregate, ::bodyTypesOf).keys.filter { it.name == name }.map { it.revision }

    override fun count(aggregate: NineXAggregate): Long =
        collectionOf(aggregate).countDocuments(Filters.eq(CONTEXT_NAME, aggregate.context))

    /** The `bodyType` 9.x last recorded for each event name and revision of [aggregate]. */
    private fun bodyTypesOf(aggregate: NineXAggregate): Map<NineXEventType, String> = collectionOf(aggregate).aggregate(
        listOf(
            Document("\$match", Document(CONTEXT_NAME, aggregate.context)),
            Document("\$unwind", "\$$BODY"),
            Document(
                "\$group",
                Document("_id", Document("name", "\$$BODY.name").append("revision", "\$$BODY.revision"))
                    .append(BODY_TYPE, Document("\$last", "\$$BODY.$BODY_TYPE")),
            ),
        ),
    ).mapNotNull { group ->
        val id = group.get("_id", Document::class.java)
        val type = group.getString(BODY_TYPE) ?: return@mapNotNull null
        NineXEventType(id.getString("name"), id.getString("revision") ?: NineXStream.DEFAULT_REVISION) to type
    }.toMap()

    private fun documentOf(stream: NineXStream, types: Map<NineXEventType, String>): Document = Document(ID, stream.id)
        .append(CONTEXT_NAME, stream.aggregate.context)
        .append("aggregateName", stream.aggregate.name)
        .append("header", Document(stream.header))
        .append(AGGREGATE_ID, stream.aggregateId.id)
        .append(TENANT_ID, stream.aggregateId.tenantId)
        .append("ownerId", stream.ownerId)
        .append("spaceId", stream.spaceId)
        .append(COMMAND_ID, stream.commandId)
        .append("requestId", stream.requestId)
        .append(VERSION, stream.version)
        .append(
            BODY,
            stream.events.map { event ->
                Document("id", event.id)
                    .append("name", event.name)
                    .append("revision", event.revision)
                    .append(BODY_TYPE, types[NineXEventType(event.name, event.revision)] ?: event.name)
                    .append(BODY, bsonOf(event.body))
            },
        )
        .append("createTime", stream.createTime)
        .append("size", stream.events.size)

    private fun collectionOf(aggregate: NineXAggregate): MongoCollection<Document> =
        database.getCollection(aggregate.name + MongoNineXEventSource.SUFFIX)

    companion object {
        private const val ID = "_id"
        private const val CONTEXT_NAME = "contextName"
        private const val AGGREGATE_ID = "aggregateId"
        private const val TENANT_ID = "tenantId"
        private const val COMMAND_ID = "commandId"
        private const val VERSION = "version"
        private const val BODY = "body"
        private const val BODY_TYPE = "bodyType"

        /** [node] as the BSON value 9.x writes for it: a decimal as an exact `Decimal128`, the inverse of [MongoNineXEventSource.toNode]. */
        fun bsonOf(node: JsonNode): Any? = when {
            node.isObject -> Document().also { document ->
                node.properties().forEach { (name, value) -> document.append(name, bsonOf(value)) }
            }

            node.isArray -> (0 until node.size()).map { bsonOf(node.get(it)) }
            node.isNull || node.isMissingNode -> null
            node.isBoolean -> node.booleanValue()
            node.isInt -> node.intValue()
            node.isLong -> node.longValue()
            node.isIntegralNumber -> Decimal128(node.decimalValue())
            node.isNumber -> Decimal128(node.decimalValue())
            else -> node.asString()
        }
    }
}

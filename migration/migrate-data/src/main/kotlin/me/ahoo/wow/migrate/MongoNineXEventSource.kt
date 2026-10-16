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

import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import me.ahoo.wow.spi.json.WowJson
import org.bson.BsonUndefined
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.Decimal128
import org.bson.types.ObjectId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.util.Date

/**
 * The 9.x MongoDB event store (9.x `wow-mongo`'s `MongoEventStore`) of one [database], read by its storage format:
 * one collection `<aggregateName>_event_stream` per aggregate, one document per stream, the stream's JSON with its
 * `id` as `_id`; 9.x wrote decimals as `Decimal128`, which read back exact.
 *
 * The full import walks an aggregate's version-1 documents by `(aggregateId, tenantId)`, and reads an instance's
 * streams through 9.x's unique `(aggregateId, version)` index. The tail reads by `(createTime, _id)`, which 9.x does
 * not index. The tail needs that index; [ensureCommitTimeIndex] creates it only when the operator opts in
 * (`source.createCommitTimeIndex`), the only write the tool can make to a 9.x store.
 */
class MongoNineXEventSource(private val database: MongoDatabase) : NineXEventSource, CommitTimeIndexed {
    override fun inventory(): List<NineXInventory> =
        database.listCollectionNames().filter { it.endsWith(SUFFIX) }.sorted().flatMap(::inventoryOf)

    private fun inventoryOf(collectionName: String): List<NineXInventory> {
        val collection = database.getCollection(collectionName)
        val events = collection.aggregate(
            listOf(
                Aggregates.unwind("\$body"),
                Document(
                    "\$group",
                    Document(
                        "_id",
                        Document("context", "\$contextName").append("aggregate", "\$aggregateName")
                            .append("name", "\$body.name").append("revision", "\$body.revision"),
                    ).append("count", Document("\$sum", 1)),
                ),
            ),
        ).map { it.get("_id", Document::class.java) to it.count() }.toList()
        val commands = collection.aggregate(
            listOf(
                Document(
                    "\$group",
                    Document(
                        "_id",
                        Document("context", "\$contextName").append("aggregate", "\$aggregateName")
                            .append("command", "\$header.${NineXHeaders.UPSTREAM_NAME}"),
                    ).append("count", Document("\$sum", 1)),
                ),
            ),
        ).map { it.get("_id", Document::class.java) to it.count() }.toList()
        val shapes = streamEventTypesOf(collection)
        return (events.map { it.first.aggregate() } + commands.map { it.first.aggregate() }).distinct().map { aggregate ->
            val ofAggregate = commands.filter { it.first.aggregate() == aggregate }
            NineXInventory(
                aggregate = aggregate,
                streams = ofAggregate.sumOf { it.second },
                events = events.filter { it.first.aggregate() == aggregate }.associate { (id, count) ->
                    NineXEventType(
                        id.getString("name"),
                        id.getString("revision") ?: NineXStream.DEFAULT_REVISION,
                    ) to count
                },
                commands = ofAggregate.mapNotNull { (id, count) ->
                    id.getString("command")?.takeIf { it.isNotBlank() }?.let { it to count }
                }.toMap(),
                streamsWithoutCommand = ofAggregate.filter { it.first.getString("command").isNullOrBlank() }
                    .sumOf { it.second },
                streamEventTypes = shapes[aggregate].orEmpty(),
            )
        }
    }

    /**
     * The number of streams by the set of event types each holds, per aggregate: grouped by the store on the set
     * `$setUnion` makes, whose order MongoDB does not fix, so equal sets are merged here.
     */
    private fun streamEventTypesOf(
        collection: MongoCollection<Document>,
    ): Map<NineXAggregate, Map<Set<NineXEventType>, Long>> {
        val types = Document(
            "\$setUnion",
            listOf(
                Document(
                    "\$map",
                    Document("input", "\$body").append(
                        "in",
                        Document("name", "\$\$this.name").append("revision", "\$\$this.revision"),
                    ),
                ),
            ),
        )
        return collection.aggregate(
            listOf(
                Document(
                    "\$group",
                    Document(
                        "_id",
                        Document("context", "\$contextName").append("aggregate", "\$aggregateName")
                            .append("types", types),
                    ).append("count", Document("\$sum", 1)),
                ),
            ),
        ).map { it.get("_id", Document::class.java) to it.count() }.toList()
            .groupBy({ it.first.aggregate() }) { (id, count) ->
                id.getList("types", Document::class.java).orEmpty().map { type ->
                    NineXEventType(type.getString("name"), type.getString("revision") ?: NineXStream.DEFAULT_REVISION)
                }.toSet() to count
            }
            .mapValues { (_, shapes) -> shapes.groupBy({ it.first }) { it.second }.mapValues { it.value.sum() } }
    }

    override fun aggregateIds(aggregate: NineXAggregate, after: NineXAggregateId?, limit: Int): List<NineXAggregateId> {
        val filters = mutableListOf(Filters.eq(CONTEXT_NAME, aggregate.context), Filters.eq(VERSION, 1))
        after?.let {
            filters += Filters.or(
                Filters.gt(AGGREGATE_ID, it.id),
                Filters.and(Filters.eq(AGGREGATE_ID, it.id), Filters.gt(TENANT_ID, it.tenantId)),
            )
        }
        return collectionOf(aggregate).find(Filters.and(filters))
            .sort(Sorts.ascending(AGGREGATE_ID, TENANT_ID))
            .projection(Document(AGGREGATE_ID, 1).append(TENANT_ID, 1))
            .limit(limit)
            .map { NineXAggregateId(it.getString(AGGREGATE_ID), it.getString(TENANT_ID)) }
            .toList()
    }

    override fun streams(
        aggregate: NineXAggregate,
        id: NineXAggregateId,
        fromVersion: Int,
        limit: Int,
    ): List<NineXStream> = read(
        aggregate,
        Filters.and(
            Filters.eq(AGGREGATE_ID, id.id),
            Filters.eq(TENANT_ID, id.tenantId),
            Filters.eq(CONTEXT_NAME, aggregate.context),
            Filters.gte(VERSION, fromVersion),
        ),
        Sorts.ascending(VERSION),
        limit,
    )

    override fun committedSince(
        aggregate: NineXAggregate,
        from: Long,
        after: NineXPosition?,
        limit: Int,
    ): List<NineXStream> {
        val since = if (after == null) {
            Filters.gte(CREATE_TIME, from)
        } else {
            Filters.or(
                Filters.gt(CREATE_TIME, after.createTime),
                Filters.and(Filters.eq(CREATE_TIME, after.createTime), Filters.gt(ID, after.streamId)),
            )
        }
        return read(
            aggregate,
            Filters.and(Filters.eq(CONTEXT_NAME, aggregate.context), since),
            Sorts.ascending(CREATE_TIME, ID),
            limit,
        )
    }

    override fun missingCommitTimeIndexes(aggregates: Collection<NineXAggregate>): Map<String, String> =
        aggregates.map { it.name + SUFFIX }.distinct().filterNot { name ->
            database.getCollection(name).listIndexes().any { isCommitTimeKey(it.get("key", Document::class.java)) }
        }.associateWith { name ->
            "db.getCollection(\"$name\").createIndex({ $CREATE_TIME: 1, $ID: 1 })"
        }

    /** Adds the `(createTime, _id)` index the tail reads [aggregate]'s collection by, when it is missing. */
    override fun ensureCommitTimeIndex(aggregate: NineXAggregate) {
        collectionOf(aggregate).createIndex(Indexes.ascending(CREATE_TIME, ID))
    }

    private fun read(aggregate: NineXAggregate, filter: Bson, sort: Bson, limit: Int): List<NineXStream> =
        collectionOf(aggregate).find(filter).sort(sort).limit(limit).map { NineXStream.parse(toNode(it)) }.toList()

    private fun collectionOf(aggregate: NineXAggregate): MongoCollection<Document> =
        database.getCollection(aggregate.name + SUFFIX)

    private fun Document.aggregate(): NineXAggregate = NineXAggregate(getString("context"), getString("aggregate"))

    private fun Document.count(): Long = (get("count") as Number).toLong()

    companion object {
        /** The suffix of a 9.x event stream collection. */
        const val SUFFIX: String = "_event_stream"
        private const val ID = "_id"
        private const val CONTEXT_NAME = "contextName"
        private const val AGGREGATE_ID = "aggregateId"
        private const val TENANT_ID = "tenantId"
        private const val VERSION = "version"
        private const val CREATE_TIME = "createTime"

        /**
         * Whether an index [key] is `{createTime: 1, _id: 1}`, however its numbers were written (the shell writes
         * doubles, the driver integers).
         */
        fun isCommitTimeKey(key: Document?): Boolean =
            key != null && key.keys.toList() == listOf(CREATE_TIME, ID) &&
                key.values.all { (it as? Number)?.toDouble() == 1.0 }
        private val NODES: JsonNodeFactory = WowJson.mapper.nodeFactory

        /** [value], a BSON value the driver read, as JSON; a `Decimal128` stays an exact decimal. */
        fun toNode(value: Any?): JsonNode = when (value) {
            null, is BsonUndefined -> NODES.nullNode()
            is Map<*, *> -> NODES.objectNode().also { node ->
                value.forEach { (key, item) -> node.set(key.toString(), toNode(item)) }
            }

            is Collection<*> -> NODES.arrayNode().also { node -> value.forEach { node.add(toNode(it)) } }
            is String -> NODES.textNode(value)
            is Boolean -> NODES.booleanNode(value)
            is Number -> numberNode(value)
            is Date -> NODES.numberNode(value.time)
            is ObjectId -> NODES.textNode(value.toHexString())
            else -> NODES.textNode(value.toString())
        }

        private fun numberNode(value: Number): JsonNode = when (value) {
            is Decimal128 -> NODES.numberNode(value.bigDecimalValue())
            is Int -> NODES.numberNode(value)
            is Long -> NODES.numberNode(value)
            else -> NODES.numberNode(value.toDouble())
        }
    }
}

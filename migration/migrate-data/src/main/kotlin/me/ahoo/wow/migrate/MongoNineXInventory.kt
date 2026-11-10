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
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.migrate.NineXMongoFields.AGGREGATE_ID
import me.ahoo.wow.migrate.NineXMongoFields.AGGREGATE_NAME
import me.ahoo.wow.migrate.NineXMongoFields.BODY
import me.ahoo.wow.migrate.NineXMongoFields.CONTEXT_NAME
import me.ahoo.wow.migrate.NineXMongoFields.EVENT_NAME
import me.ahoo.wow.migrate.NineXMongoFields.EVENT_REVISION
import me.ahoo.wow.migrate.NineXMongoFields.HEADER
import me.ahoo.wow.migrate.NineXMongoFields.TENANT_ID
import org.bson.Document

/**
 * What the mapping check and `draft-map` count of a 9.x MongoDB event store ([MongoNineXEventSource.inventory]), one
 * collection of [database] at a time: events by type, commands, streams without a command, the sets of event types
 * streams hold, the instances whose id V10 refuses, and those that record no tenant.
 */
internal class MongoNineXInventory(private val database: MongoDatabase) {
    fun inventoryOf(collectionName: String): List<NineXInventory> {
        val collection = database.getCollection(collectionName)
        val events = collection.aggregate(
            listOf(
                Aggregates.unwind("\$$BODY"),
                Document(
                    "\$group",
                    Document(
                        "_id",
                        Document("context", "\$$CONTEXT_NAME").append("aggregate", "\$$AGGREGATE_NAME")
                            .append("name", "\$$BODY.$EVENT_NAME").append("revision", "\$$BODY.$EVENT_REVISION"),
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
                        Document("context", "\$$CONTEXT_NAME").append("aggregate", "\$$AGGREGATE_NAME")
                            .append("command", COMMAND_OF_STREAM),
                    ).append("count", Document("\$sum", 1)),
                ),
            ),
        ).map { it.get("_id", Document::class.java) to it.count() }.toList()
        val shapes = streamEventTypesOf(collection)
        val refused = refusedIdsOf(collection)
        val tenantless = tenantlessOf(collection)
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
                streamEventTypes = shapes.getValue(aggregate),
                refusedIds = refused[aggregate].orEmpty(),
                tenantless = tenantless[aggregate].orEmpty(),
            )
        }
    }

    /**
     * The number of streams of each instance whose id V10 refuses ([RefusedIds]), per aggregate: an absent or null id
     * read as the empty one, as [NineXStream.parse] reads it.
     */
    private fun refusedIdsOf(collection: MongoCollection<Document>): Map<NineXAggregate, Map<NineXAggregateId, Long>> =
        collection.aggregate(
            listOf(
                Aggregates.match(
                    Filters.or(
                        Filters.eq(AGGREGATE_ID, null),
                        Filters.regex(AGGREGATE_ID, "^\\s*$"),
                        Filters.eq(AGGREGATE_ID, AggregateRef.UNASSIGNED),
                    ),
                ),
                Document(
                    "\$group",
                    Document(
                        "_id",
                        Document("context", "\$$CONTEXT_NAME").append("aggregate", "\$$AGGREGATE_NAME")
                            .append("id", Document("\$ifNull", listOf("\$$AGGREGATE_ID", "")))
                            .append("tenant", "\$$TENANT_ID"),
                    ).append("count", Document("\$sum", 1)),
                ),
            ),
        ).map { it.get("_id", Document::class.java) to it.count() }.toList()
            .groupBy({ it.first.aggregate() }) { (id, count) ->
                NineXAggregateId(id.getString("id"), id.getString("tenant").orEmpty()) to count
            }
            .mapValues { (_, instances) -> instances.toMap() }

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
                    Document("input", "\$$BODY").append(
                        "in",
                        Document("name", "\$\$this.$EVENT_NAME").append("revision", "\$\$this.$EVENT_REVISION"),
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
                        Document("context", "\$$CONTEXT_NAME").append("aggregate", "\$$AGGREGATE_NAME")
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

    /** The number of streams of each 9.x aggregate id that record no tenant, or a blank one, per aggregate. */
    private fun tenantlessOf(collection: MongoCollection<Document>): Map<NineXAggregate, Map<String, Long>> =
        collection.aggregate(
            listOf(
                Aggregates.match(Filters.or(Filters.eq(TENANT_ID, null), Filters.regex(TENANT_ID, "^\\s*$"))),
                Document(
                    "\$group",
                    Document(
                        "_id",
                        Document("context", "\$$CONTEXT_NAME").append("aggregate", "\$$AGGREGATE_NAME")
                            .append("id", Document("\$ifNull", listOf("\$$AGGREGATE_ID", ""))),
                    ).append("count", Document("\$sum", 1)),
                ),
            ),
        ).map { it.get("_id", Document::class.java) to it.count() }.toList()
            .groupBy({ it.first.aggregate() }) { (id, count) -> id.getString("id") to count }
            .mapValues { (_, ids) -> ids.toMap() }

    private fun Document.aggregate(): NineXAggregate = NineXAggregate(getString("context"), getString("aggregate"))

    private fun Document.count(): Long = (get("count") as Number).toLong()

    private companion object {
        /** The headers naming a stream's command, the first one written winning. */
        private val COMMAND_HEADERS =
            listOf(NineXHeaders.UPSTREAM_NAME, NineXHeaders.DOTTED_UPSTREAM_NAME, NineXHeaders.COMMAND_NAME)

        /**
         * A stream's command name, as [NineXStream.commandName] reads it: `upstream_name`, else an older 9.x's
         * `upstream.name`, else its `command.name`. Field names with dots are no paths, so the header is read as its
         * pairs; missing when it names none.
         */
        private val COMMAND_OF_STREAM: Document = Document(
            "\$let",
            Document(
                "vars",
                Document("pairs", Document("\$objectToArray", Document("\$ifNull", listOf("\$$HEADER", Document())))),
            ).append(
                "in",
                Document(
                    "\$arrayElemAt",
                    listOf(
                        Document(
                            "\$concatArrays",
                            COMMAND_HEADERS.map { key ->
                                Document(
                                    "\$map",
                                    Document(
                                        "input",
                                        Document(
                                            "\$filter",
                                            Document("input", "\$\$pairs")
                                                .append("cond", Document("\$eq", listOf("\$\$this.k", key))),
                                        ),
                                    ).append("in", "\$\$this.v"),
                                )
                            },
                        ),
                        0,
                    ),
                ),
            ),
        )
    }
}

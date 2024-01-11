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

package me.ahoo.wow.mongo

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.BsonField
import com.mongodb.client.model.Filters
import com.mongodb.client.model.MergeOptions
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AbstractEventStore
import me.ahoo.wow.eventsourcing.AggregateIdScanner
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.mongo.AggregateSchemaInitializer.AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME
import me.ahoo.wow.mongo.AggregateSchemaInitializer.REQUEST_ID_UNIQUE_INDEX_NAME
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toAggregateIdCollectionName
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.mongo.Documents.replaceIdToPrimaryKey
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyToId
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.bson.Document
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

class MongoEventStore(private val database: MongoDatabase) : AbstractEventStore() {
    companion object {
        private const val SIZE_FIELD = "size"
        private val DEFAULT_MERGE_OPTIONS = MergeOptions()
            .uniqueIdentifier(Documents.ID_FIELD)
            .whenMatched(MergeOptions.WhenMatched.KEEP_EXISTING)
            .whenNotMatched(MergeOptions.WhenNotMatched.INSERT)
    }

    override fun appendStream(eventStream: DomainEventStream): Mono<Void> {
        val eventStreamCollectionName = eventStream.toEventStreamCollectionName()
        val eventStreamJson = eventStream.toJsonString()
        val document = Document.parse(eventStreamJson)
            .replaceIdToPrimaryKey()
            .append(SIZE_FIELD, eventStream.size)

        return database.getCollection(eventStreamCollectionName)
            .insertOne(document)
            .toMono()
            .doOnNext {
                check(it.wasAcknowledged())
            }.onErrorMap(MongoWriteException::class.java) {
                if (ErrorCategory.fromErrorCode(it.code) != ErrorCategory.DUPLICATE_KEY) {
                    return@onErrorMap it
                }
                if (it.message!!.contains(AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME)) {
                    return@onErrorMap EventVersionConflictException(
                        eventStream,
                        it,
                    )
                }
                if (it.message!!.contains(REQUEST_ID_UNIQUE_INDEX_NAME)) {
                    return@onErrorMap DuplicateRequestIdException(
                        aggregateId = eventStream.aggregateId,
                        requestId = eventStream.requestId,
                        cause = it,
                    )
                }
                it
            }.then()
    }

    override fun loadStream(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> {
        val eventStreamCollectionName = aggregateId.toEventStreamCollectionName()
        val limit = tailVersion - headVersion + 1
        return database.getCollection(eventStreamCollectionName)
            .find(
                Filters.and(
                    Filters.eq(MessageRecords.AGGREGATE_ID, aggregateId.id),
                    Filters.gte(MessageRecords.VERSION, headVersion),
                    Filters.lte(MessageRecords.VERSION, tailVersion),
                ),
            )
            .limit(limit)
            .batchSize(limit)
            .toFlux()
            .map {
                val domainEventStream = it.replacePrimaryKeyToId().toJson().toObject<DomainEventStream>()
                require(domainEventStream.aggregateId == aggregateId) {
                    "aggregateId is not match! aggregateId: $aggregateId, domainEventStream: ${domainEventStream.aggregateId}"
                }

                domainEventStream
            }
    }

    override fun tailCursorId(namedAggregate: NamedAggregate): Mono<String> {
        val aggregateIdCollectionName = namedAggregate.toAggregateIdCollectionName()
        return database.getCollection(aggregateIdCollectionName)
            .find()
            .sort(Sorts.descending(Documents.ID_FIELD))
            .projection(Projections.include(Documents.ID_FIELD))
            .limit(1)
            .first()
            .toMono()
            .map {
                it.getString(Documents.ID_FIELD)
            }.switchIfEmpty(Mono.just(AggregateIdScanner.FIRST_CURSOR_ID))
    }

    override fun archiveAggregateId(namedAggregate: NamedAggregate): Mono<Void> {
        return tailCursorId(namedAggregate)
            .flatMap {
                archiveAggregateId(namedAggregate, it)
            }
    }

    override fun archiveAggregateId(namedAggregate: NamedAggregate, tailCursorId: String): Mono<Void> {
        val aggregateIdCollectionName = namedAggregate.toAggregateIdCollectionName()
        val eventStreamCollectionName = namedAggregate.toEventStreamCollectionName()
        return database.getCollection(eventStreamCollectionName)
            .aggregate(
                listOf(
                    Aggregates.match(Filters.gt(MessageRecords.AGGREGATE_ID, tailCursorId)),
                    Aggregates.project(
                        Projections.fields(
                            Projections.excludeId(),
                            Projections.include(MessageRecords.AGGREGATE_ID, MessageRecords.TENANT_ID),
                        )
                    ),
                    Aggregates.group(
                        "\$${MessageRecords.AGGREGATE_ID}",
                        BsonField(MessageRecords.TENANT_ID, Document("\$first", "\$${MessageRecords.TENANT_ID}")),
                    ),
                    Aggregates.sort(Sorts.ascending(Documents.ID_FIELD)),
                    Aggregates.merge(aggregateIdCollectionName, DEFAULT_MERGE_OPTIONS),
                ),
            )
            .toCollection()
            .toMono()
    }

    override fun scanAggregateId(namedAggregate: NamedAggregate, cursorId: String, limit: Int): Flux<AggregateId> {
        return archiveAggregateId(namedAggregate)
            .thenMany(findAggregateId(namedAggregate, cursorId, limit))
    }

    private fun findAggregateId(
        namedAggregate: NamedAggregate,
        cursorId: String,
        limit: Int
    ): Flux<AggregateId> {
        val aggregateIdCollectionName = namedAggregate.toAggregateIdCollectionName()
        return database.getCollection(aggregateIdCollectionName)
            .find(Filters.gt(Documents.ID_FIELD, cursorId))
            .sort(Sorts.ascending(Documents.ID_FIELD))
            .limit(limit)
            .batchSize(limit)
            .toFlux()
            .map {
                namedAggregate.aggregateId(
                    id = it.getString(Documents.ID_FIELD),
                    tenantId = it.getString(MessageRecords.TENANT_ID),
                )
            }
    }
}

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
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AbstractEventStore
import me.ahoo.wow.eventsourcing.EventVersionConflictException
import me.ahoo.wow.mongo.AggregateSchemaInitializer.AGGREGATE_ID_AND_VERSION_UNIQUE_INDEX_NAME
import me.ahoo.wow.mongo.AggregateSchemaInitializer.REQUEST_ID_UNIQUE_INDEX_NAME
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.mongo.Documents.replaceIdToPrimaryKey
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyToId
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObject
import org.bson.Document
import org.bson.conversions.Bson
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

class MongoEventStore(private val database: MongoDatabase) : AbstractEventStore() {
    companion object {
        private const val SIZE_FIELD = "size"
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
                        eventStream = eventStream,
                        cause = it,
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

    private fun findStream(aggregateId: AggregateId, filter: Bson): Flux<DomainEventStream> {
        val eventStreamCollectionName = aggregateId.toEventStreamCollectionName()
        return database.getCollection(eventStreamCollectionName)
            .find(filter)
            .sort(Sorts.ascending(MessageRecords.VERSION))
            .toFlux()
            .map {
                val domainEventStream = it.replacePrimaryKeyToId().toJson().toObject<DomainEventStream>()
                require(domainEventStream.aggregateId == aggregateId) {
                    "aggregateId is not match! aggregateId: $aggregateId, domainEventStream: ${domainEventStream.aggregateId}"
                }
                domainEventStream
            }
    }

    override fun loadStream(aggregateId: AggregateId, headVersion: Int, tailVersion: Int): Flux<DomainEventStream> {
        return findStream(
            aggregateId = aggregateId,
            filter = Filters.and(
                Filters.eq(MessageRecords.AGGREGATE_ID, aggregateId.id),
                Filters.gte(MessageRecords.VERSION, headVersion),
                Filters.lte(MessageRecords.VERSION, tailVersion),
            )
        )
    }

    override fun loadStream(
        aggregateId: AggregateId,
        headEventTime: Long,
        tailEventTime: Long
    ): Flux<DomainEventStream> {
        return findStream(
            aggregateId = aggregateId,
            filter = Filters.and(
                Filters.eq(MessageRecords.AGGREGATE_ID, aggregateId.id),
                Filters.gte(MessageRecords.CREATE_TIME, headEventTime),
                Filters.lte(MessageRecords.CREATE_TIME, tailEventTime),
            )
        )
    }
}

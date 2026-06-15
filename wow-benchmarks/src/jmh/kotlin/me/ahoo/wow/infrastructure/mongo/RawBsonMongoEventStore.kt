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

package me.ahoo.wow.infrastructure.mongo

import com.mongodb.MongoWriteException
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.AbstractEventStore
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.mongo.MongoEventStore
import me.ahoo.wow.mongo.toWowError
import org.bson.RawBsonDocument
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class RawBsonMongoEventStore(private val database: MongoDatabase) : AbstractEventStore() {
    private val mongoEventStore = MongoEventStore(database)

    @Suppress("ForbiddenVoid")
    override fun appendStream(eventStream: DomainEventStream): Mono<Void> {
        val eventStreamCollectionName = eventStream.toEventStreamCollectionName()
        val rawDocument = RawBsonEventStreamRecords.toRawBsonDocument(eventStream)

        return database.getCollection(eventStreamCollectionName, RawBsonDocument::class.java)
            .insertOne(rawDocument)
            .toMono()
            .doOnNext {
                check(it.wasAcknowledged())
            }.onErrorMap(MongoWriteException::class.java) {
                it.toWowError(eventStream)
            }.then()
    }

    override fun loadStream(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int
    ): Flux<DomainEventStream> {
        return mongoEventStore.load(aggregateId, headVersion, tailVersion)
    }

    override fun loadStream(
        aggregateId: AggregateId,
        headEventTime: Long,
        tailEventTime: Long
    ): Flux<DomainEventStream> {
        return mongoEventStore.load(aggregateId, headEventTime, tailEventTime)
    }

    override fun last(aggregateId: AggregateId): Mono<DomainEventStream> {
        return mongoEventStore.last(aggregateId)
    }
}

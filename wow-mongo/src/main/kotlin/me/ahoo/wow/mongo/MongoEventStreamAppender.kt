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

import com.mongodb.MongoWriteException
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

internal interface MongoEventStreamAppender : AutoCloseable {
    fun append(eventStream: DomainEventStream): Mono<Void>

    override fun close() = Unit
}

internal class DirectMongoEventStreamAppender(
    private val database: MongoDatabase,
) : MongoEventStreamAppender {
    override fun append(eventStream: DomainEventStream): Mono<Void> {
        val eventStreamCollectionName = eventStream.toEventStreamCollectionName()
        val document = eventStream.toDocument()
        return database.getCollection(eventStreamCollectionName)
            .insertOne(document)
            .toMono()
            .doOnNext {
                check(it.wasAcknowledged())
            }.onErrorMap(MongoWriteException::class.java) {
                it.toWowError(eventStream)
            }.then()
    }
}

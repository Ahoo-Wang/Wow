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

package me.ahoo.wow.mongo.query.event

import com.mongodb.reactivestreams.client.FindPublisher
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyToId
import me.ahoo.wow.mongo.query.MongoConditionConverter
import me.ahoo.wow.mongo.query.findDocument
import me.ahoo.wow.query.converter.ConditionConverter
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.serialization.toObject
import org.bson.Document
import org.bson.conversions.Bson
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

class MongoEventStreamQueryService(
    override val namedAggregate: NamedAggregate,
    private val collection: MongoCollection<Document>,
    private val converter: ConditionConverter<Bson> = MongoConditionConverter
) : EventStreamQueryService {
    private fun findDocument(queryable: Queryable<*>): FindPublisher<Document> {
        return collection.findDocument(converter, queryable)
    }

    override fun list(listQuery: IListQuery): Flux<DomainEventStream> {
        return findDocument(listQuery)
            .limit(listQuery.limit)
            .toFlux()
            .map {
                it.replacePrimaryKeyToId().toJson().toObject<DomainEventStream>()
            }
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
        return findDocument(listQuery)
            .limit(listQuery.limit)
            .toFlux()
            .map {
                it.replacePrimaryKeyToId().toDynamicDocument()
            }
    }

    override fun count(condition: Condition): Mono<Long> {
        val filter = converter.convert(condition)
        return collection.countDocuments(filter).toMono()
    }
}

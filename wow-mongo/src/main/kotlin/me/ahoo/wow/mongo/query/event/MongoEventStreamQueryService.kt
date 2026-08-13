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

import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyToId
import me.ahoo.wow.mongo.query.AbstractMongoQueryService
import me.ahoo.wow.mongo.query.MongoProjectionConverter
import me.ahoo.wow.mongo.query.MongoSortConverter
import me.ahoo.wow.mongo.toDomainEventStream
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.converter.ConditionConverter
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.GatewayEventStreamQueryService
import org.bson.Document
import org.bson.conversions.Bson

class MongoEventStreamQueryService private constructor(
    override val namedAggregate: NamedAggregate,
    override val collection: MongoCollection<Document>,
    override val converter: ConditionConverter<Bson>,
    queryService: QueryService<DomainEventStream>?
) : AbstractMongoQueryService<DomainEventStream>(queryService), EventStreamQueryService {
    @Deprecated("Use the constructor that requires QueryGateway.")
    constructor(
        namedAggregate: NamedAggregate,
        collection: MongoCollection<Document>,
        converter: ConditionConverter<Bson> = EventStreamConditionConverter
    ) : this(namedAggregate, collection, converter, null)

    constructor(
        namedAggregate: NamedAggregate,
        collection: MongoCollection<Document>,
        queryGateway: QueryGateway
    ) : this(
        namedAggregate,
        collection,
        EventStreamConditionConverter,
        GatewayEventStreamQueryService(namedAggregate, queryGateway)
    )

    override val projectionConverter: MongoProjectionConverter = MongoProjectionConverter(EventStreamFieldConverter)
    override val sortConverter: MongoSortConverter = MongoSortConverter(EventStreamFieldConverter)
    override fun toTypedResult(document: Document): DomainEventStream {
        return document.toDomainEventStream()
    }

    override fun toDynamicDocument(document: Document): DynamicDocument {
        return document.replacePrimaryKeyToId().toDynamicDocument()
    }
}

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

package me.ahoo.wow.mongo.query.snapshot

import com.fasterxml.jackson.databind.type.TypeFactory
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.mongo.Documents.replacePrimaryKeyToAggregateId
import me.ahoo.wow.mongo.MongoSnapshotRepository
import me.ahoo.wow.mongo.query.AbstractMongoQueryService
import me.ahoo.wow.mongo.query.MongoProjectionConverter
import me.ahoo.wow.mongo.query.MongoSortConverter
import me.ahoo.wow.mongo.toMaterializedSnapshot
import me.ahoo.wow.query.converter.ConditionConverter
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import org.bson.Document
import org.bson.conversions.Bson

class MongoSnapshotQueryService<S : Any>(
    override val namedAggregate: NamedAggregate,
    override val collection: MongoCollection<Document>,
    override val converter: ConditionConverter<Bson> = SnapshotConditionConverter
) : AbstractMongoQueryService<MaterializedSnapshot<S>>(), SnapshotQueryService<S> {
    override val name: String
        get() = MongoSnapshotRepository.NAME
    override val projectionConverter: MongoProjectionConverter = MongoProjectionConverter(SnapshotFieldConverter)
    override val sortConverter: MongoSortConverter = MongoSortConverter(SnapshotFieldConverter)
    private val snapshotType = TypeFactory.defaultInstance()
        .constructParametricType(
            MaterializedSnapshot::class.java,
            namedAggregate.requiredAggregateType<Any>().aggregateMetadata<Any, S>().state.aggregateType
        )

    override fun toTypedResult(document: Document): MaterializedSnapshot<S> {
        return document.toMaterializedSnapshot(snapshotType)
    }

    override fun toDynamicDocument(document: Document): DynamicDocument {
        return document.replacePrimaryKeyToAggregateId().toDynamicDocument()
    }
}

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

package me.ahoo.wow.mongo.query

import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryService
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryServiceFactory
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryService
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryServiceFactory
import me.ahoo.wow.query.converter.ConditionConverter
import org.bson.Document
import org.bson.conversions.Bson

class LegacyMongoQueryApiSourceCompatibilityTest {
    @Suppress("Unused")
    private fun compileOnly(
        namedAggregate: NamedAggregate,
        collection: MongoCollection<Document>,
        database: MongoDatabase,
        conditionConverter: ConditionConverter<Bson>,
        projectionConverter: MongoProjectionConverter,
        sortConverter: MongoSortConverter,
        queryable: me.ahoo.wow.api.query.Queryable<*>,
    ) {
        MongoSnapshotQueryService<Any>(namedAggregate, collection)
        MongoEventStreamQueryService(namedAggregate, collection)
        MongoSnapshotQueryServiceFactory(database)
        MongoEventStreamQueryServiceFactory(database)
        collection.findDocument(conditionConverter, queryable, projectionConverter, sortConverter)
    }
}

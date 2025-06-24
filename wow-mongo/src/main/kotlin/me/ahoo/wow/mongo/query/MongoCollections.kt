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

import com.mongodb.reactivestreams.client.FindPublisher
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.query.converter.ConditionConverter
import org.bson.Document
import org.bson.conversions.Bson

fun MongoCollection<Document>.findDocument(
    converter: ConditionConverter<Bson>,
    queryable: Queryable<*>,
    projectionConverter: MongoProjectionConverter,
    sortConverter: MongoSortConverter
): FindPublisher<Document> {
    val projectionBson = projectionConverter.convert(queryable.projection)
    val filter = converter.convert(queryable.condition)
    val sort = sortConverter.convert(queryable.sort)
    return find(filter)
        .projection(projectionBson)
        .sort(sort)
}

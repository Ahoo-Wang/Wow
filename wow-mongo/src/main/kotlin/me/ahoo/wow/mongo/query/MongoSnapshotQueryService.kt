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
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.IQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.mongo.query.MongoFilterConverter.toMongoSort
import me.ahoo.wow.mongo.toSnapshot
import me.ahoo.wow.query.ConditionConverter
import me.ahoo.wow.query.SnapshotQueryService
import org.bson.Document
import org.bson.conversions.Bson
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

class MongoSnapshotQueryService<S : Any>(
    override val namedAggregate: NamedAggregate,
    private val collection: MongoCollection<Document>,
    private val converter: ConditionConverter<Bson> = MongoConditionConverter
) : SnapshotQueryService<S> {

    override fun single(condition: Condition): Mono<Snapshot<S>> {
        val filter = converter.convert(condition)
        return collection.find(filter)
            .limit(1)
            .first()
            .toMono()
            .toSnapshot()
    }

    override fun query(query: IQuery): Flux<Snapshot<S>> {
        val filter = converter.convert(query.condition)
        val sort = query.sort.toMongoSort()
        return collection.find(filter)
            .sort(sort)
            .limit(query.limit)
            .toFlux()
            .toSnapshot()
    }

    override fun pagedQuery(pagedQuery: IPagedQuery): Mono<PagedList<Snapshot<S>>> {
        val filter = converter.convert(pagedQuery.condition)
        val sort = pagedQuery.sort.toMongoSort()

        val totalPublisher = collection.countDocuments(filter).toMono()
        val listPublisher = collection.find(filter)
            .sort(sort)
            .skip(pagedQuery.pagination.offset())
            .limit(pagedQuery.pagination.size)
            .toFlux()
            .toSnapshot<S>()
            .collectList()
        return Mono.zip(totalPublisher, listPublisher)
            .map { result ->
                PagedList(result.t1, result.t2)
            }
    }

    override fun count(condition: Condition): Mono<Long> {
        val filter = converter.convert(condition)
        return collection.countDocuments(filter).toMono()
    }
}

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

import com.mongodb.client.model.Filters
import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.IQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.mongo.query.MongoFilterConverter.toMongoFilter
import me.ahoo.wow.mongo.query.MongoFilterConverter.toMongoSort
import me.ahoo.wow.mongo.toSnapshot
import me.ahoo.wow.query.SnapshotQueryService
import me.ahoo.wow.serialization.MessageRecords
import org.bson.Document
import org.bson.conversions.Bson
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

class MongoSnapshotQueryService<S : Any>(private val collection: MongoCollection<Document>) : SnapshotQueryService<S> {

    private fun Bson.withTenantId(tenantId: String): Bson {
        val tenantIdFilter = Filters.eq(MessageRecords.TENANT_ID, tenantId)
        return Filters.and(tenantIdFilter, this)
    }

    override fun single(tenantId: String, condition: Condition): Mono<Snapshot<S>> {
        val filter = condition.toMongoFilter().withTenantId(tenantId)
        return collection.find(filter)
            .limit(1)
            .first()
            .toMono()
            .toSnapshot()
    }

    override fun query(tenantId: String, query: IQuery): Flux<Snapshot<S>> {
        val filter = query.condition.toMongoFilter().withTenantId(tenantId)
        val sort = query.sort.toMongoSort()
        return collection.find(filter)
            .sort(sort)
            .limit(query.limit)
            .toFlux()
            .toSnapshot()
    }

    override fun pagedQuery(
        tenantId: String,
        pagedQuery: IPagedQuery
    ): Mono<PagedList<Snapshot<S>>> {
        val filter = pagedQuery.condition.toMongoFilter().withTenantId(tenantId)
        val sort = pagedQuery.sort.toMongoSort()

        val totalPublisher = collection.countDocuments(filter).toMono()
        val listPublisher = collection.find(filter)
            .sort(sort)
            .skip(pagedQuery.pagination.offset())
            .limit(pagedQuery.limit)
            .toFlux()
            .toSnapshot<S>()
            .collectList()
        return Mono.zip(totalPublisher, listPublisher)
            .map { result ->
                PagedList(result.t1, result.t2)
            }
    }

    override fun count(tenantId: String, condition: Condition): Mono<Long> {
        val filter = condition.toMongoFilter().withTenantId(tenantId)
        return collection.countDocuments(filter).toMono()
    }
}

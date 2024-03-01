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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

fun <S : Any> SnapshotQueryService<S>.single(tenantId: String, block: ConditionDsl.() -> Unit): Mono<Snapshot<S>> {
    val dsl = ConditionDsl()
    dsl.block()
    val condition = dsl.build()
    return this.single(tenantId, condition)
}

fun <S : Any> SnapshotQueryService<S>.query(tenantId: String, block: QueryDsl.() -> Unit): Flux<Snapshot<S>> {
    val dsl = QueryDsl()
    dsl.block()
    val query = dsl.build()
    return this.query(tenantId, query)
}

fun <S : Any> SnapshotQueryService<S>.pagedQuery(
    tenantId: String,
    block: PagedQueryDsl.() -> Unit
): Mono<PagedList<Snapshot<S>>> {
    val dsl = PagedQueryDsl()
    dsl.block()
    val pagedQuery = dsl.build()
    return this.pagedQuery(tenantId, pagedQuery)
}

fun SnapshotQueryService<*>.count(tenantId: String, block: ConditionDsl.() -> Unit): Mono<Long> {
    val dsl = ConditionDsl()
    dsl.block()
    val condition = dsl.build()
    return this.count(tenantId, condition)
}

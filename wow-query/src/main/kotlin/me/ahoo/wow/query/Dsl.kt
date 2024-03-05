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

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.IQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

fun query(block: QueryDsl.() -> Unit): Query {
    val dsl = QueryDsl()
    dsl.block()
    return dsl.build()
}

fun pagedQuery(block: PagedQueryDsl.() -> Unit): PagedQuery {
    val dsl = PagedQueryDsl()
    dsl.block()
    return dsl.build()
}

fun condition(block: ConditionDsl.() -> Unit): Condition {
    val dsl = ConditionDsl()
    dsl.block()
    return dsl.build()
}

fun pagination(block: PaginationDsl.() -> Unit): Pagination {
    val dsl = PaginationDsl()
    dsl.block()
    return dsl.build()
}

fun sort(block: SortDsl.() -> Unit): List<Sort> {
    val dsl = SortDsl()
    dsl.block()
    return dsl.build()
}

fun <S : Any> IQuery.query(queryService: SnapshotQueryService<S>): Flux<Snapshot<S>> {
    return queryService.query(this)
}

fun <S : Any> IPagedQuery.query(queryService: SnapshotQueryService<S>): Mono<PagedList<Snapshot<S>>> {
    return queryService.pagedQuery(this)
}

fun <S : Any> Condition.single(queryService: SnapshotQueryService<S>): Mono<Snapshot<S>> {
    return queryService.single(this)
}

fun Condition.count(queryService: SnapshotQueryService<*>): Mono<Long> {
    return queryService.count(this)
}

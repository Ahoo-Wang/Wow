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
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

fun singleQuery(block: SingleQueryDsl.() -> Unit): ISingleQuery {
    val dsl = SingleQueryDsl()
    dsl.block()
    return dsl.build()
}

fun listQuery(block: ListQueryDsl.() -> Unit): IListQuery {
    val dsl = ListQueryDsl()
    dsl.block()
    return dsl.build()
}

fun pagedQuery(block: PagedQueryDsl.() -> Unit): IPagedQuery {
    val dsl = PagedQueryDsl()
    dsl.block()
    return dsl.build()
}

fun condition(block: ConditionDsl.() -> Unit): Condition {
    val dsl = ConditionDsl()
    dsl.block()
    return dsl.build()
}

fun projection(block: ProjectionDsl.() -> Unit): Projection {
    val dsl = ProjectionDsl()
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

fun <S : Any> IListQuery.query(queryService: SnapshotQueryService<S>): Flux<MaterializedSnapshot<S>> {
    return queryService.list(this)
}

fun <S : Any> IPagedQuery.query(queryService: SnapshotQueryService<S>): Mono<PagedList<MaterializedSnapshot<S>>> {
    return queryService.paged(this)
}

fun <S : Any> ISingleQuery.query(queryService: SnapshotQueryService<S>): Mono<MaterializedSnapshot<S>> {
    return queryService.single(this)
}

fun IListQuery.dynamicQuery(queryService: SnapshotQueryService<*>): Flux<DynamicDocument> {
    return queryService.dynamicList(this)
}

fun IPagedQuery.dynamicQuery(queryService: SnapshotQueryService<*>): Mono<PagedList<DynamicDocument>> {
    return queryService.dynamicPaged(this)
}

fun ISingleQuery.dynamicQuery(queryService: SnapshotQueryService<*>): Mono<DynamicDocument> {
    return queryService.dynamicSingle(this)
}

fun Condition.count(queryService: SnapshotQueryService<*>): Mono<Long> {
    return queryService.count(this)
}

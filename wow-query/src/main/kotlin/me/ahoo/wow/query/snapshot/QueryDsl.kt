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

package me.ahoo.wow.query.snapshot

import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.query.dsl.FilterDsl
import me.ahoo.wow.query.dsl.NestedFieldDsl
import me.ahoo.wow.serialization.state.StateAggregateRecords
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

fun NestedFieldDsl.nestedState() {
    this.nested(StateAggregateRecords.STATE)
}

/** Applies [block] in the snapshot state path scope. */
fun FilterDsl.pathState(block: FilterDsl.() -> Unit) {
    StateAggregateRecords.STATE.path(block)
}

fun <S : Any> IListQuery.query(queryService: SnapshotQueryService<S>): Flux<MaterializedSnapshot<S>> {
    return queryService.list(this)
}

fun <S : Any> IPagedQuery.query(queryService: SnapshotQueryService<S>): Mono<PagedList<MaterializedSnapshot<S>>> {
    return queryService.paged(this)
}

fun <S : Any> ICursorQuery.query(queryService: SnapshotQueryService<S>): Mono<CursorPage<MaterializedSnapshot<S>>> {
    return queryService.cursor(this)
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

fun ICursorQuery.dynamicQuery(queryService: SnapshotQueryService<*>): Mono<CursorPage<DynamicDocument>> {
    return queryService.dynamicCursor(this)
}

fun ISingleQuery.dynamicQuery(queryService: SnapshotQueryService<*>): Mono<DynamicDocument> {
    return queryService.dynamicSingle(this)
}

fun FilterExpression.count(queryService: SnapshotQueryService<*>): Mono<Long> {
    return queryService.count(this)
}

fun AggregationQuery.query(queryService: SnapshotQueryService<*>): Flux<DynamicDocument> {
    return queryService.aggregate(this)
}

@Deprecated("Use FilterExpression.count.")
fun Condition.count(queryService: SnapshotQueryService<*>): Mono<Long> {
    return queryService.count(this)
}

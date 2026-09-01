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
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.toFilterExpression
import me.ahoo.wow.query.dsl.FilterDsl
import me.ahoo.wow.query.dsl.NestedFieldDsl
import me.ahoo.wow.serialization.state.StateAggregateRecords
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

fun NestedFieldDsl.nestedState() {
    this.nested(StateAggregateRecords.STATE)
}

/** Applies [block] in the snapshot state path scope. */
fun FilterDsl.pathState(block: FilterDsl.() -> Unit) {
    StateAggregateRecords.STATE.path(block)
}

fun <S : Any> IListQuery.query(queryGateway: SnapshotQueryGateway<S>): Flux<MaterializedSnapshot<S>> {
    return queryGateway.list(this)
}

fun <S : Any> IPagedQuery.query(queryGateway: SnapshotQueryGateway<S>): Mono<PagedList<MaterializedSnapshot<S>>> {
    return queryGateway.paged(this)
}

fun <S : Any> ICursorQuery.query(
    queryGateway: SnapshotQueryGateway<S>,
): Mono<CursorPage<MaterializedSnapshot<S>>> = queryGateway.cursor(this)

fun <S : Any> ISingleQuery.query(queryGateway: SnapshotQueryGateway<S>): Mono<MaterializedSnapshot<S>> {
    return queryGateway.single(this)
}

fun IListQuery.dynamicQuery(queryGateway: SnapshotQueryGateway<*>): Flux<ObjectNode> {
    return queryGateway.dynamicList(this)
}

fun IPagedQuery.dynamicQuery(queryGateway: SnapshotQueryGateway<*>): Mono<PagedList<ObjectNode>> {
    return queryGateway.dynamicPaged(this)
}

fun ICursorQuery.dynamicQuery(queryGateway: SnapshotQueryGateway<*>): Mono<CursorPage<ObjectNode>> =
    queryGateway.dynamicCursor(this)

fun ISingleQuery.dynamicQuery(queryGateway: SnapshotQueryGateway<*>): Mono<ObjectNode> {
    return queryGateway.dynamicSingle(this)
}

fun FilterExpression.count(queryGateway: SnapshotQueryGateway<*>): Mono<Long> {
    return queryGateway.count(this)
}

fun AggregationQuery.query(queryGateway: SnapshotQueryGateway<*>): Flux<ObjectNode> {
    return queryGateway.aggregate(this)
}

@Deprecated("Scheduled for removal in 10.0.0. Use FilterExpression.count.")
fun Condition.count(queryGateway: SnapshotQueryGateway<*>): Mono<Long> {
    return queryGateway.count(this.toFilterExpression())
}

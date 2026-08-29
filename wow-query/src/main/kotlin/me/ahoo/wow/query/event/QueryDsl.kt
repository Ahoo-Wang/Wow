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

package me.ahoo.wow.query.event

import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.toFilterExpression
import me.ahoo.wow.event.DomainEventStream
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

fun ISingleQuery.query(queryGateway: EventStreamQueryGateway): Mono<DomainEventStream> {
    return queryGateway.single(this)
}

fun ISingleQuery.dynamicQuery(queryGateway: EventStreamQueryGateway): Mono<ObjectNode> {
    return queryGateway.dynamicSingle(this)
}

fun IListQuery.query(queryGateway: EventStreamQueryGateway): Flux<DomainEventStream> {
    return queryGateway.list(this)
}

fun IListQuery.dynamicQuery(queryGateway: EventStreamQueryGateway): Flux<ObjectNode> {
    return queryGateway.dynamicList(this)
}

fun IPagedQuery.query(queryGateway: EventStreamQueryGateway): Mono<PagedList<DomainEventStream>> {
    return queryGateway.paged(this)
}

fun IPagedQuery.dynamicQuery(queryGateway: EventStreamQueryGateway): Mono<PagedList<ObjectNode>> {
    return queryGateway.dynamicPaged(this)
}

fun FilterExpression.count(queryGateway: EventStreamQueryGateway): Mono<Long> {
    return queryGateway.count(this)
}

@Deprecated("Use FilterExpression.count.")
fun Condition.count(queryGateway: EventStreamQueryGateway): Mono<Long> {
    return queryGateway.count(this.toFilterExpression())
}

/** Executes this aggregation against persisted event-stream documents. */
fun AggregationQuery.query(queryGateway: EventStreamQueryGateway): Flux<ObjectNode> {
    return queryGateway.aggregate(this)
}

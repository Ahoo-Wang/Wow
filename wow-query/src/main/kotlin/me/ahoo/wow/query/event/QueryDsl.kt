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

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.event.DomainEventStream
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

fun ISingleQuery.query(queryService: EventStreamQueryService): Mono<DomainEventStream> {
    return queryService.single(this)
}

fun ISingleQuery.dynamicQuery(queryService: EventStreamQueryService): Mono<DynamicDocument> {
    return queryService.dynamicSingle(this)
}

fun IListQuery.query(queryService: EventStreamQueryService): Flux<DomainEventStream> {
    return queryService.list(this)
}

fun IListQuery.dynamicQuery(queryService: EventStreamQueryService): Flux<DynamicDocument> {
    return queryService.dynamicList(this)
}

fun IPagedQuery.query(queryService: EventStreamQueryService): Mono<PagedList<DomainEventStream>> {
    return queryService.paged(this)
}

fun IPagedQuery.dynamicQuery(queryService: EventStreamQueryService): Mono<PagedList<DynamicDocument>> {
    return queryService.dynamicPaged(this)
}

fun Condition.count(queryService: EventStreamQueryService): Mono<Long> {
    return queryService.count(this)
}

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

package me.ahoo.wow.query.event.filter

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.query.filter.QueryContext
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

interface EventStreamQueryContext<SOURCE : EventStreamQueryContext<SOURCE, Q, R>, Q : Any, R : Any> :
    QueryContext<SOURCE, Q, R> {
    val queryType: QueryType
}

enum class QueryType(val isDynamic: Boolean) {
    LIST(false),
    DYNAMIC_LIST(true),
    COUNT(false),
}

class ListEventStreamQueryContext(
    override val namedAggregate: NamedAggregate,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : EventStreamQueryContext<ListEventStreamQueryContext, IListQuery, Flux<DomainEventStream>> {
    override val queryType: QueryType
        get() = QueryType.LIST
}

class DynamicListEventStreamQueryContext(
    override val namedAggregate: NamedAggregate,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : EventStreamQueryContext<DynamicListEventStreamQueryContext, IListQuery, Flux<DynamicDocument>> {
    override val queryType: QueryType
        get() = QueryType.DYNAMIC_LIST
}

class CountEventStreamQueryContext(
    override val namedAggregate: NamedAggregate,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) : EventStreamQueryContext<CountEventStreamQueryContext, Condition, Mono<Long>> {
    override val queryType: QueryType
        get() = QueryType.COUNT
}

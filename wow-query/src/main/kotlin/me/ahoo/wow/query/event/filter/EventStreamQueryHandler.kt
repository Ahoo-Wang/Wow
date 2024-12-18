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
import me.ahoo.wow.filter.AbstractHandler
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.query.filter.QueryHandler
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface EventStreamQueryHandler : QueryHandler<EventStreamQueryContext<*, *, *>> {

    fun <S : Any> list(namedAggregate: NamedAggregate, query: IListQuery): Flux<DomainEventStream> {
        val context = ListEventStreamQueryContext(
            namedAggregate = namedAggregate,
        ).setQuery(query)
        return handle(context)
            .checkpoint("List ${namedAggregate.toStringWithAlias()} [EventStreamQueryHandler]")
            .thenMany(
                Flux.defer {
                    context.getRequiredResult()
                }
            )
    }

    override fun dynamicList(namedAggregate: NamedAggregate, query: IListQuery): Flux<DynamicDocument> {
        val context = DynamicListEventStreamQueryContext(
            namedAggregate = namedAggregate,
        ).setQuery(query)
        return handle(context)
            .checkpoint("DynamicList ${namedAggregate.toStringWithAlias()} [EventStreamQueryHandler]")
            .thenMany(
                Flux.defer {
                    context.getRequiredResult()
                }
            )
    }

    override fun count(namedAggregate: NamedAggregate, condition: Condition): Mono<Long> {
        val context = CountEventStreamQueryContext(namedAggregate).setQuery(condition)
        return handle(context)
            .checkpoint("Count ${namedAggregate.toStringWithAlias()} [EventStreamQueryHandler]")
            .then(
                Mono.defer {
                    context.getRequiredResult()
                }
            )
    }
}

class DefaultEventStreamQueryHandler(
    chain: FilterChain<EventStreamQueryContext<*, *, *>>,
    errorHandler: ErrorHandler<EventStreamQueryContext<*, *, *>> = LogErrorHandler()
) : EventStreamQueryHandler, AbstractHandler<EventStreamQueryContext<*, *, *>>(
    chain,
    errorHandler,
)

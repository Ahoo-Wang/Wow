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

import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.filter.CountQueryContext
import me.ahoo.wow.query.filter.ListQueryContext
import me.ahoo.wow.query.filter.PagedQueryContext
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.filter.SingleQueryContext
import reactor.core.publisher.Mono

@FilterType(EventStreamQueryHandler::class)
interface EventStreamQueryFilter : QueryFilter<QueryContext<*, *, *>>

@Order(ORDER_LAST)
@FilterType(EventStreamQueryHandler::class)
@Suppress("UNCHECKED_CAST")
class TailEventStreamQueryFilter(private val queryServiceFactory: EventStreamQueryServiceFactory) :
    EventStreamQueryFilter {
    override fun filter(
        context: QueryContext<*, *, *>,
        next: FilterChain<QueryContext<*, *, *>>
    ): Mono<Void> {
        val queryService = queryServiceFactory.create(context.namedAggregate)
        when (context.queryType) {
            QueryType.SINGLE -> {
                context as SingleQueryContext<DomainEventStream>
                context.setResult(queryService.single(context.getQuery()))
            }

            QueryType.DYNAMIC_SINGLE -> {
                context as SingleQueryContext<DynamicDocument>
                context.setResult(queryService.dynamicSingle(context.getQuery()))
            }

            QueryType.LIST -> {
                context as ListQueryContext<DomainEventStream>
                context.setResult(queryService.list(context.getQuery()))
            }

            QueryType.DYNAMIC_LIST -> {
                context as ListQueryContext<DynamicDocument>
                context.setResult(queryService.dynamicList(context.getQuery()))
            }

            QueryType.PAGED -> {
                context as PagedQueryContext<DomainEventStream>
                context.setResult(queryService.paged(context.getQuery()))
            }

            QueryType.DYNAMIC_PAGED -> {
                context as PagedQueryContext<DynamicDocument>
                context.setResult(queryService.dynamicPaged(context.getQuery()))
            }

            QueryType.COUNT -> {
                context as CountQueryContext
                context.setResult(queryService.count(context.getQuery()))
            }
        }
        return next.filter(context)
    }
}

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
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.query.mask.EventStreamMaskerRegistry
import reactor.core.publisher.Mono

@Suppress("UNCHECKED_CAST")
@Order(ORDER_LAST, before = [TailEventStreamQueryFilter::class])
@FilterType(EventStreamQueryHandler::class)
class MaskingEventStreamQueryFilter(
    private val maskerRegistry: EventStreamMaskerRegistry
) : EventStreamQueryFilter {
    override fun filter(
        context: EventStreamQueryContext<*, *, *>,
        next: FilterChain<EventStreamQueryContext<*, *, *>>
    ): Mono<Void> {
        return next.filter(context).then(
            Mono.defer {
                tryMask(context)
                Mono.empty()
            }
        )
    }

    @Suppress("LongMethod")
    private fun tryMask(context: EventStreamQueryContext<*, *, *>) {
        if (context.queryType != QueryType.DYNAMIC_LIST) {
            return
        }
        val aggregateDataMasker = maskerRegistry.getAggregateDataMasker(context.namedAggregate)
        if (aggregateDataMasker.isEmpty()) {
            return
        }
        context as DynamicListEventStreamQueryContext
        context.rewriteResult { result ->
            result.map {
                aggregateDataMasker.mask(it)
            }
        }
    }
}

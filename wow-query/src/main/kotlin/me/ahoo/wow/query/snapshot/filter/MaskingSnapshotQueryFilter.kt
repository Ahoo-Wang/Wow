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

package me.ahoo.wow.query.snapshot.filter

import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.mask.StateObjectNodeMaskerRegistry
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import reactor.core.publisher.Mono

@Order(ORDER_LAST)
@FilterType(SnapshotQueryGateway::class)
class MaskingSnapshotQueryFilter(
    private val maskerRegistry: StateObjectNodeMaskerRegistry,
) : SnapshotQueryFilter {
    override fun filter(context: QueryContext<*, *>, next: FilterChain<QueryContext<*, *>>): Mono<Void> =
        next.filter(context).then(Mono.fromRunnable { mask(context) })

    private fun mask(context: QueryContext<*, *>) {
        if (context.queryType == QueryType.COUNT || context.queryType == QueryType.AGGREGATION) return
        val masker = maskerRegistry.getMasker(context.namedAggregate)
        when (context.queryType) {
            QueryType.SINGLE -> context.asSingleQuery().rewriteResult { it.map(masker::mask) }
            QueryType.LIST -> context.asListQuery().rewriteResult { it.map(masker::mask) }
            QueryType.PAGED -> context.asPagedQuery().rewriteResult { result ->
                result.map { page -> PagedList(page.total, page.list.map(masker::mask)) }
            }
            QueryType.COUNT, QueryType.AGGREGATION -> Unit
        }
    }
}

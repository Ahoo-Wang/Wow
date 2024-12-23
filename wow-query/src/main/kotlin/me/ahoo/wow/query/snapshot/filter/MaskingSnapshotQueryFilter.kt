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
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.query.filter.ListQueryContext
import me.ahoo.wow.query.filter.MaskingDynamicDocumentQueryFilter
import me.ahoo.wow.query.filter.PagedQueryContext
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.filter.SingleQueryContext
import me.ahoo.wow.query.mask.StateDataMaskerRegistry
import me.ahoo.wow.query.mask.StateDynamicDocumentMasker
import me.ahoo.wow.query.mask.tryMask
import reactor.core.publisher.Mono

@Suppress("UNCHECKED_CAST")
@Order(ORDER_LAST, before = [TailSnapshotQueryFilter::class])
@FilterType(SnapshotQueryHandler::class)
class MaskingSnapshotQueryFilter(maskerRegistry: StateDataMaskerRegistry) : SnapshotQueryFilter,
    MaskingDynamicDocumentQueryFilter<StateDynamicDocumentMasker>(maskerRegistry) {
    override fun filter(
        context: QueryContext<*, *, *>,
        next: FilterChain<QueryContext<*, *, *>>
    ): Mono<Void> {
        return next.filter(context).then(
            Mono.defer {
                mask(context)
                Mono.empty()
            }
        )
    }

    @Suppress("LongMethod")
    private fun mask(context: QueryContext<*, *, *>) {
        if (context.queryType == QueryType.COUNT) {
            return
        }
        if (context.queryType.isDynamic) {
            maskDynamicDocument(context)
            return
        }

        maskState(context)
    }

    private fun maskState(context: QueryContext<*, *, *>) {
        when (context.queryType) {
            QueryType.SINGLE -> {
                context as SingleQueryContext<MaterializedSnapshot<Any>>
                context.rewriteResult { result ->
                    result.map {
                        it.tryMask()
                    }
                }
            }

            QueryType.LIST -> {
                context as ListQueryContext<MaterializedSnapshot<Any>>
                context.rewriteResult { result ->
                    result.map {
                        it.tryMask()
                    }
                }
            }

            QueryType.PAGED -> {
                context as PagedQueryContext<MaterializedSnapshot<Any>>
                context.rewriteResult { result ->
                    result.map {
                        it.tryMask()
                    }
                }
            }

            else -> {
            }
        }
    }
}

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
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.query.mask.StateDataMaskerRegistry
import me.ahoo.wow.query.mask.mask
import me.ahoo.wow.query.mask.tryMask
import reactor.core.publisher.Mono

@Suppress("UNCHECKED_CAST")
@Order(ORDER_LAST, before = [TailSnapshotQueryFilter::class])
@FilterType(SnapshotQueryHandler::class)
object MaskingSnapshotQueryFilter : SnapshotQueryFilter {
    override fun filter(
        context: SnapshotQueryContext<*, *, *>,
        next: FilterChain<SnapshotQueryContext<*, *, *>>
    ): Mono<Void> {
        return next.filter(context).then(
            Mono.defer {
                tryMask(context)
                Mono.empty()
            }
        )
    }

    @Suppress("LongMethod")
    private fun tryMask(context: SnapshotQueryContext<*, *, *>) {
        if (context.queryType == QueryType.COUNT) {
            return
        }
        when (context.queryType) {
            QueryType.SINGLE -> {
                context as SingleSnapshotQueryContext<MaterializedSnapshot<Any>>
                context.rewriteResult { result ->
                    result.map {
                        it.tryMask()
                    }
                }
            }

            QueryType.LIST -> {
                context as ListSnapshotQueryContext<MaterializedSnapshot<Any>>
                context.rewriteResult { result ->
                    result.map {
                        it.tryMask()
                    }
                }
            }

            QueryType.PAGED -> {
                context as PagedSnapshotQueryContext<MaterializedSnapshot<Any>>
                context.rewriteResult { result ->
                    result.map {
                        it.tryMask()
                    }
                }
            }

            else -> {
                tryMaskDynamicDocument(context)
            }
        }
    }

    private fun tryMaskDynamicDocument(context: SnapshotQueryContext<*, *, *>) {
        val aggregateDataMasker = StateDataMaskerRegistry.getAggregateDataMasker(context.namedAggregate)
        if (aggregateDataMasker.isEmpty()) {
            return
        }
        when (context.queryType) {
            QueryType.DYNAMIC_SINGLE -> {
                context as SingleSnapshotQueryContext<DynamicDocument>
                context.rewriteResult { result ->
                    result.map {
                        aggregateDataMasker.mask(it)
                    }
                }
            }

            QueryType.DYNAMIC_LIST -> {
                context as ListSnapshotQueryContext<DynamicDocument>
                context.rewriteResult { result ->
                    result.map {
                        aggregateDataMasker.mask(it)
                    }
                }
            }

            QueryType.DYNAMIC_PAGED -> {
                context as PagedSnapshotQueryContext<DynamicDocument>
                context.rewriteResult { result ->
                    result.map {
                        aggregateDataMasker.mask(it)
                    }
                }
            }

            else -> {
            }
        }
    }
}

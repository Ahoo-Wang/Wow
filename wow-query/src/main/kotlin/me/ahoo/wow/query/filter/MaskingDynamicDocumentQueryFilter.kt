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

package me.ahoo.wow.query.filter

import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.query.mask.AggregateDynamicDocumentMasker
import me.ahoo.wow.query.mask.DataMaskerRegistry
import me.ahoo.wow.query.mask.mask
import reactor.core.publisher.Mono

@Suppress("UNCHECKED_CAST")
abstract class MaskingDynamicDocumentQueryFilter<MASKER : AggregateDynamicDocumentMasker>(
    protected val maskerRegistry: DataMaskerRegistry<MASKER>
) :
    QueryFilter<QueryContext<*, *>> {
    override fun filter(
        context: QueryContext<*, *>,
        next: FilterChain<QueryContext<*, *>>
    ): Mono<Void> {
        return next.filter(context).then(
            Mono.defer {
                maskDynamicDocument(context)
                Mono.empty()
            }
        )
    }

    fun maskDynamicDocument(context: QueryContext<*, *>) {
        if (!context.queryType.isDynamic) {
            return
        }
        val aggregateDataMasker = maskerRegistry.getAggregateDataMasker(context.namedAggregate)
        if (aggregateDataMasker.isEmpty()) {
            return
        }
        when (context.queryType) {
            QueryType.DYNAMIC_SINGLE -> {
                context.asSingleQuery<DynamicDocument>().rewriteResult { result ->
                    result.map {
                        aggregateDataMasker.mask(it)
                    }
                }
            }

            QueryType.DYNAMIC_LIST -> {
                context.asListQuery<DynamicDocument>().rewriteResult { result ->
                    result.map {
                        aggregateDataMasker.mask(it)
                    }
                }
            }

            QueryType.DYNAMIC_PAGED -> {
                context.asPagedQuery<DynamicDocument>().rewriteResult { result ->
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

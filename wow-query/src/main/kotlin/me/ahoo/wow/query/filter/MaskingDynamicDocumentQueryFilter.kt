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

@file:OptIn(me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.query.filter

import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.query.gateway.QueryErrorCategory
import me.ahoo.wow.query.gateway.QueryExecutionException
import me.ahoo.wow.query.mask.AggregateDynamicDocumentMasker
import me.ahoo.wow.query.mask.DataMaskerRegistry
import me.ahoo.wow.query.mask.mask
import me.ahoo.wow.serialization.MessageRecords
import reactor.core.publisher.Mono

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
                        aggregateDataMasker.maskPreservingSystemFields(it)
                    }
                }
            }

            QueryType.DYNAMIC_LIST -> {
                context.asListQuery<DynamicDocument>().rewriteResult { result ->
                    result.map {
                        aggregateDataMasker.maskPreservingSystemFields(it)
                    }
                }
            }

            QueryType.DYNAMIC_PAGED -> {
                context.asPagedQuery<DynamicDocument>().rewriteResult { result ->
                    result.map { page ->
                        me.ahoo.wow.api.query.PagedList(
                            page.total,
                            page.list.map { document ->
                                aggregateDataMasker.maskPreservingSystemFields(document)
                            },
                        )
                    }
                }
            }

            else -> {
            }
        }
    }

    private fun me.ahoo.wow.query.mask.AggregateDataMasker<MASKER>.maskPreservingSystemFields(
        source: DynamicDocument,
    ): DynamicDocument {
        val protectedFields = SYSTEM_FIELDS.associateWith { field ->
            ProtectedField(source.containsKey(field), source[field])
        }
        val masked = mask(source)
        protectedFields.forEach { (field, original) ->
            if (masked.containsKey(field) != original.present || masked[field] != original.value) {
                throw QueryExecutionException(
                    category = QueryErrorCategory.INTERNAL_FAILURE,
                    path = "$.result.$field",
                    code = "RESULT_MASKING_SYSTEM_FIELD_VIOLATION",
                )
            }
        }
        return masked
    }

    private data class ProtectedField(val present: Boolean, val value: Any?)

    private companion object {
        val SYSTEM_FIELDS = listOf(
            MessageRecords.ID,
            MessageRecords.AGGREGATE_ID,
            MessageRecords.TENANT_ID,
            MessageRecords.OWNER_ID,
            MessageRecords.SPACE_ID,
        )
    }
}

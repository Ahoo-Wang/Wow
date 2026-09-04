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

package me.ahoo.wow.query.mask

import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.SimpleFilterChain
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QueryModelSchema
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.atomic.AtomicReference

internal class SchemaMaskQueryFilter : QueryFilter<QueryContext<*, *>> {
    private val cached = AtomicReference<Pair<QueryModelSchema, SchemaMasker>?>()

    override fun filter(
        context: QueryContext<*, *>,
        next: FilterChain<QueryContext<*, *>>,
    ): Mono<Void> {
        if (
            !context.schema.hasMaskedFields ||
            context.queryType == QueryType.COUNT ||
            context.queryType == QueryType.AGGREGATION
        ) {
            return next.filter(context)
        }
        return next.filter(context).then(
            Mono.fromRunnable {
                val schemaMasker = masker(context.schema)
                when (context.queryType) {
                    QueryType.SINGLE -> context.asSingleQuery().rewriteResult { it.maskResult(schemaMasker) }
                    QueryType.LIST -> context.asListQuery().rewriteResult { it.maskResult(schemaMasker) }
                    QueryType.PAGED -> context.asPagedQuery().rewriteResult { it.maskPagedResult(schemaMasker) }
                    QueryType.CURSOR -> context.asCursorQuery().rewriteResult { it.maskCursorResult(schemaMasker) }
                    QueryType.COUNT,
                    QueryType.AGGREGATION,
                    -> Unit
                }
            },
        )
    }

    private fun masker(schema: QueryModelSchema): SchemaMasker =
        cached.get()?.takeIf { it.first === schema }?.second
            ?: checkNotNull(SchemaMasker.create(schema)).also { cached.set(schema to it) }

    private fun Mono<ObjectNode>.maskResult(masker: SchemaMasker): Mono<ObjectNode> = map(masker::mask)

    private fun Flux<ObjectNode>.maskResult(masker: SchemaMasker): Flux<ObjectNode> = map(masker::mask)

    private fun Mono<PagedList<ObjectNode>>.maskPagedResult(masker: SchemaMasker): Mono<PagedList<ObjectNode>> =
        map { page -> page.also { it.list.forEach(masker::mask) } }

    private fun Mono<CursorPage<ObjectNode>>.maskCursorResult(masker: SchemaMasker): Mono<CursorPage<ObjectNode>> =
        map { page -> page.also { it.list.forEach(masker::mask) } }
}

internal fun FilterChain<QueryContext<*, *>>.withSchemaMaskFilter(): FilterChain<QueryContext<*, *>> =
    SimpleFilterChain(SchemaMaskQueryFilter(), this)

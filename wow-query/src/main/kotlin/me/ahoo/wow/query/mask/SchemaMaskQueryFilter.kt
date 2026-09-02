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
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

internal class SchemaMaskQueryFilter : QueryFilter<QueryContext<*, *>> {
    private val cached = AtomicReference<Pair<QueryModelSchema, Optional<SchemaMasker>>?>()

    override fun filter(
        context: QueryContext<*, *>,
        next: FilterChain<QueryContext<*, *>>,
    ): Mono<Void> = next.filter(context).then(
        Mono.defer {
            when (context.queryType) {
                QueryType.COUNT,
                QueryType.AGGREGATION,
                -> return@defer Mono.empty()
                else -> Unit
            }
            val schema = context.schema
            when (context.queryType) {
                QueryType.SINGLE -> context.asSingleQuery().rewriteResult { it.maskResult(schema) }
                QueryType.LIST -> context.asListQuery().rewriteResult { it.maskResult(schema) }
                QueryType.PAGED -> context.asPagedQuery().rewriteResult { it.maskPagedResult(schema) }
                QueryType.CURSOR -> context.asCursorQuery().rewriteResult { it.maskCursorResult(schema) }
                QueryType.COUNT,
                QueryType.AGGREGATION,
                -> Unit
            }
            Mono.empty()
        },
    )

    private fun masker(schema: QueryModelSchema): Optional<SchemaMasker> =
        cached.get()?.takeIf { it.first === schema }?.second ?: Optional
            .ofNullable(SchemaMasker.create(schema))
            .also { cached.set(schema to it) }

    private fun Mono<ObjectNode>.maskResult(schema: QueryModelSchema): Mono<ObjectNode> =
        masker(schema).map { map(it::mask) }.orElse(this)

    private fun Flux<ObjectNode>.maskResult(schema: QueryModelSchema): Flux<ObjectNode> =
        masker(schema).map { map(it::mask) }.orElse(this)

    private fun Mono<PagedList<ObjectNode>>.maskPagedResult(schema: QueryModelSchema): Mono<PagedList<ObjectNode>> =
        masker(schema).map { schemaMasker ->
            map { page -> PagedList(page.total, page.list.map(schemaMasker::mask)) }
        }.orElse(this)

    private fun Mono<CursorPage<ObjectNode>>.maskCursorResult(schema: QueryModelSchema): Mono<CursorPage<ObjectNode>> =
        masker(schema).map { schemaMasker ->
            map { page -> page.copy(list = page.list.map(schemaMasker::mask)) }
        }.orElse(this)
}

internal fun FilterChain<QueryContext<*, *>>.withSchemaMaskFilter(): FilterChain<QueryContext<*, *>> =
    SimpleFilterChain(SchemaMaskQueryFilter(), this)

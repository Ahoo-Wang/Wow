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

import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.SimpleFilterChain
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

internal class SchemaMaskQueryFilter(
    provider: QueryModelSchemaProvider,
) : QueryFilter<QueryContext<*, *>> {
    private val masker: Mono<Optional<SchemaMasker>>

    init {
        val cached = AtomicReference<Pair<QueryModelSchema, Optional<SchemaMasker>>?>()
        masker = Mono.defer(provider::schema).map { schema ->
            cached.get()?.takeIf { it.first === schema }?.second ?: Optional
                .ofNullable(SchemaMasker.create(schema))
                .also { cached.set(schema to it) }
        }
    }

    override fun filter(
        context: QueryContext<*, *>,
        next: FilterChain<QueryContext<*, *>>,
    ): Mono<Void> = next.filter(context).then(
        Mono.fromRunnable {
            when (context.queryType) {
                QueryType.SINGLE -> context.asSingleQuery().rewriteResult { it.maskResult() }
                QueryType.LIST -> context.asListQuery().rewriteResult { it.maskResult() }
                QueryType.PAGED -> context.asPagedQuery().rewriteResult { it.maskPagedResult() }
                QueryType.COUNT,
                QueryType.AGGREGATION,
                -> Unit
            }
        },
    )

    private fun Mono<ObjectNode>.maskResult(): Mono<ObjectNode> = masker.flatMap { optional ->
        optional.map { schemaMasker -> map(schemaMasker::mask) }.orElse(this)
    }

    private fun Flux<ObjectNode>.maskResult(): Flux<ObjectNode> = masker.flatMapMany { optional ->
        optional.map { schemaMasker -> map(schemaMasker::mask) }.orElse(this)
    }

    private fun Mono<PagedList<ObjectNode>>.maskPagedResult(): Mono<PagedList<ObjectNode>> = masker.flatMap { optional ->
        optional.map { schemaMasker ->
            map { page -> PagedList(page.total, page.list.map(schemaMasker::mask)) }
        }.orElse(this)
    }
}

internal fun FilterChain<QueryContext<*, *>>.withSchemaMaskFilter(
    backend: QueryBackend,
): FilterChain<QueryContext<*, *>> {
    val provider = backend as? QueryModelSchemaProvider ?: return this
    return SimpleFilterChain(SchemaMaskQueryFilter(provider), this)
}

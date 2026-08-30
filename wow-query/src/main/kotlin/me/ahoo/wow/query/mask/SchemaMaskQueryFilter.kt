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
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.SimpleFilterChain
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.withQueryModelSchema
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

internal class SchemaMaskQueryFilter(
    private val provider: QueryModelSchemaProvider,
) : QueryFilter<QueryContext<*, *>> {
    private val cached = AtomicReference<Pair<QueryModelSchema, Optional<SchemaMasker>>?>()

    override fun filter(
        context: QueryContext<*, *>,
        next: FilterChain<QueryContext<*, *>>,
    ): Mono<Void> {
        val projection = (context.getQuery() as? Queryable<*>)?.projection ?: Projection.ALL
        return next.filter(context).then(
            Mono.fromRunnable {
                when (context.queryType) {
                    QueryType.SINGLE -> context.asSingleQuery().rewriteResult { it.maskResult(projection) }
                    QueryType.LIST -> context.asListQuery().rewriteResult { it.maskResult(projection) }
                    QueryType.PAGED -> context.asPagedQuery().rewriteResult { it.maskPagedResult(projection) }
                    QueryType.CURSOR -> context.asCursorQuery().rewriteResult { it.maskCursorResult(projection) }
                    QueryType.COUNT,
                    QueryType.AGGREGATION,
                    -> Unit
                }
            },
        )
    }

    private fun masker(schema: QueryModelSchema): Optional<SchemaMasker> =
        cached.get()?.takeIf { it.first === schema }?.second ?: Optional
            .ofNullable(SchemaMasker.create(schema))
            .also { cached.set(schema to it) }

    private fun Mono<ObjectNode>.maskResult(projection: Projection): Mono<ObjectNode> =
        Mono.defer(provider::schema).flatMap { schema ->
            val source = withQueryModelSchema(schema)
            masker(schema).map { schemaMasker ->
                val removeBodyType = schema.requiresInternalEventBodyType(projection)
                source.map { schemaMasker.mask(it, removeBodyType) }
            }.orElse(source)
        }

    private fun Flux<ObjectNode>.maskResult(projection: Projection): Flux<ObjectNode> =
        Mono.defer(provider::schema).flatMapMany { schema ->
            val source = withQueryModelSchema(schema)
            masker(schema).map { schemaMasker ->
                val removeBodyType = schema.requiresInternalEventBodyType(projection)
                source.map { schemaMasker.mask(it, removeBodyType) }
            }.orElse(source)
        }

    private fun Mono<PagedList<ObjectNode>>.maskPagedResult(projection: Projection): Mono<PagedList<ObjectNode>> =
        Mono.defer(provider::schema).flatMap { schema ->
            val source = withQueryModelSchema(schema)
            masker(schema).map { schemaMasker ->
                val removeBodyType = schema.requiresInternalEventBodyType(projection)
                source.map { page -> PagedList(page.total, page.list.map { schemaMasker.mask(it, removeBodyType) }) }
            }.orElse(source)
        }

    private fun Mono<CursorPage<ObjectNode>>.maskCursorResult(projection: Projection): Mono<CursorPage<ObjectNode>> =
        Mono.defer(provider::schema).flatMap { schema ->
            val source = withQueryModelSchema(schema)
            masker(schema).map { schemaMasker ->
                val removeBodyType = schema.requiresInternalEventBodyType(projection)
                source.map { page -> page.copy(list = page.list.map { schemaMasker.mask(it, removeBodyType) }) }
            }.orElse(source)
        }

    private fun SchemaMasker.mask(node: ObjectNode, removeBodyType: Boolean): ObjectNode =
        mask(node).let { if (removeBodyType) it.removeInternalEventBodyType() else it }

    private fun QueryModelSchema.requiresInternalEventBodyType(projection: Projection): Boolean =
        model == QueryModel.EVENT_STREAM && projection.requiresInternalEventBodyType()
}

internal fun FilterChain<QueryContext<*, *>>.withSchemaMaskFilter(
    backend: QueryBackend,
): FilterChain<QueryContext<*, *>> {
    val provider = backend as? QueryModelSchemaProvider ?: return this
    return SimpleFilterChain(SchemaMaskQueryFilter(provider), this)
}

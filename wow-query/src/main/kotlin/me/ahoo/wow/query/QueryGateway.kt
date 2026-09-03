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

package me.ahoo.wow.query

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.filter.ErrorAccessor
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.query.filter.DefaultQueryContext
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.mask.withSchemaMaskFilter
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.requireAccepted
import me.ahoo.wow.serialization.toObject
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.JavaType
import tools.jackson.databind.node.ObjectNode
import kotlin.reflect.KClass

interface QueryGateway<R : Any> : NamedAggregateDecorator {
    fun single(query: ISingleQuery): Mono<R>
    fun dynamicSingle(query: ISingleQuery): Mono<ObjectNode>
    fun list(query: IListQuery): Flux<R>
    fun dynamicList(query: IListQuery): Flux<ObjectNode>
    fun paged(query: IPagedQuery): Mono<PagedList<R>>
    fun dynamicPaged(query: IPagedQuery): Mono<PagedList<ObjectNode>>
    fun cursor(query: ICursorQuery): Mono<CursorPage<R>>
    fun dynamicCursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>>
    fun count(filter: FilterExpression): Mono<Long>
    fun aggregate(query: AggregationQuery): Flux<ObjectNode>
}

abstract class AbstractQueryGateway<R : Any>(
    override val namedAggregate: NamedAggregate,
    binding: QueryBackendBinding<QueryBackend>,
    private val validationMode: QuerySchemaValidationMode,
    private val targetType: JavaType,
    filters: List<QueryFilter<QueryContext<*, *>>>,
    filterType: KClass<*>,
    private val errorHandler: ErrorHandler<QueryContext<*, *>>,
) : QueryGateway<R> {
    @Deprecated("Scheduled for removal in 10.0.0. Use QueryBackendBinding.")
    constructor(
        namedAggregate: NamedAggregate,
        backend: QueryBackend,
        schemaProvider: QueryModelSchemaProvider,
        validationMode: QuerySchemaValidationMode,
        targetType: JavaType,
        filters: List<QueryFilter<QueryContext<*, *>>>,
        filterType: KClass<*>,
        errorHandler: ErrorHandler<QueryContext<*, *>>,
    ) : this(
        namedAggregate,
        QueryBackendBinding(backend, schemaProvider),
        validationMode,
        targetType,
        filters,
        filterType,
        errorHandler,
    )

    private val backend = binding.backend
    private val schemaProvider = binding.schemaProvider
    private val chain = FilterChainBuilder<QueryContext<*, *>>()
        .addFilters(filters)
        .filterCondition(filterType)
        .build(FilterChain(::invokeBackend))
        .withSchemaMaskFilter()

    private fun invokeBackend(context: QueryContext<*, *>): Mono<Void> {
        when (context.queryType) {
            QueryType.SINGLE -> context.asSingleQuery().run {
                val accepted = schema.resolve(getQuery()).requireAccepted(validationMode)
                setResult(backend.single(ResolvedQuery(accepted, schema)))
            }
            QueryType.LIST -> context.asListQuery().run {
                val accepted = schema.resolve(getQuery()).requireAccepted(validationMode)
                setResult(backend.list(ResolvedQuery(accepted, schema)))
            }
            QueryType.PAGED -> context.asPagedQuery().run {
                val accepted = schema.resolve(getQuery()).requireAccepted(validationMode)
                setResult(backend.paged(ResolvedQuery(accepted, schema)))
            }
            QueryType.CURSOR -> context.asCursorQuery().run {
                val accepted = schema.resolve(getQuery()).requireAccepted(validationMode)
                setResult(backend.cursor(ResolvedQuery(accepted, schema)))
            }
            QueryType.COUNT -> context.asCountQuery().run {
                val accepted = schema.resolve(getQuery()).requireAccepted(validationMode)
                setResult(backend.count(ResolvedQuery(accepted, schema)))
            }
            QueryType.AGGREGATION -> context.asAggregationQuery().run {
                val accepted = schema.resolve(getQuery()).requireAccepted(validationMode)
                setResult(backend.aggregate(ResolvedQuery(accepted, schema)))
            }
        }
        return Mono.empty()
    }

    private fun observeError(context: QueryContext<*, *>, original: Throwable): Mono<Void> {
        if (context is ErrorAccessor) {
            context.setError(original)
        }
        return Mono.defer { errorHandler.handle(context, original) }
            .onErrorResume { handlerFailure ->
                if (handlerFailure !== original) {
                    original.addSuppressed(handlerFailure)
                }
                Mono.empty()
            }
    }

    private fun <Q : Any, RESULT : Any, T : Any> mono(
        queryType: QueryType,
        query: Q,
        result: (QueryContext<Q, RESULT>) -> Mono<T>,
    ): Mono<T> = Mono.defer {
        schemaProvider.schema().flatMap { schema ->
            val context = DefaultQueryContext<Q, RESULT>(queryType, namedAggregate, schema).setQuery(query)
            Mono.defer { chain.filter(context) }
                .then(Mono.defer { result(context) })
                .onErrorResume { original -> observeError(context, original).then(Mono.error(original)) }
        }
    }

    private fun <Q : Any, RESULT : Any, T : Any> flux(
        queryType: QueryType,
        query: Q,
        result: (QueryContext<Q, RESULT>) -> Flux<T>,
    ): Flux<T> = Flux.defer {
        schemaProvider.schema().flatMapMany { schema ->
            val context = DefaultQueryContext<Q, RESULT>(queryType, namedAggregate, schema).setQuery(query)
            Mono.defer { chain.filter(context) }
                .thenMany(Flux.defer { result(context) })
                .onErrorResume { original -> observeError(context, original).thenMany(Flux.error(original)) }
        }
    }

    protected open fun prepareDynamicResult(context: QueryContext<*, *>, result: ObjectNode): ObjectNode = result

    override fun single(query: ISingleQuery): Mono<R> =
        mono<ISingleQuery, Mono<ObjectNode>, R>(QueryType.SINGLE, query) { context ->
            context.getRequiredResult().map { it.toObject<R>(targetType) }
        }

    override fun dynamicSingle(query: ISingleQuery): Mono<ObjectNode> =
        mono<ISingleQuery, Mono<ObjectNode>, ObjectNode>(QueryType.SINGLE, query) { context ->
            context.getRequiredResult().map { result -> prepareDynamicResult(context, result) }
        }

    override fun list(query: IListQuery): Flux<R> =
        flux<IListQuery, Flux<ObjectNode>, R>(QueryType.LIST, query) { context ->
            context.getRequiredResult().map { it.toObject<R>(targetType) }
        }

    override fun dynamicList(query: IListQuery): Flux<ObjectNode> =
        flux<IListQuery, Flux<ObjectNode>, ObjectNode>(QueryType.LIST, query) { context ->
            context.getRequiredResult().map { result -> prepareDynamicResult(context, result) }
        }

    override fun paged(query: IPagedQuery): Mono<PagedList<R>> =
        mono<IPagedQuery, Mono<PagedList<ObjectNode>>, PagedList<R>>(QueryType.PAGED, query) { context ->
            context.getRequiredResult().map { page ->
                PagedList(page.total, page.list.map { it.toObject<R>(targetType) })
            }
        }

    override fun dynamicPaged(query: IPagedQuery): Mono<PagedList<ObjectNode>> =
        mono<IPagedQuery, Mono<PagedList<ObjectNode>>, PagedList<ObjectNode>>(QueryType.PAGED, query) { context ->
            context.getRequiredResult().map { page ->
                page.copy(list = page.list.map { prepareDynamicResult(context, it) })
            }
        }

    override fun cursor(query: ICursorQuery): Mono<CursorPage<R>> =
        mono<ICursorQuery, Mono<CursorPage<ObjectNode>>, CursorPage<R>>(QueryType.CURSOR, query) { context ->
            context.getRequiredResult().map { page ->
                CursorPage(page.list.map { it.toObject<R>(targetType) }, page.nextCursor)
            }
        }

    override fun dynamicCursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> =
        mono<ICursorQuery, Mono<CursorPage<ObjectNode>>, CursorPage<ObjectNode>>(QueryType.CURSOR, query) { context ->
            context.getRequiredResult().map { page ->
                page.copy(list = page.list.map { prepareDynamicResult(context, it) })
            }
        }

    override fun count(filter: FilterExpression): Mono<Long> =
        mono<FilterExpression, Mono<Long>, Long>(QueryType.COUNT, filter) { it.getRequiredResult() }

    override fun aggregate(query: AggregationQuery): Flux<ObjectNode> =
        flux<AggregationQuery, Flux<ObjectNode>, ObjectNode>(QueryType.AGGREGATION, query) { it.getRequiredResult() }
}

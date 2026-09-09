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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.mask.SchemaMasker
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.validateQuery
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.toObject
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import reactor.util.context.ContextView
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
    private val targetType: JavaType,
    filters: List<QueryFilter>,
    filterType: KClass<*>,
    policies: List<QueryPolicy>,
    private val observer: QueryObserver,
) : QueryGateway<R> {
    private val backend = binding.backend
    private val schemaProvider = binding.schemaProvider
    private val prepares = filters.filter {
        it::class.scanAnnotation<FilterType>()?.value?.contains(filterType) ?: true
    }.sortedByOrder()
    private val policies = policies.toList()

    private fun <Q : RewritableFilter<Q>> prepare(
        query: Q,
        schema: QueryModelSchema,
        identity: ContextView,
    ): Mono<Q> {
        val scope = identity.queryScope()
        val prepared = prepares.fold(Mono.just(query)) { pending, filter ->
            pending.flatMap { current ->
                Mono.defer { filter.prepare(QueryContext(current, namedAggregate, schema)) }
                    .switchIfEmpty(
                        Mono.error { IllegalStateException("QueryFilter.prepare must emit exactly one query.") }
                    )
            }
        }
        return prepared.flatMap { current ->
            val scoped = if (scope === MatchAllFilter) current else current.appendFilter(scope)
            evaluatePolicies(identity, QueryContext(scoped, namedAggregate, schema))
                .map { if (it === MatchAllFilter) scoped else scoped.appendFilter(it) }
        }.map { applyDefaults(it, schema) }
    }

    private fun evaluatePolicies(
        identity: ContextView,
        context: QueryContext<*>,
    ): Mono<FilterExpression> =
        policies.fold(Mono.just<FilterExpression>(MatchAllFilter)) { pending, policy ->
            pending.flatMap { combined ->
                Mono.defer { policy.evaluate(identity, context) }
                    .switchIfEmpty(
                        Mono.error { IllegalStateException("QueryPolicy must emit one filter.") }
                    )
                    .map { if (it === MatchAllFilter) combined else combined.appendFilter(it) }
            }
        }

    private fun <Q : RewritableFilter<Q>> applyDefaults(query: Q, schema: QueryModelSchema): Q {
        if (schema.model != QueryModel.SNAPSHOT) return query
        val filter = when (query) {
            is FilterExpression -> query
            is FilterCapable<*> -> query.filter
            else -> error("Unsupported query filter contract.")
        }
        return if (filter.hasDeletionScope()) query else query.appendFilter(DeletionFilter(DeletionState.ACTIVE))
    }

    private fun FilterExpression.hasDeletionScope(): Boolean = when (this) {
        is DeletionFilter -> true
        is AndFilter -> operands.any { it.hasDeletionScope() }
        else -> false
    }

    private fun <Q : RewritableFilter<Q>, T : Any> mono(
        queryType: QueryType,
        query: Q,
        execute: (Q, QueryModelSchema) -> Mono<T>,
    ): Mono<T> = Mono.deferContextual { identity ->
        schemaProvider.schema()
            .switchIfEmpty(Mono.error { IllegalStateException("QueryModelSchemaProvider must emit one schema.") })
            .flatMap { schema ->
                prepare(query, schema, identity).flatMap { execute(it, schema) }
            }
    }.doOnError { error -> observe { observer.onError(namedAggregate, queryType, error) } }
        .doFinally { observeTerminal(queryType, it) }

    private fun <Q : RewritableFilter<Q>, T : Any> flux(
        queryType: QueryType,
        query: Q,
        execute: (Q, QueryModelSchema) -> Flux<T>,
    ): Flux<T> = Flux.deferContextual { identity ->
        schemaProvider.schema()
            .switchIfEmpty(Mono.error { IllegalStateException("QueryModelSchemaProvider must emit one schema.") })
            .flatMapMany { schema ->
                prepare(query, schema, identity).flatMapMany { execute(it, schema) }
            }
    }.doOnError { error -> observe { observer.onError(namedAggregate, queryType, error) } }
        .doFinally { observeTerminal(queryType, it) }

    private fun observeTerminal(queryType: QueryType, signal: SignalType) = observe {
        when (signal) {
            SignalType.ON_COMPLETE -> observer.onComplete(namedAggregate, queryType)
            SignalType.CANCEL -> observer.onCancel(namedAggregate, queryType)
            else -> Unit
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun observe(callback: () -> Unit) {
        try {
            callback()
        } catch (failure: Throwable) {
            Exceptions.throwIfFatal(failure)
            log.error(failure) { "Query observer failed." }
        }
    }

    private fun <T : Any> single(query: ISingleQuery, materialize: (ObjectNode) -> T): Mono<T> =
        mono(QueryType.SINGLE, query) { prepared, schema ->
            val accepted = validateQuery(prepared, schema)
            val mask = SchemaMasker.create(schema)
            backend.single(accepted, schema).map { materialize(mask?.mask(it) ?: it) }
        }

    private fun <T : Any> list(query: IListQuery, materialize: (ObjectNode) -> T): Flux<T> =
        flux(QueryType.LIST, query) { prepared, schema ->
            val accepted = validateQuery(prepared, schema)
            val mask = SchemaMasker.create(schema)
            backend.list(accepted, schema).map { materialize(mask?.mask(it) ?: it) }
        }

    private fun <T : Any> paged(query: IPagedQuery, materialize: (ObjectNode) -> T): Mono<PagedList<T>> =
        mono(QueryType.PAGED, query) { prepared, schema ->
            val accepted = validateQuery(prepared, schema)
            val mask = SchemaMasker.create(schema)
            backend.paged(accepted, schema).map { page ->
                PagedList(page.total, page.list.map { materialize(mask?.mask(it) ?: it) })
            }
        }

    private fun <T : Any> cursor(query: ICursorQuery, materialize: (ObjectNode) -> T): Mono<CursorPage<T>> =
        mono(QueryType.CURSOR, query) { prepared, schema ->
            val uniqueField = when (schema.model) {
                QueryModel.SNAPSHOT -> MessageRecords.AGGREGATE_ID
                QueryModel.EVENT_STREAM -> MessageRecords.ID
                else -> throw QuerySchemaValidationException(
                    "Cursor identity is not defined for model [${schema.model.value}]."
                )
            }
            val accepted = validateQuery(prepared.withUniqueSort(QueryField(uniqueField)), schema)
            val mask = SchemaMasker.create(schema)
            backend.cursor(accepted, schema).map { page ->
                CursorPage(page.list.map { materialize(mask?.mask(it) ?: it) }, page.nextCursor)
            }
        }

    override fun single(query: ISingleQuery): Mono<R> = single(query) { it.toObject<R>(targetType) }
    override fun dynamicSingle(query: ISingleQuery): Mono<ObjectNode> = single(query) { it }
    override fun list(query: IListQuery): Flux<R> = list(query) { it.toObject<R>(targetType) }
    override fun dynamicList(query: IListQuery): Flux<ObjectNode> = list(query) { it }
    override fun paged(query: IPagedQuery): Mono<PagedList<R>> = paged(query) { it.toObject<R>(targetType) }
    override fun dynamicPaged(query: IPagedQuery): Mono<PagedList<ObjectNode>> = paged(query) { it }
    override fun cursor(query: ICursorQuery): Mono<CursorPage<R>> = cursor(query) { it.toObject<R>(targetType) }
    override fun dynamicCursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> = cursor(query) { it }
    override fun count(filter: FilterExpression): Mono<Long> = mono(QueryType.COUNT, filter) { prepared, schema ->
        backend.count(validateQuery(prepared, schema), schema)
    }

    override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = flux(
        QueryType.AGGREGATION,
        query
    ) { prepared, schema ->
        backend.aggregate(validateQuery(prepared, schema), schema)
    }

    private companion object {
        val log = KotlinLogging.logger { }
    }
}

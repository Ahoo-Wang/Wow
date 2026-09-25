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
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.mask.SchemaMasker
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.requireIdentityField
import me.ahoo.wow.query.schema.validateQuery
import me.ahoo.wow.serialization.toObject
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
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
    private val entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
) : QueryGateway<R> {
    private val backend = binding.backend
    private val schemaProvider = binding.schemaProvider
    private val preparer = QueryPreparer(
        namedAggregate,
        filters.filter { it::class.scanAnnotation<FilterType>()?.value?.contains(filterType) ?: true },
        policies,
    )

    private fun <Q : RewritableFilter<Q>, T : Any> mono(
        queryType: QueryType,
        query: Q,
        execute: (Q, QueryModelSchema) -> Mono<T>,
    ): Mono<T> = Mono.deferContextual { identity ->
        entryPolicy.admit(identity.queryEntry())
        schema().flatMap { schema -> preparer.prepare(query, schema, identity).flatMap { execute(it, schema) } }
    }.doOnError { error -> observe { observer.onError(namedAggregate, queryType, error) } }
        .doFinally { observeTerminal(queryType, it) }

    private fun <Q : RewritableFilter<Q>, T : Any> flux(
        queryType: QueryType,
        query: Q,
        execute: (Q, QueryModelSchema) -> Flux<T>,
    ): Flux<T> = Flux.deferContextual { identity ->
        entryPolicy.admit(identity.queryEntry())
        schema().flatMapMany { schema -> preparer.prepare(query, schema, identity).flatMapMany { execute(it, schema) } }
    }.doOnError { error -> observe { observer.onError(namedAggregate, queryType, error) } }
        .doFinally { observeTerminal(queryType, it) }

    private fun schema(): Mono<QueryModelSchema> = schemaProvider.schema()
        .switchIfEmpty(Mono.error { IllegalStateException("QueryModelSchemaProvider must emit one schema.") })

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

    /** Masks each backend record by the schema's response masks before [materialize] shapes it. */
    private fun <T : Any> QueryModelSchema.reader(materialize: (ObjectNode) -> T): (ObjectNode) -> T {
        val masker = SchemaMasker.create(this) ?: return materialize
        return { materialize(masker.mask(it)) }
    }

    private fun <T : Any> single(query: ISingleQuery, materialize: (ObjectNode) -> T): Mono<T> =
        mono(QueryType.SINGLE, query) { prepared, schema ->
            backend.single(validateQuery(prepared, schema), schema).map(schema.reader(materialize))
        }

    private fun <T : Any> list(query: IListQuery, materialize: (ObjectNode) -> T): Flux<T> =
        flux(QueryType.LIST, query) { prepared, schema ->
            backend.list(validateQuery(prepared, schema), schema).map(schema.reader(materialize))
        }

    private fun <T : Any> paged(query: IPagedQuery, materialize: (ObjectNode) -> T): Mono<PagedList<T>> =
        mono(QueryType.PAGED, query) { prepared, schema ->
            val read = schema.reader(materialize)
            backend.paged(validateQuery(prepared, schema), schema).map { page ->
                PagedList(page.total, page.list.map(read))
            }
        }

    private fun <T : Any> cursor(query: ICursorQuery, materialize: (ObjectNode) -> T): Mono<CursorPage<T>> =
        mono(QueryType.CURSOR, query) { prepared, schema ->
            val read = schema.reader(materialize)
            val accepted = validateQuery(prepared.withUniqueSort(schema.requireIdentityField()), schema)
            backend.cursor(accepted, schema).map { page -> CursorPage(page.list.map(read), page.nextCursor) }
        }

    private fun materialize(record: ObjectNode): R = record.toObject<R>(targetType)

    override fun single(query: ISingleQuery): Mono<R> = single(query, ::materialize)
    override fun dynamicSingle(query: ISingleQuery): Mono<ObjectNode> = single(query) { it }
    override fun list(query: IListQuery): Flux<R> = list(query, ::materialize)
    override fun dynamicList(query: IListQuery): Flux<ObjectNode> = list(query) { it }
    override fun paged(query: IPagedQuery): Mono<PagedList<R>> = paged(query, ::materialize)
    override fun dynamicPaged(query: IPagedQuery): Mono<PagedList<ObjectNode>> = paged(query) { it }
    override fun cursor(query: ICursorQuery): Mono<CursorPage<R>> = cursor(query, ::materialize)
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

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
import me.ahoo.wow.api.query.descriptor.QueryModelDescriptor
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.mask.SchemaMasker
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.describe
import me.ahoo.wow.serialization.toObject
import org.reactivestreams.Publisher
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

    /** The entry policy this gateway admits every query under: the one source of each entry's budget. */
    val entryPolicy: QueryEntryPolicy

    /**
     * How this model can be queried on [entry] (design §7): the capability descriptor of the schema this gateway
     * admits against, under the budget [entryPolicy] gives [entry]. [defaultListSize] is the list size the entry's
     * adapter applies when a list query sends none, or `null`.
     */
    fun describe(entry: QueryEntry, defaultListSize: Int? = null): Mono<QueryModelDescriptor>
}

abstract class AbstractQueryGateway<R : Any>(
    override val namedAggregate: NamedAggregate,
    private val backend: QueryBackend,
    schemaProvider: QueryModelSchemaProvider,
    private val targetType: JavaType,
    filters: List<QueryFilter>,
    filterType: KClass<*>,
    policies: List<QueryPolicy>,
    private val observer: QueryObserver,
    final override val entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
) : QueryGateway<R> {
    private val schema = Mono.defer { schemaProvider.schema() }
    private val admission = QueryAdmission(
        namedAggregate,
        filters.filter { it::class.scanAnnotation<FilterType>()?.value?.contains(filterType) ?: true },
        policies,
        entryPolicy,
    )

    /**
     * Runs one query: [admission] admits it once per subscription, [execute] runs the admitted query, and the
     * observer and audit trail see the outcome. A single-valued operation reads the one element back with
     * [Flux.singleOrEmpty], which completes rather than cancels the source, so its terminal signal stays exact.
     */
    private fun <Q : RewritableFilter<Q>, T : Any> run(
        operation: QueryOperation<Q>,
        query: Q,
        rows: (T) -> Long = { 1 },
        execute: (AdmittedQuery<Q>) -> Publisher<T>,
    ): Flux<T> = Flux.deferContextual { identity ->
        val trail = trail(operation.queryType, query, identity)
        val result = admission.admit(operation, query, schema, identity, trail).flatMapMany(execute)
        if (trail == null) {
            result
        } else {
            result.doOnNext { trail.rows(rows(it)) }
                .doOnError(trail::error)
                .doFinally { signal -> audit(trail, signal) }
        }
    }.doOnError { error -> observe { observer.onError(namedAggregate, operation.queryType, error) } }
        .doFinally { observeTerminal(operation.queryType, it) }

    private fun trail(queryType: QueryType, query: Any, identity: ContextView): QueryAuditTrail? =
        if (observer.audits) QueryAuditTrail(namedAggregate, queryType, query, identity) else null

    private fun audit(trail: QueryAuditTrail, signal: SignalType) = observe {
        val outcome = when (signal) {
            SignalType.ON_ERROR -> QueryAudit.Outcome.ERROR
            SignalType.CANCEL -> QueryAudit.Outcome.CANCEL
            else -> QueryAudit.Outcome.COMPLETE
        }
        observer.onAudit(trail.audit(outcome))
    }

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
        run(QueryOperation.SINGLE, query) { admitted ->
            backend.single(admitted).map(admitted.schema.reader(materialize))
        }.singleOrEmpty()

    private fun <T : Any> list(query: IListQuery, materialize: (ObjectNode) -> T): Flux<T> =
        run(QueryOperation.LIST, query) { admitted ->
            backend.list(admitted).map(admitted.schema.reader(materialize))
        }

    private fun <T : Any> paged(query: IPagedQuery, materialize: (ObjectNode) -> T): Mono<PagedList<T>> =
        run(QueryOperation.PAGED, query, rows = { it.list.size.toLong() }) { admitted ->
            val read = admitted.schema.reader(materialize)
            backend.paged(admitted).map { page -> PagedList(page.total, page.list.map(read)) }
        }.singleOrEmpty()

    private fun <T : Any> cursor(query: ICursorQuery, materialize: (ObjectNode) -> T): Mono<CursorPage<T>> =
        run(QueryOperation.CURSOR, query, rows = { it.list.size.toLong() }) { admitted ->
            val read = admitted.schema.reader(materialize)
            backend.cursor(admitted).map { page -> CursorPage(page.list.map(read), page.nextCursor) }
        }.singleOrEmpty()

    private fun materialize(record: ObjectNode): R = record.toObject<R>(targetType)

    override fun single(query: ISingleQuery): Mono<R> = single(query, ::materialize)
    override fun dynamicSingle(query: ISingleQuery): Mono<ObjectNode> = single(query) { it }
    override fun list(query: IListQuery): Flux<R> = list(query, ::materialize)
    override fun dynamicList(query: IListQuery): Flux<ObjectNode> = list(query) { it }
    override fun paged(query: IPagedQuery): Mono<PagedList<R>> = paged(query, ::materialize)
    override fun dynamicPaged(query: IPagedQuery): Mono<PagedList<ObjectNode>> = paged(query) { it }
    override fun cursor(query: ICursorQuery): Mono<CursorPage<R>> = cursor(query, ::materialize)
    override fun dynamicCursor(query: ICursorQuery): Mono<CursorPage<ObjectNode>> = cursor(query) { it }
    override fun count(filter: FilterExpression): Mono<Long> =
        run(QueryOperation.COUNT, filter) { admitted -> backend.count(admitted) }.singleOrEmpty()

    override fun aggregate(query: AggregationQuery): Flux<ObjectNode> =
        run(QueryOperation.AGGREGATION, query) { admitted ->
            backend.aggregate(admitted, entryPolicy.budget(admitted.entry))
        }

    override fun describe(entry: QueryEntry, defaultListSize: Int?): Mono<QueryModelDescriptor> =
        schema.map { it.describe(entryPolicy.budget(entry), defaultListSize) }

    private companion object {
        val log = KotlinLogging.logger { }
    }
}

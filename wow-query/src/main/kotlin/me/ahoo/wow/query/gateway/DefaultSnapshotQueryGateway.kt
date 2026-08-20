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

package me.ahoo.wow.query.gateway

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.api.query.QueryBudget
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.QueryExpression
import me.ahoo.wow.api.query.QueryPage
import me.ahoo.wow.api.query.QueryScope
import me.ahoo.wow.api.query.QueryStage
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryRouter
import me.ahoo.wow.query.backend.SecuredQuery
import me.ahoo.wow.query.policy.CompositeQueryPolicy
import me.ahoo.wow.query.policy.QueryCallContext
import me.ahoo.wow.query.policy.QueryContexts
import me.ahoo.wow.query.policy.QueryOperation
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryResultKind
import me.ahoo.wow.query.policy.SystemQueryPolicy
import me.ahoo.wow.query.result.QueryMaterializer
import me.ahoo.wow.query.result.QueryResultContext
import me.ahoo.wow.query.result.QueryResultPolicy
import me.ahoo.wow.query.result.QueryResultPolicyChain
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QuerySchemaProvider
import reactor.core.Exceptions
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal class DefaultSnapshotQueryGatewayFactory(
    private val schemaProvider: QuerySchemaProvider,
    private val router: QueryRouter,
    objectMapper: ObjectMapper,
    policies: List<QueryPolicy>,
    resultPolicies: List<QueryResultPolicy>,
    limits: QueryLimits,
    private val clock: Clock,
    zoneId: ZoneId
) : SnapshotQueryGatewayFactory {
    private val preparer = QueryPreparer(
        CompositeQueryPolicy(listOf(SystemQueryPolicy(limits.maximumBudget)) + policies),
        limits,
        zoneId,
        clock
    )
    private val resultPolicy = QueryResultPolicyChain(resultPolicies.toList())
    private val materializer = QueryMaterializer(objectMapper)

    override fun <S : Any> create(metadata: AggregateMetadata<*, S>): SnapshotQueryGateway<S> {
        val schema = schemaProvider.getSchema(metadata).validatedSnapshot()
        return DefaultSnapshotQueryGateway(
            metadata,
            schema,
            preparer,
            router,
            resultPolicy,
            materializer,
            clock
        )
    }
}

internal class DefaultSnapshotQueryGateway<S : Any>(
    private val metadata: AggregateMetadata<*, S>,
    private val schema: QuerySchema,
    private val preparer: QueryPreparer,
    private val router: QueryRouter,
    private val resultPolicy: QueryResultPolicyChain,
    private val materializer: QueryMaterializer,
    private val clock: Clock
) : SnapshotQueryGateway<S> {
    override val namedAggregate: NamedAggregate = metadata.namedAggregate.materialize()
    override val aggregateType: Class<S> = metadata.state.aggregateType

    override fun first(query: Query): Mono<MaterializedSnapshot<S>> = monoQuery(
        query,
        QueryOperation.FIRST,
        QueryResultKind.SNAPSHOT
    ) { backend, secured, context ->
        records(backend.stream(secured), secured, context)
            .next()
            .map { materializer.snapshot(it, metadata) }
    }

    override fun firstRecord(query: Query): Mono<ObjectNode> = monoQuery(
        query,
        QueryOperation.FIRST,
        QueryResultKind.RECORD
    ) { backend, secured, context ->
        records(backend.stream(secured), secured, context)
            .next()
            .map { materializer.record(it, secured.projection) }
    }

    override fun stream(query: Query): Flux<MaterializedSnapshot<S>> = executeStream(query, null)

    override fun stream(query: Query, limit: Int): Flux<MaterializedSnapshot<S>> = executeStream(query, limit)

    private fun executeStream(query: Query, limit: Int?): Flux<MaterializedSnapshot<S>> = fluxQuery(
        query,
        QueryResultKind.SNAPSHOT,
        limit
    ) { record, _ -> materializer.snapshot(record, metadata) }

    override fun streamRecords(query: Query): Flux<ObjectNode> = executeRecordStream(query, null)

    override fun streamRecords(query: Query, limit: Int): Flux<ObjectNode> = executeRecordStream(query, limit)

    private fun executeRecordStream(query: Query, limit: Int?): Flux<ObjectNode> = fluxQuery(
        query,
        QueryResultKind.RECORD,
        limit
    ) { record, secured -> materializer.record(record, secured.projection) }

    override fun page(query: Query, page: Int, size: Int): Mono<QueryPage<MaterializedSnapshot<S>>> = pageQuery(
        query,
        page,
        size,
        QueryResultKind.SNAPSHOT
    ) { record, _ -> materializer.snapshot(record, metadata) }

    override fun pageRecords(query: Query, page: Int, size: Int): Mono<QueryPage<ObjectNode>> = pageQuery(
        query,
        page,
        size,
        QueryResultKind.RECORD
    ) { record, secured -> materializer.record(record, secured.projection) }

    override fun count(filter: QueryExpression, scope: QueryScope, budget: QueryBudget): Mono<Long> =
        Mono.deferContextual { reactorContext ->
            val call = QueryCallContext(QueryContexts.authority(reactorContext), clock.instant())
            preparer.prepare(
                metadata,
                schema,
                Query(filter = filter, scope = scope, budget = budget),
                QueryOperation.COUNT,
                QueryResultKind.COUNT,
                call
            ).flatMap { secured ->
                val backend = route(secured)
                withDeadline(backend.count(secured), secured)
            }
        }.onErrorMap(::mapMonoError)

    private fun <R : Any> monoQuery(
        query: Query,
        operation: QueryOperation,
        resultKind: QueryResultKind,
        execute: (QueryBackend, SecuredQuery, QueryResultContext) -> Mono<R>
    ): Mono<R> = Mono.deferContextual { reactorContext ->
        val call = QueryCallContext(QueryContexts.authority(reactorContext), clock.instant())
        preparer.prepare(metadata, schema, query, operation, resultKind, call)
            .flatMap { secured ->
                val backend = route(secured)
                withDeadline(
                    execute(backend, secured, QueryResultContext(secured, call.authority, call.subscribedAt)),
                    secured
                )
            }
    }.onErrorMap(::mapMonoError)

    private fun <R : Any> fluxQuery(
        query: Query,
        resultKind: QueryResultKind,
        limit: Int?,
        materialize: (ObjectNode, SecuredQuery) -> R
    ): Flux<R> = Flux.deferContextual { reactorContext ->
        val call = QueryCallContext(QueryContexts.authority(reactorContext), clock.instant())
        preparer.prepare(
            metadata,
            schema,
            query,
            QueryOperation.STREAM,
            resultKind,
            call,
            limit = limit
        ).flatMapMany { secured ->
            val backend = route(secured)
            val context = QueryResultContext(secured, call.authority, call.subscribedAt)
            withDeadline(records(backend.stream(secured), secured, context).map { materialize(it, secured) }, secured)
        }
    }.let(::mapStreamErrors)

    private fun <R : Any> pageQuery(
        query: Query,
        page: Int,
        size: Int,
        resultKind: QueryResultKind,
        materialize: (ObjectNode, SecuredQuery) -> R
    ): Mono<QueryPage<R>> = Mono.deferContextual { reactorContext ->
        val call = QueryCallContext(QueryContexts.authority(reactorContext), clock.instant())
        preparer.prepare(
            metadata,
            schema,
            query,
            QueryOperation.PAGE,
            resultKind,
            call,
            page,
            size
        ).flatMap { secured ->
            val backend = route(secured)
            val context = QueryResultContext(secured, call.authority, call.subscribedAt)
            withDeadline(
                backend.page(secured).map { result ->
                    val limit = secured.limit ?: throw QueryException(
                        QueryErrorCode.RESULT_INVALID,
                        QueryStage.BACKEND
                    )
                    if (result.items.size > limit) {
                        throw QueryException(QueryErrorCode.RESULT_INVALID, QueryStage.BACKEND)
                    }
                    QueryPage(
                        result.items.map { resultPolicy.transform(context, it) }.map { materialize(it, secured) },
                        result.total
                    )
                },
                secured
            )
        }
    }.onErrorMap(::mapMonoError)

    private fun records(
        source: Flux<ObjectNode>,
        query: SecuredQuery,
        context: QueryResultContext
    ): Flux<ObjectNode> {
        val limited = query.limit?.let { maximum -> source.take(maximum.toLong()) } ?: source
        val bounded = query.budget.maxRecords?.let { maximum ->
            limited.index().handle<ObjectNode> { indexed, sink ->
                if (indexed.t1 >= maximum) {
                    sink.error(QueryException(QueryErrorCode.BUDGET_EXCEEDED, QueryStage.BACKEND))
                } else {
                    sink.next(indexed.t2)
                }
            }
        } ?: limited
        return bounded.map { record -> resultPolicy.transform(context, record) }
    }

    private fun route(query: SecuredQuery): QueryBackend = try {
        router.route(query).also { it.validate(query) }
    } catch (error: QueryException) {
        throw error
    } catch (@Suppress("TooGenericExceptionCaught") error: RuntimeException) {
        throw QueryException(QueryErrorCode.UNSUPPORTED_QUERY, QueryStage.ROUTING)
    }

    private fun <T : Any> withDeadline(publisher: Mono<T>, query: SecuredQuery): Mono<T> {
        val timeout = remaining(query) ?: return publisher
        return publisher.timeout(timeout).onErrorMap(TimeoutException::class.java) {
            QueryException(QueryErrorCode.DEADLINE_EXCEEDED, QueryStage.BACKEND)
        }
    }

    private fun <T : Any> withDeadline(publisher: Flux<T>, query: SecuredQuery): Flux<T> {
        if (query.deadline == null) return publisher
        return publisher.timeout(
            timeoutSignal(query),
            { timeoutSignal(query) }
        ).onErrorMap(TimeoutException::class.java) {
            QueryException(QueryErrorCode.DEADLINE_EXCEEDED, QueryStage.BACKEND)
        }
    }

    private fun timeoutSignal(query: SecuredQuery): Mono<Long> = Mono.delay(checkNotNull(remaining(query)))

    private fun remaining(query: SecuredQuery): Duration? = query.deadline?.let { deadline ->
        Duration.between(clock.instant(), deadline).also { remaining ->
            if (remaining.isNegative || remaining.isZero) {
                throw QueryException(QueryErrorCode.DEADLINE_EXCEEDED, QueryStage.BACKEND)
            }
        }
    }

    private fun <T : Any> mapStreamErrors(source: Flux<T>): Flux<T> = Flux.defer {
        val emitted = AtomicBoolean()
        source.doOnNext { emitted.set(true) }.onErrorMap { error ->
            val mapped = mapMonoError(error)
            if (emitted.get()) {
                QueryException(
                    QueryErrorCode.INCOMPLETE_RESULT,
                    QueryStage.BACKEND,
                    safeCause(mapped)
                )
            } else {
                mapped
            }
        }
    }

    private fun mapMonoError(error: Throwable): Throwable {
        Exceptions.throwIfFatal(error)
        return if (error is QueryException) {
            error
        } else {
            QueryException(
                QueryErrorCode.BACKEND_FAILURE,
                QueryStage.BACKEND
            )
        }
    }

    private fun safeCause(error: Throwable): Throwable =
        QueryException((error as? QueryException)?.code ?: QueryErrorCode.BACKEND_FAILURE, QueryStage.BACKEND)
}

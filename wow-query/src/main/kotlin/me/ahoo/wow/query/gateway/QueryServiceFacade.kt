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

@file:OptIn(ExperimentalQueryCursorApi::class, ExperimentalQueryGatewayApi::class)

package me.ahoo.wow.query.gateway

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.query.analytics.AnalyticsQueryService
import me.ahoo.wow.query.analytics.AnalyticsQueryServiceFactory
import me.ahoo.wow.query.analytics.AnalyticsQueryTrustedContextRequest
import me.ahoo.wow.query.analytics.AnalyticsQueryTrustedContextResolver
import me.ahoo.wow.query.backend.ExperimentalQueryBackendApi
import me.ahoo.wow.query.backend.QueryBackendComposition
import me.ahoo.wow.query.cursor.ExperimentalQueryCursorApi
import me.ahoo.wow.query.cursor.QueryCursorLeaseConfiguration
import me.ahoo.wow.query.event.AbstractEventStreamQueryServiceFactory
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.internal.gateway.QueryGatewayRuntimeBuilder
import me.ahoo.wow.query.internal.gateway.TrustedAuthorityChannel
import me.ahoo.wow.query.snapshot.AbstractSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.convert
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Clock

@ExperimentalQueryGatewayApi
data class QueryCallResolutionRequest(
    val target: QueryTarget,
    val queryType: QueryType,
)

@ExperimentalQueryGatewayApi
fun interface QueryCallResolver {
    /** Called once for each subscription made through a compatibility QueryService facade. */
    fun resolve(request: QueryCallResolutionRequest): Mono<QueryCall>
}

@ExperimentalQueryGatewayApi
class GatewaySnapshotQueryServiceFactory(
    private val gateway: QueryGateway,
    callResolver: QueryCallResolver,
) : AbstractSnapshotQueryServiceFactory() {
    private val facadeContextResolver = callResolver.asFacadeContextResolver()

    override fun createQueryService(namedAggregate: NamedAggregate): SnapshotQueryService<*> =
        GatewaySnapshotQueryService(namedAggregate.materialize(), gateway, facadeContextResolver)
}

@ExperimentalQueryGatewayApi
class GatewayEventStreamQueryServiceFactory(
    private val gateway: QueryGateway,
    callResolver: QueryCallResolver,
) : AbstractEventStreamQueryServiceFactory() {
    private val facadeContextResolver = callResolver.asFacadeContextResolver()

    override fun createQueryService(namedAggregate: NamedAggregate): EventStreamQueryService =
        GatewayEventStreamQueryService(namedAggregate.materialize(), gateway, facadeContextResolver)
}

private class GatewaySnapshotQueryService(
    override val namedAggregate: NamedAggregate,
    private val gateway: QueryGateway,
    private val facadeContextResolver: FacadeContextResolver,
) : SnapshotQueryService<Any> {
    override val name: String = GATEWAY_QUERY_SERVICE_NAME

    override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<Any>> =
        callMono(QueryType.SINGLE) { call -> gateway.single(call, singleQuery, MATERIALIZED_SNAPSHOT_TYPE) }
            .map(MaterializedSnapshot<*>::eraseStateType)

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> =
        callMono(QueryType.DYNAMIC_SINGLE) { call -> gateway.single(call, singleQuery) }

    override fun list(listQuery: IListQuery): Flux<MaterializedSnapshot<Any>> =
        callFlux(QueryType.LIST) { call -> gateway.stream(call, listQuery, MATERIALIZED_SNAPSHOT_TYPE) }
            .map(MaterializedSnapshot<*>::eraseStateType)

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> =
        callFlux(QueryType.DYNAMIC_LIST) { call -> gateway.stream(call, listQuery) }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<Any>>> =
        callMono(QueryType.PAGED) { call -> gateway.page(call, pagedQuery, MATERIALIZED_SNAPSHOT_TYPE) }
            .map { page ->
                PagedList(page.total, page.list.map(MaterializedSnapshot<*>::eraseStateType))
            }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> =
        callMono(QueryType.DYNAMIC_PAGED) { call -> gateway.page(call, pagedQuery) }

    override fun count(condition: Condition): Mono<Long> =
        callMono(QueryType.COUNT) { call -> gateway.count(call, condition) }

    private fun <R : Any> callMono(queryType: QueryType, source: (QueryCall) -> Mono<R>): Mono<R> =
        resolveFacadeContext(facadeContextResolver, target(), queryType)
            .flatMap { context -> context.applyTo(source(context.call)) }

    private fun <R : Any> callFlux(queryType: QueryType, source: (QueryCall) -> Flux<R>): Flux<R> =
        resolveFacadeContext(facadeContextResolver, target(), queryType)
            .flatMapMany { context -> context.applyTo(source(context.call)) }

    private fun target(): QueryTarget = QueryTarget(namedAggregate, QueryDocumentKind.SNAPSHOT)
}

private class GatewayEventStreamQueryService(
    override val namedAggregate: NamedAggregate,
    private val gateway: QueryGateway,
    private val facadeContextResolver: FacadeContextResolver,
) : EventStreamQueryService {
    override fun single(singleQuery: ISingleQuery): Mono<DomainEventStream> =
        callMono(QueryType.SINGLE) { call -> gateway.single(call, singleQuery, DomainEventStream::class.java) }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> =
        callMono(QueryType.DYNAMIC_SINGLE) { call -> gateway.single(call, singleQuery) }

    override fun list(listQuery: IListQuery): Flux<DomainEventStream> =
        callFlux(QueryType.LIST) { call -> gateway.stream(call, listQuery, DomainEventStream::class.java) }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> =
        callFlux(QueryType.DYNAMIC_LIST) { call -> gateway.stream(call, listQuery) }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<DomainEventStream>> =
        callMono(QueryType.PAGED) { call -> gateway.page(call, pagedQuery, DomainEventStream::class.java) }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> =
        callMono(QueryType.DYNAMIC_PAGED) { call -> gateway.page(call, pagedQuery) }

    override fun count(condition: Condition): Mono<Long> =
        callMono(QueryType.COUNT) { call -> gateway.count(call, condition) }

    private fun <R : Any> callMono(queryType: QueryType, source: (QueryCall) -> Mono<R>): Mono<R> =
        resolveFacadeContext(facadeContextResolver, target(), queryType)
            .flatMap { context -> context.applyTo(source(context.call)) }

    private fun <R : Any> callFlux(queryType: QueryType, source: (QueryCall) -> Flux<R>): Flux<R> =
        resolveFacadeContext(facadeContextResolver, target(), queryType)
            .flatMapMany { context -> context.applyTo(source(context.call)) }

    private fun target(): QueryTarget = QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM)
}

private fun resolveFacadeContext(
    resolver: FacadeContextResolver,
    target: QueryTarget,
    queryType: QueryType,
): Mono<ResolvedFacadeContext> = Mono.defer {
    resolver.resolveContext(QueryCallResolutionRequest(target, queryType))
}.onErrorMap { error ->
    if (error is QueryExecutionException) {
        error
    } else {
        queryCallError("QUERY_CALL_RESOLUTION_FAILED", error)
    }
}.switchIfEmpty(Mono.error(queryCallError("QUERY_CALL_REQUIRED")))
    .map { context ->
        if (context.call.target != target) {
            throw queryCallError("QUERY_CALL_TARGET_MISMATCH")
        }
        context
    }

private fun interface FacadeContextResolver {
    fun resolveContext(request: QueryCallResolutionRequest): Mono<ResolvedFacadeContext>
}

private data class ResolvedFacadeContext(
    val call: QueryCall,
    val authority: QueryAuthority?,
    val trustedAuthorityChannel: TrustedAuthorityChannel?,
) {
    fun <R : Any> applyTo(source: Mono<R>): Mono<R> =
        authority?.let { trustedAuthorityChannel!!.bind(source, it) } ?: source

    fun <R : Any> applyTo(source: Flux<R>): Flux<R> =
        authority?.let { trustedAuthorityChannel!!.bind(source, it) } ?: source
}

private class CallOnlyFacadeContextResolver(
    private val callResolver: QueryCallResolver,
) : FacadeContextResolver {
    override fun resolveContext(request: QueryCallResolutionRequest): Mono<ResolvedFacadeContext> =
        callResolver.resolve(request).map { call -> ResolvedFacadeContext(call, null, null) }
}

private class TrustedFacadeContextResolver(
    private val trustedContextResolver: QueryTrustedContextResolver,
    private val executionProfiles: QueryExecutionProfiles,
    private val trustedAuthorityChannel: TrustedAuthorityChannel,
) : QueryCallResolver, FacadeContextResolver {
    override fun resolve(request: QueryCallResolutionRequest): Mono<QueryCall> =
        resolveContext(request).map(ResolvedFacadeContext::call)

    override fun resolveContext(request: QueryCallResolutionRequest): Mono<ResolvedFacadeContext> =
        executionProfiles.resolve(request.target, request.queryType.toOperation()).let { profile ->
            trustedContextResolver.resolve(
                QueryTrustedContextRequest(
                    request,
                    profile.executionMode,
                    profile.validationMode,
                ),
            )
        }.map { context -> ResolvedFacadeContext(context.call, context.authority, trustedAuthorityChannel) }
}

private fun QueryType.toOperation(): QueryOperation =
    when (this) {
        QueryType.SINGLE,
        QueryType.DYNAMIC_SINGLE,
        -> QueryOperation.SINGLE

        QueryType.LIST,
        QueryType.DYNAMIC_LIST,
        -> QueryOperation.STREAM

        QueryType.PAGED,
        QueryType.DYNAMIC_PAGED,
        -> QueryOperation.PAGE

        QueryType.COUNT -> QueryOperation.COUNT
    }

private fun QueryCallResolver.asFacadeContextResolver(): FacadeContextResolver =
    this as? FacadeContextResolver ?: CallOnlyFacadeContextResolver(this)

/**
 * Owns one immutable Gateway runtime for the supplied aggregate set.
 *
 * The raw source has a distinct type from the public application factories, so the runtime cannot recursively resolve
 * its own facade. The trusted resolver and its per-runtime authority capability are frozen at construction time.
 */
@ExperimentalQueryGatewayApi
class QueryGatewayRuntime private constructor(private val state: RuntimeState) {
    val gateway: QueryGateway
        get() = state.gateway

    val analyticsGateway: AnalyticsQueryGateway
        get() = state.analyticsGateway

    /** Reaps one bounded batch of expired cursor leases and closes their backend-owned resources. */
    @ExperimentalQueryCursorApi
    fun reapExpiredQueryCursors(batchSize: Int = 100): Mono<Long> = state.cursorReaper(batchSize)

    fun snapshotQueryServiceFactory(): GatewaySnapshotQueryServiceFactory = GatewaySnapshotQueryServiceFactory(
        state.gateway,
        state.trustedCallResolver,
    )

    fun eventStreamQueryServiceFactory(): GatewayEventStreamQueryServiceFactory = GatewayEventStreamQueryServiceFactory(
        state.gateway,
        state.trustedCallResolver,
    )

    fun analyticsQueryServiceFactory(): AnalyticsQueryServiceFactory = GatewayAnalyticsQueryServiceFactory(
        state.analyticsGateway,
        state.executionProfiles,
        state.trustedAuthorityChannel,
        state.analyticsTrustedContextResolver,
    )

    private class RuntimeState(
        val gateway: QueryGateway,
        val analyticsGateway: AnalyticsQueryGateway,
        val trustedCallResolver: QueryCallResolver,
        val executionProfiles: QueryExecutionProfiles,
        val trustedAuthorityChannel: TrustedAuthorityChannel,
        val analyticsTrustedContextResolver: AnalyticsQueryTrustedContextResolver,
        val cursorReaper: (Int) -> Mono<Long>,
    )

    companion object {
        fun create(
            namedAggregates: Iterable<NamedAggregate>,
            rawServiceSource: QueryRawServiceSource,
            dialectResolver: QueryLegacyDialectResolver,
            authorityResolver: QueryAuthorityResolver,
            trustedContextResolver: QueryTrustedContextResolver = QueryTrustedContextResolver { Mono.empty() },
            resultMaterializers: Iterable<QueryResultMaterializer<*>> = emptyList(),
            configuration: QueryGatewayConfiguration = QueryGatewayConfiguration(),
            clock: Clock = Clock.systemUTC(),
            scheduler: Scheduler = Schedulers.parallel(),
        ): QueryGatewayRuntime = createRuntime(
            namedAggregates,
            rawServiceSource,
            dialectResolver,
            authorityResolver,
            trustedContextResolver,
            resultMaterializers,
            clock,
            scheduler,
            QueryBackendComposition.EMPTY,
            QueryExecutionProfiles.fixed(configuration),
            QueryShadowConfiguration(),
            QueryShadowObserver.NONE,
            QueryRuntimeHealthObserver.NONE,
            null,
        )

        @ExperimentalQueryBackendApi
        fun create(
            namedAggregates: Iterable<NamedAggregate>,
            backendComposition: QueryBackendComposition,
            rawServiceSource: QueryRawServiceSource,
            dialectResolver: QueryLegacyDialectResolver,
            authorityResolver: QueryAuthorityResolver,
            trustedContextResolver: QueryTrustedContextResolver = QueryTrustedContextResolver { Mono.empty() },
            resultMaterializers: Iterable<QueryResultMaterializer<*>> = emptyList(),
            configuration: QueryGatewayConfiguration = QueryGatewayConfiguration(),
            executionProfiles: QueryExecutionProfiles = QueryExecutionProfiles.fixed(configuration),
            shadowConfiguration: QueryShadowConfiguration = QueryShadowConfiguration(),
            shadowObserver: QueryShadowObserver = QueryShadowObserver.NONE,
            runtimeHealthObserver: QueryRuntimeHealthObserver = QueryRuntimeHealthObserver.NONE,
            clock: Clock = Clock.systemUTC(),
            scheduler: Scheduler = Schedulers.parallel(),
        ): QueryGatewayRuntime = createRuntime(
            namedAggregates,
            rawServiceSource,
            dialectResolver,
            authorityResolver,
            trustedContextResolver,
            resultMaterializers,
            clock,
            scheduler,
            backendComposition,
            executionProfiles,
            shadowConfiguration,
            shadowObserver,
            runtimeHealthObserver,
            null,
        )

        @ExperimentalQueryBackendApi
        @ExperimentalQueryCursorApi
        fun create(
            namedAggregates: Iterable<NamedAggregate>,
            backendComposition: QueryBackendComposition,
            cursorLeaseConfiguration: QueryCursorLeaseConfiguration,
            rawServiceSource: QueryRawServiceSource,
            dialectResolver: QueryLegacyDialectResolver,
            authorityResolver: QueryAuthorityResolver,
            trustedContextResolver: QueryTrustedContextResolver = QueryTrustedContextResolver { Mono.empty() },
            resultMaterializers: Iterable<QueryResultMaterializer<*>> = emptyList(),
            configuration: QueryGatewayConfiguration = QueryGatewayConfiguration(),
            executionProfiles: QueryExecutionProfiles = QueryExecutionProfiles.fixed(configuration),
            shadowConfiguration: QueryShadowConfiguration = QueryShadowConfiguration(),
            shadowObserver: QueryShadowObserver = QueryShadowObserver.NONE,
            runtimeHealthObserver: QueryRuntimeHealthObserver = QueryRuntimeHealthObserver.NONE,
            clock: Clock = Clock.systemUTC(),
            scheduler: Scheduler = Schedulers.parallel(),
        ): QueryGatewayRuntime = createRuntime(
            namedAggregates,
            rawServiceSource,
            dialectResolver,
            authorityResolver,
            trustedContextResolver,
            resultMaterializers,
            clock,
            scheduler,
            backendComposition,
            executionProfiles,
            shadowConfiguration,
            shadowObserver,
            runtimeHealthObserver,
            cursorLeaseConfiguration,
        )

        @OptIn(ExperimentalQueryBackendApi::class)
        private fun createRuntime(
            namedAggregates: Iterable<NamedAggregate>,
            rawServiceSource: QueryRawServiceSource,
            dialectResolver: QueryLegacyDialectResolver,
            authorityResolver: QueryAuthorityResolver,
            trustedContextResolver: QueryTrustedContextResolver,
            resultMaterializers: Iterable<QueryResultMaterializer<*>>,
            clock: Clock,
            scheduler: Scheduler,
            backendComposition: QueryBackendComposition,
            executionProfiles: QueryExecutionProfiles,
            shadowConfiguration: QueryShadowConfiguration,
            shadowObserver: QueryShadowObserver,
            runtimeHealthObserver: QueryRuntimeHealthObserver,
            cursorLeaseConfiguration: QueryCursorLeaseConfiguration?,
        ): QueryGatewayRuntime {
            val analyticsTrustedContextResolver =
                (trustedContextResolver as? AnalyticsQueryTrustedContextResolver)
                    ?: AnalyticsQueryTrustedContextResolver { Mono.empty() }
            val components = QueryGatewayRuntimeBuilder.build(
                namedAggregates,
                rawServiceSource,
                resultMaterializers,
                dialectResolver,
                authorityResolver,
                clock,
                scheduler,
                backendComposition,
                executionProfiles,
                shadowConfiguration,
                shadowObserver,
                runtimeHealthObserver,
                cursorLeaseConfiguration,
            )
            return QueryGatewayRuntime(
                RuntimeState(
                    components.gateway,
                    components.analyticsGateway,
                    TrustedFacadeContextResolver(
                        trustedContextResolver,
                        executionProfiles,
                        components.trustedAuthorityChannel,
                    ),
                    executionProfiles,
                    components.trustedAuthorityChannel,
                    analyticsTrustedContextResolver,
                    components.cursorReaper,
                ),
            )
        }
    }
}

private class GatewayAnalyticsQueryServiceFactory(
    private val gateway: AnalyticsQueryGateway,
    private val executionProfiles: QueryExecutionProfiles,
    private val trustedAuthorityChannel: TrustedAuthorityChannel,
    private val resolver: AnalyticsQueryTrustedContextResolver,
) : AnalyticsQueryServiceFactory {
    override fun create(namedAggregate: NamedAggregate): AnalyticsQueryService = GatewayAnalyticsQueryService(
        namedAggregate.materialize(),
        gateway,
        executionProfiles,
        trustedAuthorityChannel,
        resolver,
    )
}

private class GatewayAnalyticsQueryService(
    override val namedAggregate: NamedAggregate,
    private val gateway: AnalyticsQueryGateway,
    private val executionProfiles: QueryExecutionProfiles,
    private val trustedAuthorityChannel: TrustedAuthorityChannel,
    private val resolver: AnalyticsQueryTrustedContextResolver,
) : AnalyticsQueryService {
    override fun analyze(
        query: me.ahoo.wow.api.query.analytics.AnalyticsQuery
    ): Mono<me.ahoo.wow.api.query.analytics.AnalyticsPage> =
        Mono.defer {
            val target = QueryTarget(namedAggregate, QueryDocumentKind.SNAPSHOT)
            val profile = executionProfiles.resolve(target, QueryOperation.ANALYZE)
            Mono.defer {
                resolver.resolve(
                    AnalyticsQueryTrustedContextRequest(target, profile.executionMode, profile.validationMode),
                )
            }.onErrorMap { error ->
                if (error is QueryExecutionException) error else queryCallError("QUERY_CALL_RESOLUTION_FAILED", error)
            }.switchIfEmpty(Mono.error(queryCallError("QUERY_CALL_REQUIRED")))
                .flatMap { context ->
                    if (context.call.target != target) {
                        return@flatMap Mono.error(queryCallError("QUERY_CALL_TARGET_MISMATCH"))
                    }
                    trustedAuthorityChannel.bind(gateway.analyze(context.call, query), context.authority)
                }
        }
}

@ExperimentalQueryGatewayApi
object QueryResultMaterializers {
    fun snapshot(target: QueryTarget, stateType: Class<*>): QueryResultMaterializer<MaterializedSnapshot<*>> {
        require(target.documentKind == QueryDocumentKind.SNAPSHOT) {
            "Snapshot materializer requires a SNAPSHOT target."
        }
        val snapshotType = JsonSerializer.typeFactory.constructParametricType(
            MaterializedSnapshot::class.java,
            stateType,
        )
        return QueryResultMaterializer(target, MATERIALIZED_SNAPSHOT_TYPE) { identity, document ->
            document.convert<MaterializedSnapshot<*>>(snapshotType).also { snapshot ->
                require(snapshot.aggregateId == identity) {
                    "Materialized snapshot identity does not match the backend record identity."
                }
                require(stateType.isInstance(snapshot.state)) {
                    "Materialized snapshot state does not match the registered state type."
                }
            }
        }
    }

    fun eventStream(target: QueryTarget): QueryResultMaterializer<DomainEventStream> {
        require(target.documentKind == QueryDocumentKind.EVENT_STREAM) {
            "Event-stream materializer requires an EVENT_STREAM target."
        }
        return QueryResultMaterializer(target, DomainEventStream::class.java) { identity, document ->
            document.convert(DomainEventStream::class.java).also { eventStream ->
                require(eventStream.id == identity) {
                    "Materialized event-stream identity does not match the backend record identity."
                }
            }
        }
    }
}

private fun queryCallError(code: String, cause: Throwable? = null): QueryExecutionException =
    QueryExecutionException(QueryErrorCategory.ACCESS_DENIED, "$.executionContext.call", code, cause)

private fun MaterializedSnapshot<*>.eraseStateType(): MaterializedSnapshot<Any> = MaterializedSnapshot(
    contextName = contextName,
    aggregateName = aggregateName,
    tenantId = tenantId,
    ownerId = ownerId,
    spaceId = spaceId,
    aggregateId = aggregateId,
    version = version,
    eventId = eventId,
    firstOperator = firstOperator,
    operator = operator,
    firstEventTime = firstEventTime,
    eventTime = eventTime,
    state = state,
    snapshotTime = snapshotTime,
    tags = tags,
    deleted = deleted,
)

private val MATERIALIZED_SNAPSHOT_TYPE: Class<MaterializedSnapshot<*>> =
    MaterializedSnapshot::class.java

private const val GATEWAY_QUERY_SERVICE_NAME = "QueryGateway"

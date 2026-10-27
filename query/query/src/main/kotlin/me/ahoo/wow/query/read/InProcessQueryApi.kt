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

package me.ahoo.wow.query.read

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.ProjectionCapable
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.read.AggregateState
import me.ahoo.wow.api.read.DatasetName
import me.ahoo.wow.api.read.EventQueries
import me.ahoo.wow.api.read.EventStream
import me.ahoo.wow.api.read.MinVersionNotReachedException
import me.ahoo.wow.api.read.Positioned
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.RowQueries
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.read.SnapshotQueries
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.read.TrustedQueries
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryScope
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.paged
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.engine.CommittedStates
import me.ahoo.wow.query.engine.QueryEngine
import me.ahoo.wow.query.engine.ReadFacade
import me.ahoo.wow.query.policy.queryTenantOf
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.state.CommittedState
import me.ahoo.wow.query.state.StateNotFoundException
import me.ahoo.wow.query.state.StateNotFoundException.Reason
import me.ahoo.wow.query.state.StateRead
import me.ahoo.wow.spi.json.query.decode
import me.ahoo.wow.spi.json.query.toEventStream
import me.ahoo.wow.spi.json.query.toSnapshot
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The node's [QueryApi] (decisions D254 ②, D259): a thin layer over the [engine]'s gateways and read facade, so a query
 * asked in process is admitted, scoped, hidden, bulkheaded, budgeted and resumed as the same query over the public
 * port is; only the rows differ, decoded as the [models]' classes rather than written as JSON.
 *
 * - [of] a caller queries as that caller on [QueryEntry.IN_PROCESS]: the scope policy restricts it to the tenant
 *   the engine's [QueryEngine.tenants] gives it (a type's fixed tenant, else the caller's) and to its owner and space, ABAC and the application's
 *   policies see its attributes; the in-process budget (the engine's [me.ahoo.wow.query.policy.QueryEntryPolicy.inProcess])
 *   bounds it.
 * - [trusted] code queries with no caller on the same entry and budget, [TrustedQueries.unbounded] on
 *   [QueryEntry.UNBOUNDED] without the budget.
 * - A `list` that names no limit runs with the budget's list default ([defaultListSize], at most its list size), as over
 *   HTTP; a `stream` reads keyset chunks of [chunkSize] (decision D253).
 * - A state read ([SnapshotQueries.state]) reads [latest] through the engine's [ReadFacade]: the caller's scope is
 *   checked against the state's (its owner only for an aggregate [QueryEngine.ownerships] says belongs to its owner), then the
 *   snapshot policies of its type, and it is masked and observed as the public port's state read is.
 */
class InProcessQueryApi @InternalWowApi constructor(
    private val engine: QueryEngine,
    private val models: ReadModels,
    private val latest: CommittedStates = CommittedStates.NONE,
    private val defaultListSize: Int = DEFAULT_LIST_SIZE,
    private val chunkSize: Int = QueryGateway.DEFAULT_STREAM_CHUNK_SIZE,
) : QueryApi {
    init {
        require(defaultListSize >= 0) { "defaultListSize must be greater than or equal to 0." }
        require(chunkSize >= 1) { "A stream reads at least one record per chunk, not $chunkSize." }
    }

    private val trusted = Trusted()

    override fun of(caller: Caller): Queries = Scoped(QueryCaller(caller, QueryEntry.IN_PROCESS))

    override fun trusted(): TrustedQueries = trusted

    /**
     * The queries of a subscription handler of a commit of [tenantId] (decision D259): trusted code whose authenticated
     * scope pins [tenantId], so it reads that whole tenant and no other, and [SubscriptionQueries.trusted] for the
     * rest. A type whose tenant is fixed to another one shows such queries nothing.
     */
    @InternalWowApi
    fun tenant(tenantId: String): SubscriptionQueries = TenantScoped(tenantId)

    /** The queries of [caller]; a state read by id reads in the [pinned] tenant when given, else in the caller's. */
    private open inner class Scoped(val caller: QueryCaller, val pinned: String? = null) : Queries {
        override fun <S : Any> snapshots(stateType: Class<S>): SnapshotQueries<S> =
            Snapshots(models.typeOf(stateType), stateType, caller, pinned)

        override fun snapshots(type: AggregateType): SnapshotQueries<Any> {
            @Suppress("UNCHECKED_CAST")
            val stateType = (models.stateTypeOf(type) ?: Map::class.java) as Class<Any>
            return Snapshots(type, stateType, caller, pinned)
        }

        override fun events(type: AggregateType): EventQueries = Events(type, caller)

        @OptIn(ExperimentalWowApi::class)
        override fun <R : Any> datasets(name: DatasetName, rowType: Class<R>): RowQueries<R> {
            val gateway = engine.datasets[AggregateType(name.context, name.name)]?.takeIf {
                engine.datasetVisibility.visible(it.dataset, caller)
            }
            requireNotNull(gateway) { "No dataset $name is served." }
            return Rows(gateway, caller) { decode(it, rowType) }
        }
    }

    private inner class Trusted : Scoped(QueryCaller.IN_PROCESS), TrustedQueries {
        private val unbounded = Scoped(QueryCaller(entry = QueryEntry.UNBOUNDED))

        override fun unbounded(): Queries = unbounded
    }

    private inner class TenantScoped(override val tenantId: String) :
        Scoped(
            QueryCaller(entry = QueryEntry.IN_PROCESS).withScope(QueryScope(authenticated = TenantIdFilter(tenantId))),
            tenantId,
        ),
        SubscriptionQueries {
        override fun trusted(): TrustedQueries = this@InProcessQueryApi.trusted
    }

    /** The queries of [gateway] for [caller], each record read by [row]; refused by [check] first when typed. */
    private open inner class Rows<R : Any>(
        val gateway: QueryGateway,
        val caller: QueryCaller,
        private val check: (Any) -> Unit = {},
        private val row: (ObjectNode) -> R,
    ) : RowQueries<R> {
        override suspend fun single(query: ISingleQuery): R? {
            check(query)
            return gateway.single(query, caller)?.let(row)
        }

        override fun list(query: IListQuery): Flow<R> = flow {
            check(query)
            emitAll(gateway.list(query.withListDefault(), caller).map(row))
        }

        override fun stream(query: IListQuery, after: String?): Flow<Positioned<R>> = flow {
            check(query)
            if (gateway.resumes(query)) {
                emitAll(
                    gateway.stream(query, caller, after, chunkSize).map { Positioned.of(row(it.record), it.position) },
                )
            } else {
                if (after != null) throw QueryViolation.InvalidCursor.rejection()
                emitAll(gateway.list(query.withListDefault(), caller).map { Positioned.of(row(it), null) })
            }
        }

        override suspend fun paged(query: IPagedQuery): PagedList<R> {
            check(query)
            val page = gateway.paged(query, caller)
            return PagedList(page.total, page.list.map(row), page.exact)
        }

        override suspend fun cursor(query: ICursorQuery): CursorPage<R> {
            check(query)
            val page = gateway.cursor(query, caller)
            return CursorPage(page.list.map(row), page.nextCursor)
        }

        override suspend fun count(query: ICountQuery): CountResult = gateway.count(query, caller)

        override fun <G : Any> aggregate(query: AggregationQuery, groupType: Class<G>): Flow<G> =
            gateway.aggregate(query, caller).map { decode(it, groupType) }

        /** This list with the budget's list default when it names no limit (`0`), as the public port runs it. */
        private fun IListQuery.withListDefault(): IListQuery {
            val default = gateway.entryPolicy.budget(caller.entry)?.listDefault(defaultListSize)
            return if (limit != 0 || default == null) {
                this
            } else {
                ListQuery(filter = filter, projection = projection, sort = sort, limit = default)
            }
        }
    }

    private inner class Snapshots<S : Any>(
        override val type: AggregateType,
        private val stateType: Class<S>,
        caller: QueryCaller,
        private val tenantId: String?,
    ) : Rows<Snapshot<S>>(snapshotGateway(type), caller, ::wholeRecords, { it.toSnapshot(type, stateType) }),
        SnapshotQueries<S> {
        override fun <T : Any> dynamic(rowType: Class<T>): RowQueries<T> = Rows(gateway, caller) { decode(it, rowType) }

        override suspend fun state(id: String): AggregateState<S>? {
            val tenant = checkNotNull(tenantOf()) {
                "Trusted queries name no tenant: read the state of $type by its ref."
            }
            return state(type.ref(tenant, id))
        }

        override suspend fun state(ref: AggregateRef): AggregateState<S>? = read(ref) { latest.committed(ref) }

        override suspend fun state(id: String, minVersion: Int): AggregateState<S>? {
            val tenant = checkNotNull(tenantOf()) {
                "Trusted queries name no tenant: read the state of $type by its ref."
            }
            return state(type.ref(tenant, id), minVersion)
        }

        /**
         * Only a state the caller may see answers that it is below [minVersion] (review R4-Q, Q-5): a hidden one, and
         * one with no commit, are not found, as they are without a minimum version.
         */
        override suspend fun state(ref: AggregateRef, minVersion: Int): AggregateState<S>? {
            require(minVersion >= 1) { "A committed state has a version from 1." }
            var notReached: MinVersionNotReachedException? = null
            val state = read(ref) {
                try {
                    latest.committed(ref, minVersion)
                } catch (behind: MinVersionNotReachedException) {
                    notReached = behind
                    if (behind.version == 0) null else latest.committed(ref)
                }
            }
            notReached?.let { if (state != null) throw it }
            return state
        }

        /** The state of [ref] [load] reads, as the public port's state read answers it. */
        private suspend fun read(ref: AggregateRef, load: suspend () -> CommittedState?): AggregateState<S>? {
            require(ref.type == type) { "$ref is not an aggregate of $type." }
            val reads = engine.reads
            return try {
                reads.observed(type, StateRead.LATEST, caller) {
                    when (val read = reads.read(caller, load, { it }) { checkScope(ref, it) }) {
                        is ReadFacade.Read.Visible -> read.view.let {
                            AggregateState.of(it.ref, it.version, it.scope, it.deleted, it.tags, stateOf(read.state))
                        }
                        ReadFacade.Read.Missing -> throw StateNotFoundException(ref, Reason.MISSING)
                        ReadFacade.Read.Hidden -> throw StateNotFoundException(ref, Reason.HIDDEN)
                    }
                }
            } catch (_: StateNotFoundException) {
                null
            }
        }

        private fun stateOf(state: JsonNode): S = decode(state, stateType)

        private fun tenantOf(): String? = tenantId ?: engine.tenants.queryTenantOf(type, caller)

        /** The read of [ref] as the public port's: in the caller's tenant, owner and space, else not found. */
        private fun checkScope(ref: AggregateRef, state: CommittedState) {
            val tenant = tenantOf()
            val scope = caller.caller?.let { engine.ownerships.ownershipOf(type).scopeOf(it.scope) } ?: Scope.NONE
            if (tenant != null && tenant != ref.tenantId || !scope.covers(state.scope)) {
                throw StateNotFoundException(ref, Reason.OUT_OF_SCOPE)
            }
        }
    }

    private inner class Events(override val type: AggregateType, caller: QueryCaller) :
        Rows<EventStream>(
            eventGateway(type),
            caller,
            ::wholeRecords,
            { record -> record.toEventStream(type) { models.eventTypeOf(type, it) } },
        ),
        EventQueries {
        override fun <T : Any> dynamic(rowType: Class<T>): RowQueries<T> = Rows(gateway, caller) { decode(it, rowType) }
    }

    private fun snapshotGateway(type: AggregateType): QueryGateway =
        requireNotNull(engine.snapshots(type)) { "No snapshot query of $type is served on this node." }

    private fun eventGateway(type: AggregateType): QueryGateway =
        requireNotNull(engine.events(type)) { "No events query of $type is served on this node." }

    companion object {
        /** The list size a `list` that names none runs with by default, the public port's. */
        const val DEFAULT_LIST_SIZE: Int = 100

        /** A typed row reads the whole record: a projection leaves out fields it needs (decision D259). */
        private fun wholeRecords(query: Any) {
            val projection = (query as? ProjectionCapable<*>)?.projection ?: return
            require(projection.include.isEmpty() && projection.exclude.isEmpty()) {
                "A typed query reads whole records: run a projection through dynamic()."
            }
        }

        /** Whether this scope, the reader's, admits a state of [scope]: a field it names, the state has too. */
        private fun Scope.covers(scope: Scope): Boolean =
            (ownerId == null || ownerId == scope.ownerId) && (spaceId == null || spaceId == scope.spaceId)
    }
}

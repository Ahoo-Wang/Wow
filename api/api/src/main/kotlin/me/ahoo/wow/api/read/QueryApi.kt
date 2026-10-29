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

package me.ahoo.wow.api.read

import kotlinx.coroutines.flow.Flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import tools.jackson.databind.node.ObjectNode

/**
 * The typed read API: the snapshots, the events and the datasets of the read side,
 * queried as someone. There is no entry that names nobody: [of] a caller reads within that caller's scope, under the
 * same admission, visibility, bulkhead and budget as the public port's queries. A client's reads another service's
 * over HTTP this way alone; the node's own, a [NodeQueryApi], also reads as trusted code.
 *
 * Every query takes the query protocol's types (`me.ahoo.wow.api.query`, built with its DSL) and fails with the query
 * errors of the read side: a query the read side refuses is `wow.query.invalid`, one that runs out of time
 * `wow.query.timeout`, one its store has no room for `wow.query.busy`.
 */
interface QueryApi {
    /**
     * The queries [caller] may run: restricted to its tenant (or the fixed tenant of an aggregate type that has one),
     * its owner and space, and to the rows its attributes see (ABAC and the application's query policies), under the
     * in-process budget (`wow.query.budgets.in-process.*`, the HTTP defaults).
     */
    fun of(caller: Caller): Queries
}

/**
 * The node's read API, its own read side in process: a caller's queries ([of]) and trusted code's ([trusted]), which
 * runs on the node and nowhere else (a job, a projection's rebuild, a route of the service's own); a peer reads for a
 * caller, so a client's [QueryApi] has no trusted queries.
 */
interface NodeQueryApi : QueryApi {
    /**
     * The queries of trusted code: no caller, so no tenant, owner or space restricts them, and no query policy that
     * reads the caller hides a row. They keep the in-process budget unless [TrustedQueries.unbounded] drops it.
     */
    fun trusted(): TrustedQueries
}

/**
 * A [NodeQueryApi] that builds the reads of a subscription handler itself ([tenant]), which the node asks of every
 * [QueryApi] it hands its handlers: a provider's side of the read API, not an entry point of an application, which
 * reads as a caller ([of]) or as trusted code ([trusted]).
 *
 * The node's own API implements it, pinning the tenant of the commit as trusted code, and so does a test's
 * [QueryApi] that must read as the node does (`:test-query`'s memory queries). Any other [QueryApi], a test's fake
 * say, serves a handler as a caller of the commit's tenant with no owner, space or subject, the rest [trusted].
 */
@WowSpi
interface TenantQueryApi : NodeQueryApi {
    /**
     * The queries of a subscription handler of a commit of [tenantId], which its context hands it: they read in
     * [tenantId] alone, and answer [SubscriptionQueries.tenantId] with it.
     */
    fun tenant(tenantId: String): SubscriptionQueries
}

/**
 * The query models one caller ([Queries]) or trusted code ([TrustedQueries]) reads. A row of a type the reader names no
 * class of, a state read by its type or an event no class of the model has, is an [ObjectNode], in process and over
 * HTTP alike.
 */
interface QueryModels {
    /**
     * The snapshots of the aggregate whose state is of [stateType] (its root's class when the root is its own state),
     * their rows decoded as [Snapshot]s of it. The node finds the aggregate type from its models.
     *
     * @throws IllegalArgumentException when no aggregate of the node has a state of [stateType], or this node serves
     * no snapshot query of it.
     */
    fun <S : Any> snapshots(stateType: Class<S>): SnapshotQueries<S>

    /**
     * The snapshots of [type], their states as stored ([ObjectNode]): read their fields by the constants of the
     * aggregate's contract.
     *
     * @throws IllegalArgumentException when this node serves no snapshot query of [type].
     */
    fun snapshots(type: AggregateType): SnapshotQueries<ObjectNode>

    /**
     * The event streams of [type]: one row per commit, each event's body decoded as the model's class of it, an
     * [ObjectNode] for an event it has no class of.
     *
     * @throws IllegalArgumentException when this node serves no events query of [type].
     */
    fun events(type: AggregateType): EventQueries

    /**
     * The rows of the dataset [name] (`context.dataset`), decoded as [rowType]: a class of the dataset's
     * columns, or [ObjectNode]. Experimental, as the dataset surface is.
     *
     * @throws IllegalArgumentException when no dataset of that name is served to this reader.
     */
    @ExperimentalWowApi
    fun <R : Any> datasets(name: DatasetName, rowType: Class<R>): RowQueries<R>
}

/**
 * The queries of one caller ([QueryApi.of]), or of a commit's tenant ([SubscriptionQueries]): they read in a tenant,
 * so a state is read by its aggregate id too ([TenantSnapshotQueries]).
 */
interface Queries : QueryModels {
    override fun <S : Any> snapshots(stateType: Class<S>): TenantSnapshotQueries<S>

    override fun snapshots(type: AggregateType): TenantSnapshotQueries<ObjectNode>
}

/**
 * The queries of a subscription handler ([me.ahoo.wow.api.subscribe.SubscriptionContext.queries]): the
 * whole tenant of the commit it handles, as trusted code pinned to that tenant (no owner, space or caller attribute
 * restricts them, and no policy that reads the caller hides a row), under the in-process budget. Another tenant's
 * rows only through an explicit [trusted].
 */
interface SubscriptionQueries : Queries {
    /** The tenant these queries read: the commit's. */
    val tenantId: String

    /** The queries of trusted code across every tenant; see [NodeQueryApi.trusted]. */
    fun trusted(): TrustedQueries
}

/**
 * The queries of trusted code ([NodeQueryApi.trusted]): no tenant restricts them, so a state is read by its ref alone
 * ([SnapshotQueries]). [unbounded] lifts the in-process budget for a job that must read everything.
 */
interface TrustedQueries : QueryModels {
    /**
     * These queries without a budget: no list, page or count bound, exact counts; only the in-process timeout
     * (`wow.query.unbounded-timeout`) applies. For jobs that read a whole model, not for anything a request drives.
     */
    fun unbounded(): TrustedQueries
}

/**
 * The query operations of one model, each row decoded as [R]. A row a policy hides is never returned; a field the
 * schema masks is masked as on the public port.
 */
interface RowQueries<R : Any> {
    /** The first row [query] matches, or `null` when none does. */
    suspend fun single(query: SingleQuery): R?

    /**
     * The rows [query] matches, at most its limit, or the budget's default list size when it names none (`0`); read
     * whole within the query's timeout. To read every row of a large result, [stream] it.
     */
    fun list(query: ListQuery): Flow<R>

    /**
     * The rows [query] matches as a resumable stream: read in keyset chunks, each its own query under
     * the store's bulkhead, so no permit is held while the collector works, and bounded by neither the list size nor
     * the budget. Each row comes with its [position][Positioned.position]: the same query streamed again [after] it
     * goes on strictly after that row, on this node or another. A query whose sort cannot order a cursor streams as
     * [list] does, under its bounds, its rows without positions, and cannot be resumed ([after] is then
     * `wow.query.invalid`).
     */
    fun stream(query: ListQuery, after: String? = null): Flow<Positioned<R>>

    /** One page of the rows [query] matches, with its total as [PagedQuery.total] asks. */
    suspend fun paged(query: PagedQuery): PagedList<R>

    /** One keyset page of the rows [query] matches, with the cursor of the next one. */
    suspend fun cursor(query: CursorQuery): CursorPage<R>

    /** How many rows [query] matches, bounded by its limit or the budget's count limit. */
    suspend fun count(query: CountQuery): CountResult

    /**
     * The groups of [query], each decoded as [groupType]: a class of its groups and metrics, or a `Map`. Experimental,
     * as the aggregation protocol is.
     */
    @ExperimentalWowApi
    fun <G : Any> aggregate(query: AggregationQuery, groupType: Class<G>): Flow<G>
}

/** The queries of an aggregate's model, its rows typed, with the same queries returning the rows as stored. */
interface ModelQueries<R : Any> : RowQueries<R> {
    /** The aggregate type these queries read. */
    val type: AggregateType

    /**
     * The same queries with each row decoded as [rowType] rather than typed: for a projection, which leaves out
     * fields a typed row needs, or a reader that walks the record as stored ([ObjectNode]).
     */
    fun <T : Any> dynamic(rowType: Class<T>): RowQueries<T>
}

/**
 * The snapshot queries of an aggregate type: one row per aggregate, a [Snapshot] of its state [S], and the latest
 * state of an aggregate by its ref. A typed row reads the whole record: a query with a projection is refused, run it
 * through [dynamic].
 */
interface SnapshotQueries<S : Any> : ModelQueries<Snapshot<S>> {
    /**
     * The latest committed state of the aggregate [ref] (its snapshot, else its log before the first one), as the
     * public port's state read reads it: `null` when there is none or the reader may not see it, alike, and for a
     * caller when [ref] is outside its tenant. Trusted code reads any tenant's.
     *
     * @throws IllegalArgumentException when [ref] is not an aggregate of [type].
     */
    suspend fun state(ref: AggregateRef): AggregateState<S>?

    /**
     * The committed state of the aggregate [ref] at version [minVersion] or later (read your writes,
     * [me.ahoo.wow.api.result.CommandResult.version] the version a command wrote): the latest state, its snapshot
     * caught up with its log when the snapshot lags; `null` when the reader may not see it.
     *
     * @throws MinVersionNotReachedException when the aggregate stays below [minVersion] through the read's wait
     * (`wow.query.state.min-version-wait`): the command has not committed yet.
     */
    suspend fun state(ref: AggregateRef, minVersion: Int): AggregateState<S>?
}

/**
 * The snapshot queries of a tenant's reader ([Queries]): a state is read by its aggregate id too, in the tenant the
 * queries read (the caller's, or the commit's).
 */
interface TenantSnapshotQueries<S : Any> : SnapshotQueries<S> {
    /** The latest committed state of the aggregate [id] in the queries' tenant, as [state] of a ref reads it. */
    suspend fun state(id: String): AggregateState<S>?

    /** The committed state of the aggregate [id] at version [minVersion] or later, as [state] of a ref reads it. */
    suspend fun state(id: String, minVersion: Int): AggregateState<S>?
}

/** The events queries of an aggregate type: one row per commit, an [EventStream]. */
interface EventQueries : ModelQueries<EventStream>

/** The snapshots of the aggregate whose state is [S], in the queries' tenant; see [Queries.snapshots]. */
inline fun <reified S : Any> Queries.snapshots(): TenantSnapshotQueries<S> = snapshots(S::class.java)

/** The snapshots of the aggregate whose state is [S], as trusted code; see [QueryModels.snapshots]. */
inline fun <reified S : Any> TrustedQueries.snapshots(): SnapshotQueries<S> = snapshots(S::class.java)

/** The rows of the dataset [name] decoded as [R]; see [QueryModels.datasets]. */
@ExperimentalWowApi
inline fun <reified R : Any> QueryModels.datasets(name: DatasetName): RowQueries<R> = datasets(name, R::class.java)

/** These queries with each row decoded as [T]; see [ModelQueries.dynamic]. */
inline fun <reified T : Any> ModelQueries<*>.dynamic(): RowQueries<T> = dynamic(T::class.java)

/** The groups of [query] decoded as [G]; see [RowQueries.aggregate]. */
@ExperimentalWowApi
inline fun <reified G : Any> RowQueries<*>.aggregate(query: AggregationQuery): Flow<G> =
    aggregate(query, G::class.java)

/**
 * A state read that asked for at least [minVersion] of [ref] (read your writes) while the
 * aggregate stayed at [version] (0 when it has no commit) through the read's wait: the command whose version was asked
 * for has not committed yet. The HTTP state route answers it `409` [CODE] with the version, and `Retry-After`.
 */
class MinVersionNotReachedException(val ref: AggregateRef, val minVersion: Int, val version: Int) :
    RuntimeException("$ref is at version $version, not yet at $minVersion.") {
    companion object {
        /** The problem code of this failure, as the framework's error catalog names it. */
        const val CODE: String = "wow.query.min-version-not-reached"
    }
}

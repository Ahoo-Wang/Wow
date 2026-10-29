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

package me.ahoo.wow.client.java

import kotlinx.coroutines.flow.toList
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
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
import me.ahoo.wow.api.read.AggregateState
import me.ahoo.wow.api.read.DatasetName
import me.ahoo.wow.api.read.EventStream
import me.ahoo.wow.api.read.ModelQueries
import me.ahoo.wow.api.read.Positioned
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.QueryModels
import me.ahoo.wow.api.read.RowQueries
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.read.SnapshotQueries
import me.ahoo.wow.api.read.TenantSnapshotQueries
import me.ahoo.wow.api.read.TrustedQueries
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

/**
 * The [QueryApi] for Java callers: the same queries, in process or a peer's over HTTP,
 * each blocking or asynchronous ([JavaFlows]), lists as [List]s and resumable streams as [Stream]s.
 *
 * ```java
 * JavaTenantSnapshotQueries<OrderState> orders = JavaQueryApi.of(queryApi).of(caller).snapshots(OrderState.class);
 * List<Snapshot<OrderState>> open = orders.list(ListQuery.of(MatchAllFilter.INSTANCE));
 * AggregateState<OrderState> order = orders.state("order-1"); // null when there is none
 * ```
 */
@JvmSuppressWildcards
open class JavaQueryApi internal constructor(private val delegate: QueryApi) {
    /** The queries [caller] may run ([QueryApi.of]). */
    fun of(caller: Caller): JavaQueries = JavaQueries(delegate.of(caller))

    companion object {
        /** The Java facade of [api]. */
        @JvmStatic
        fun of(api: QueryApi): JavaQueryApi = JavaQueryApi(api)

        /** The Java facade of the node's [api], trusted code's queries with it. */
        @JvmStatic
        fun of(api: NodeQueryApi): JavaNodeQueryApi = JavaNodeQueryApi(api)
    }
}

/** The [NodeQueryApi] for Java callers: a caller's queries, and trusted code's ([trusted]). */
@JvmSuppressWildcards
class JavaNodeQueryApi internal constructor(private val delegate: NodeQueryApi) : JavaQueryApi(delegate) {
    /** The queries of trusted code ([NodeQueryApi.trusted]). */
    fun trusted(): JavaTrustedQueries = JavaTrustedQueries(delegate.trusted())
}

/** [QueryModels] for Java callers: the models any reader reads. */
@JvmSuppressWildcards
open class JavaQueryModels internal constructor(private val delegate: QueryModels) {
    /** The event streams of [type] ([QueryModels.events]). */
    fun events(type: AggregateType): JavaModelQueries<EventStream> = JavaModelQueries(delegate.events(type))

    /** The rows of the dataset [name] as [rowType] ([QueryModels.datasets]). */
    @ExperimentalWowApi
    fun <R : Any> datasets(name: DatasetName, rowType: Class<R>): JavaRowQueries<R> =
        JavaRowQueries(delegate.datasets(name, rowType))
}

/** [Queries] for Java callers: a tenant's reader, which reads a state by its aggregate id too. */
@JvmSuppressWildcards
class JavaQueries internal constructor(private val delegate: Queries) : JavaQueryModels(delegate) {
    /** The snapshots of the aggregate whose state is of [stateType] ([Queries.snapshots]). */
    fun <S : Any> snapshots(stateType: Class<S>): JavaTenantSnapshotQueries<S> =
        JavaTenantSnapshotQueries(delegate.snapshots(stateType))

    /** The snapshots of [type], their states as stored ([Queries.snapshots]). */
    fun snapshots(type: AggregateType): JavaTenantSnapshotQueries<ObjectNode> =
        JavaTenantSnapshotQueries(delegate.snapshots(type))
}

/** [TrustedQueries] for Java callers: they read a state by its ref alone. */
@JvmSuppressWildcards
class JavaTrustedQueries internal constructor(private val delegate: TrustedQueries) : JavaQueryModels(delegate) {
    /** The snapshots of the aggregate whose state is of [stateType] ([TrustedQueries.snapshots]). */
    fun <S : Any> snapshots(stateType: Class<S>): JavaSnapshotQueries<S> =
        JavaSnapshotQueries(delegate.snapshots(stateType))

    /** The snapshots of [type], their states as stored ([TrustedQueries.snapshots]). */
    fun snapshots(type: AggregateType): JavaSnapshotQueries<ObjectNode> = JavaSnapshotQueries(delegate.snapshots(type))

    /** These queries without a budget ([TrustedQueries.unbounded]). */
    fun unbounded(): JavaTrustedQueries = JavaTrustedQueries(delegate.unbounded())
}

/** [RowQueries] for Java callers: each query blocking, or asynchronous with the `Async` suffix. */
@JvmSuppressWildcards
open class JavaRowQueries<R : Any> internal constructor(private val delegate: RowQueries<R>) {
    /** The first row [query] matches, or null. */
    fun single(query: SingleQuery): R? = JavaFlows.blocking { delegate.single(query) }

    fun singleAsync(query: SingleQuery): CompletableFuture<R?> = JavaFlows.async { delegate.single(query) }

    /** The rows [query] matches, read whole ([RowQueries.list]). */
    fun list(query: ListQuery): List<R> = JavaFlows.blocking { delegate.list(query).toList() }

    fun listAsync(query: ListQuery): CompletableFuture<List<R>> = JavaFlows.async { delegate.list(query).toList() }

    /**
     * The rows [query] matches as a resumable stream ([RowQueries.stream]), from after the position [after] when
     * given; close it (try-with-resources) to stop reading early.
     */
    @JvmOverloads
    fun stream(query: ListQuery, after: String? = null): Stream<Positioned<R>> =
        JavaFlows.stream(delegate.stream(query, after))

    /** One page of the rows [query] matches ([RowQueries.paged]). */
    fun paged(query: PagedQuery): PagedList<R> = JavaFlows.blocking { delegate.paged(query) }

    fun pagedAsync(query: PagedQuery): CompletableFuture<PagedList<R>> = JavaFlows.async { delegate.paged(query) }

    /** One keyset page of the rows [query] matches ([RowQueries.cursor]). */
    fun cursor(query: CursorQuery): CursorPage<R> = JavaFlows.blocking { delegate.cursor(query) }

    fun cursorAsync(query: CursorQuery): CompletableFuture<CursorPage<R>> = JavaFlows.async { delegate.cursor(query) }

    /** How many rows [query] matches ([RowQueries.count]). */
    fun count(query: CountQuery): CountResult = JavaFlows.blocking { delegate.count(query) }

    fun countAsync(query: CountQuery): CompletableFuture<CountResult> = JavaFlows.async { delegate.count(query) }

    /** The groups of [query], each as [groupType] ([RowQueries.aggregate]). */
    fun <G : Any> aggregate(query: AggregationQuery, groupType: Class<G>): List<G> =
        JavaFlows.blocking { delegate.aggregate(query, groupType).toList() }

    fun <G : Any> aggregateAsync(query: AggregationQuery, groupType: Class<G>): CompletableFuture<List<G>> =
        JavaFlows.async { delegate.aggregate(query, groupType).toList() }
}

/** [ModelQueries] for Java callers. */
@JvmSuppressWildcards
open class JavaModelQueries<R : Any> internal constructor(private val delegate: ModelQueries<R>) :
    JavaRowQueries<R>(delegate) {
    /** The aggregate type these queries read. */
    val type: AggregateType get() = delegate.type

    /** These queries with each row as [rowType] ([ModelQueries.dynamic]). */
    fun <T : Any> dynamic(rowType: Class<T>): JavaRowQueries<T> = JavaRowQueries(delegate.dynamic(rowType))
}

/** [SnapshotQueries] for Java callers: a state by its ref. */
@JvmSuppressWildcards
open class JavaSnapshotQueries<S : Any> internal constructor(private val delegate: SnapshotQueries<S>) :
    JavaModelQueries<@JvmSuppressWildcards Snapshot<S>>(delegate) {
    /** The latest committed state of the aggregate [ref], or null ([SnapshotQueries.state]). */
    fun state(ref: AggregateRef): AggregateState<S>? = JavaFlows.blocking { delegate.state(ref) }

    fun stateAsync(ref: AggregateRef): CompletableFuture<AggregateState<S>?> = JavaFlows.async { delegate.state(ref) }
}

/** [TenantSnapshotQueries] for Java callers: a state by its aggregate id too, in the queries' tenant. */
@JvmSuppressWildcards
class JavaTenantSnapshotQueries<S : Any> internal constructor(private val delegate: TenantSnapshotQueries<S>) :
    JavaSnapshotQueries<S>(delegate) {
    /** The latest committed state of the aggregate [id], or null ([TenantSnapshotQueries.state]). */
    fun state(id: String): AggregateState<S>? = JavaFlows.blocking { delegate.state(id) }

    fun stateAsync(id: String): CompletableFuture<AggregateState<S>?> = JavaFlows.async { delegate.state(id) }
}

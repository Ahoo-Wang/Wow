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
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.read.AggregateState
import me.ahoo.wow.api.read.DatasetName
import me.ahoo.wow.api.read.EventStream
import me.ahoo.wow.api.read.ModelQueries
import me.ahoo.wow.api.read.Positioned
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.RowQueries
import me.ahoo.wow.api.read.Snapshot
import me.ahoo.wow.api.read.SnapshotQueries
import me.ahoo.wow.api.read.TrustedQueries
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

/**
 * The [QueryApi] for Java callers: the same queries, in process or a peer's over HTTP,
 * each blocking or asynchronous ([JavaFlows]), lists as [List]s and resumable streams as [Stream]s.
 *
 * ```java
 * JavaSnapshotQueries<OrderState> orders = JavaQueryApi.of(queryApi).of(caller).snapshots(OrderState.class);
 * List<Snapshot<OrderState>> open = orders.list(new ListQuery(MatchAllFilter.INSTANCE));
 * AggregateState<OrderState> order = orders.state("order-1"); // null when there is none
 * ```
 */
@JvmSuppressWildcards
class JavaQueryApi private constructor(private val delegate: QueryApi) {
    /** The queries [caller] may run ([QueryApi.of]). */
    fun of(caller: Caller): JavaQueries = JavaQueries(delegate.of(caller))

    /** The queries of trusted code ([QueryApi.trusted]). */
    fun trusted(): JavaTrustedQueries = JavaTrustedQueries(delegate.trusted())

    companion object {
        /** The Java facade of [api]. */
        @JvmStatic
        fun of(api: QueryApi): JavaQueryApi = JavaQueryApi(api)
    }
}

/** [Queries] for Java callers. */
@JvmSuppressWildcards
open class JavaQueries internal constructor(private val delegate: Queries) {
    /** The snapshots of the aggregate whose state is of [stateType] ([Queries.snapshots]). */
    fun <S : Any> snapshots(stateType: Class<S>): JavaSnapshotQueries<S> =
        JavaSnapshotQueries(delegate.snapshots(stateType))

    /** The snapshots of [type] ([Queries.snapshots]). */
    fun snapshots(type: AggregateType): JavaSnapshotQueries<Any> = JavaSnapshotQueries(delegate.snapshots(type))

    /** The event streams of [type] ([Queries.events]). */
    fun events(type: AggregateType): JavaModelQueries<EventStream> = JavaModelQueries(delegate.events(type))

    /** The rows of the dataset [name] as [rowType] ([Queries.datasets]). */
    @ExperimentalWowApi
    fun <R : Any> datasets(name: DatasetName, rowType: Class<R>): JavaRowQueries<R> =
        JavaRowQueries(delegate.datasets(name, rowType))
}

/** [TrustedQueries] for Java callers. */
@JvmSuppressWildcards
class JavaTrustedQueries internal constructor(private val delegate: TrustedQueries) : JavaQueries(delegate) {
    /** These queries without a budget ([TrustedQueries.unbounded]). */
    fun unbounded(): JavaQueries = JavaQueries(delegate.unbounded())
}

/** [RowQueries] for Java callers: each query blocking, or asynchronous with the `Async` suffix. */
@JvmSuppressWildcards
open class JavaRowQueries<R : Any> internal constructor(private val delegate: RowQueries<R>) {
    /** The first row [query] matches, or null. */
    fun single(query: ISingleQuery): R? = JavaFlows.blocking { delegate.single(query) }

    fun singleAsync(query: ISingleQuery): CompletableFuture<R?> = JavaFlows.async { delegate.single(query) }

    /** The rows [query] matches, read whole ([RowQueries.list]). */
    fun list(query: IListQuery): List<R> = JavaFlows.blocking { delegate.list(query).toList() }

    fun listAsync(query: IListQuery): CompletableFuture<List<R>> = JavaFlows.async { delegate.list(query).toList() }

    /**
     * The rows [query] matches as a resumable stream ([RowQueries.stream]), from after the position [after] when
     * given; close it (try-with-resources) to stop reading early.
     */
    @JvmOverloads
    fun stream(query: IListQuery, after: String? = null): Stream<Positioned<R>> =
        JavaFlows.stream(delegate.stream(query, after))

    /** One page of the rows [query] matches ([RowQueries.paged]). */
    fun paged(query: IPagedQuery): PagedList<R> = JavaFlows.blocking { delegate.paged(query) }

    fun pagedAsync(query: IPagedQuery): CompletableFuture<PagedList<R>> = JavaFlows.async { delegate.paged(query) }

    /** One keyset page of the rows [query] matches ([RowQueries.cursor]). */
    fun cursor(query: ICursorQuery): CursorPage<R> = JavaFlows.blocking { delegate.cursor(query) }

    fun cursorAsync(query: ICursorQuery): CompletableFuture<CursorPage<R>> = JavaFlows.async { delegate.cursor(query) }

    /** How many rows [query] matches ([RowQueries.count]). */
    fun count(query: ICountQuery): CountResult = JavaFlows.blocking { delegate.count(query) }

    fun countAsync(query: ICountQuery): CompletableFuture<CountResult> = JavaFlows.async { delegate.count(query) }

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

/** [SnapshotQueries] for Java callers. */
@JvmSuppressWildcards
class JavaSnapshotQueries<S : Any> internal constructor(private val delegate: SnapshotQueries<S>) :
    JavaModelQueries<@JvmSuppressWildcards Snapshot<S>>(delegate) {
    /** The latest committed state of the aggregate [id], or null ([SnapshotQueries.state]). */
    fun state(id: String): AggregateState<S>? = JavaFlows.blocking { delegate.state(id) }

    fun stateAsync(id: String): CompletableFuture<AggregateState<S>?> = JavaFlows.async { delegate.state(id) }

    /** The latest committed state of the aggregate [ref], or null ([SnapshotQueries.state]). */
    fun state(ref: AggregateRef): AggregateState<S>? = JavaFlows.blocking { delegate.state(ref) }

    fun stateAsync(ref: AggregateRef): CompletableFuture<AggregateState<S>?> = JavaFlows.async { delegate.state(ref) }
}

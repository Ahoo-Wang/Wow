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

package me.ahoo.wow.http.query

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.dataset.DatasetQueryGateways
import me.ahoo.wow.query.dataset.DatasetVisibility
import me.ahoo.wow.query.engine.QueryEngine
import me.ahoo.wow.query.engine.ReadFacade
import me.ahoo.wow.runtime.snapshot.SnapshotStatus

/**
 * The query models the public routes serve (decisions D127, D145, D235), by aggregate type: the [snapshots] of each
 * aggregate a snapshot query backend serves, and the [events] of each aggregate an events backend serves, every one a
 * [QueryGateway] on the one engine. A model of the Manifest without one here has no route: a request for it is the
 * context's 404.
 *
 * The [datasets] (decision D75) are served under `/wow/dataset/{context}/{dataset}/query`, each by its gateway as it
 * is when the request comes: a dataset that joins or leaves while the node runs is served or not from then on.
 * [datasetVisibility] decides which of them a caller sees, in `GET /wow/dataset` and on its routes.
 *
 * [defaultListSize] is the limit a `list` query that names none (`limit` 0) runs with over HTTP, at most the HTTP
 * budget's `maxListSize`; `0` keeps 0's meaning, every match, where the budget allows it.
 *
 * [snapshotStatus] tells whether an aggregate type's snapshots are being rebuilt or backfilled (decision D172): its
 * snapshot query and descriptor then answer with `Wow-Rebuilding: true`.
 *
 * [policies] are the gateways that admit the states of each aggregate type whose snapshots are not queried while
 * query policies may hide one (review R3.3-3, [me.ahoo.wow.query.state.SnapshotPolicyGateway]); no route serves them,
 * and a state read sees a state as [visibility] says. [reads] is the read path of the state reads
 * ([ReadFacade], decision D237), the [QueryEngine]'s when it comes from one ([of]). [streams] is how a `list` streams
 * as server-sent events (decision D253).
 */
class HttpQueries @InternalWowApi constructor(
    snapshots: Collection<QueryGateway> = emptyList(),
    events: Collection<QueryGateway> = emptyList(),
    val defaultListSize: Int = DEFAULT_LIST_SIZE,
    val datasets: DatasetQueryGateways = DatasetQueryGateways.NONE,
    val datasetVisibility: DatasetVisibility = DatasetVisibility.DEFAULT,
    @InternalWowApi
    val snapshotStatus: SnapshotStatus = SnapshotStatus.NONE,
    policies: Collection<QueryGateway> = emptyList(),
    @InternalWowApi
    val reads: ReadFacade? = null,
    @InternalWowApi
    val streams: QueryStreams = QueryStreams(),
) {
    private val snapshots: Map<AggregateType, QueryGateway> = snapshots.associateBy { it.aggregateType }
    private val events: Map<AggregateType, QueryGateway> = events.associateBy { it.aggregateType }
    private val policies: Map<AggregateType, QueryGateway> = policies.associateBy { it.aggregateType }

    init {
        require(defaultListSize >= 0) { "defaultListSize must be greater than or equal to 0." }
        require(this.snapshots.size == snapshots.size) { "One snapshot query gateway per aggregate type." }
        require(this.events.size == events.size) { "One events query gateway per aggregate type." }
        require(this.policies.size == policies.size) { "One snapshot policy gateway per aggregate type." }
        require(this.policies.keys.none(this.snapshots::containsKey)) {
            "A snapshot policy gateway is only for an aggregate type whose snapshots are not queried."
        }
    }

    /** The snapshot query gateway of [type], or null when no backend serves its snapshots. */
    fun snapshots(type: AggregateType): QueryGateway? = snapshots[type]

    /**
     * The gateway whose policies decide which of [type]'s states a caller sees ([me.ahoo.wow.query.state.StateVisibility]):
     * its snapshot query gateway, else its snapshot policy gateway; null when no policy applies to it.
     */
    @InternalWowApi
    fun visibility(type: AggregateType): QueryGateway? = snapshots[type] ?: policies[type]

    /** The events query gateway of [type], or null when no backend serves its events. */
    fun events(type: AggregateType): QueryGateway? = events[type]

    /** The gateway of [type]'s query [model] (`{model}` of its route), or null when none serves it. */
    fun gateway(type: AggregateType, model: String): QueryGateway? = when (model) {
        QueryRoutes.SNAPSHOT -> snapshots(type)
        QueryRoutes.EVENTS -> events(type)
        else -> null
    }

    companion object {
        /** The default of [defaultListSize], as in 9.x. */
        const val DEFAULT_LIST_SIZE: Int = 100

        /** No query model: the query routes are not served. */
        @JvmField
        val NONE: HttpQueries = HttpQueries()

        /**
         * The query models [engine] composed (decision D237), with [defaultListSize], the [snapshotStatus] of the
         * node's snapshots and how lists stream ([streams]).
         */
        @InternalWowApi
        fun of(
            engine: QueryEngine,
            defaultListSize: Int = DEFAULT_LIST_SIZE,
            snapshotStatus: SnapshotStatus = SnapshotStatus.NONE,
            streams: QueryStreams = QueryStreams(),
        ): HttpQueries = HttpQueries(
            engine.snapshots,
            engine.events,
            defaultListSize,
            engine.datasets,
            engine.datasetVisibility,
            snapshotStatus,
            engine.policies,
            engine.reads,
            streams,
        )
    }
}

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
import me.ahoo.wow.query.QueryGateway

/**
 * The query models the public routes serve (decisions D127, D131), by aggregate type: the [snapshots] of each
 * aggregate a snapshot query backend serves, and the [events] of each aggregate. A model of the Manifest without one
 * here has no route: a request for it is the context's 404.
 *
 * [defaultListSize] is the limit a `list` query that names none (`limit` 0) runs with over HTTP, at most the HTTP
 * budget's `maxListSize`; `0` keeps 0's meaning, every match, where the budget allows it.
 */
class HttpQueries(
    snapshots: Collection<QueryGateway> = emptyList(),
    events: Collection<EventQueryGateway> = emptyList(),
    val defaultListSize: Int = DEFAULT_LIST_SIZE,
) {
    private val snapshots: Map<AggregateType, QueryGateway> = snapshots.associateBy { it.aggregateType }
    private val events: Map<AggregateType, EventQueryGateway> = events.associateBy { it.aggregateType }

    init {
        require(defaultListSize >= 0) { "defaultListSize must be greater than or equal to 0." }
        require(this.snapshots.size == snapshots.size) { "One snapshot query gateway per aggregate type." }
        require(this.events.size == events.size) { "One events query gateway per aggregate type." }
    }

    /** The snapshot query gateway of [type], or null when no backend serves its snapshots. */
    fun snapshots(type: AggregateType): QueryGateway? = snapshots[type]

    /** The events query gateway of [type], or null when it has none. */
    fun events(type: AggregateType): EventQueryGateway? = events[type]

    companion object {
        /** The default of [defaultListSize], as in 9.x. */
        const val DEFAULT_LIST_SIZE: Int = 100

        /** No query model: the query routes are not served. */
        @JvmField
        val NONE: HttpQueries = HttpQueries()
    }
}

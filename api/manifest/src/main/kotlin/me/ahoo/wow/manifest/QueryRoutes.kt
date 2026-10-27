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

package me.ahoo.wow.manifest

/**
 * The routes of an aggregate's query models.
 *
 * A model is queried with `POST /{context}/{aggregate}/{model}/query`, the operation in the body's `kind`; a `GET` of
 * the same path answers the model's capability descriptor. Both end in [SEGMENT], which [CommandRoutes] keeps from the
 * commands, so they never meet a command route.
 */
object QueryRoutes {
    const val GET: String = "GET"

    /** The last segment of every query route. */
    const val SEGMENT: String = "query"

    /** The aggregate's snapshots, the default read model, when the aggregate keeps them. */
    const val SNAPSHOT: String = "snapshot"

    /** The aggregate's event streams, one record per commit. */
    const val EVENTS: String = "events"

    /** The query route of [model] of [aggregate] in [context], then the route of its capability descriptor. */
    fun of(context: String, aggregate: String, model: String): List<RouteManifest> {
        val path = "/$context/$aggregate/$model/$SEGMENT"
        return listOf(RouteManifest(CommandRoutes.POST, path), RouteManifest(GET, path))
    }

    /**
     * The query models of [aggregate] in [context]: [SNAPSHOT] when its [snapshots] are enabled, and [EVENTS] always.
     */
    fun queries(context: String, aggregate: String, snapshots: Boolean): List<QueryManifest> =
        listOfNotNull(SNAPSHOT.takeIf { snapshots }, EVENTS).map { QueryManifest(it, of(context, aggregate, it)) }
}

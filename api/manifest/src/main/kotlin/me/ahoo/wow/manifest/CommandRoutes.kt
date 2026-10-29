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
 * The REST routes of a command, and the names they reserve.
 *
 * Every command is `POST /{context}/{aggregate}/{id}/{segment}`; a creation command ([Creation.REQUIRED] or
 * [Creation.ALLOWED]) is also `POST /{context}/{aggregate}/{segment}`, where the server generates the id, and so is a
 * mounted or `@Unaddressed` command, whose transformer finds the aggregate it acts on when the request
 * names none. The
 * segment is the command's name in kebab case ([segment]); `{id}` is written literally, as a template variable.
 *
 * The [system][CommandManifest.system] commands are routed on reserved segments, and the Manifest lists their routes
 * like any command's, so the edge serves and a client sends them from the Manifest alone:
 *
 * - an aggregate's lifecycle commands: `DELETE /{context}/{aggregate}/{id}` for `wow.lifecycle.DeleteAggregate`
 *   ([deleteRoute]), `POST /{context}/{aggregate}/{id}/wow.lifecycle.<segment>` for the others ([byIdPath] of the
 *   system name: `wow.lifecycle.restore-aggregate`);
 * - an aggregate's snapshot rebuild: `POST /{context}/{aggregate}/wow.snapshot.rebuild`, the ids in its body
 *   ([unaddressed] of the system name);
 * - a context's schedule jobs: `POST /{context}/wow.schedule/{id}/<segment>`, the job's name as the id
 *   ([jobRoute]: `.../enable`, `.../reschedule`, `.../disable`).
 *
 * An aggregate whose id is its owner's (`@AggregateRoot(idSource = OWNER)`) has, beside every route by id, the same route
 * without `{id}`, which an owner-level caller sends for its own aggregate: `POST /{context}/{aggregate}/{segment}`
 * for each command and lifecycle command, `DELETE /{context}/{aggregate}` for the delete ([ownDeleteRoute]). None of
 * them generates an id; the routes by id are for the callers with no owner.
 *
 * So that a command route never shadows a route of the framework, [ManifestMerger] refuses the context
 * [RESERVED_CONTEXT] (the framework's own endpoints, `/wow/...`), and a route whose last segment is one of
 * [RESERVED_SEGMENTS] (`POST /{context}/{aggregate}/{model}/query`, `GET .../{id}/state`) or starts with
 * [SYSTEM_SEGMENT_PREFIX] (the lifecycle routes, and any later system command).
 */
object CommandRoutes {
    const val POST: String = "POST"

    /** The path variable of the aggregate id. */
    const val ID: String = "{id}"

    /** The context name the framework's own endpoints live under. */
    const val RESERVED_CONTEXT: String = "wow"

    /** Last path segments of the framework's routes beside the command routes. */
    val RESERVED_SEGMENTS: Set<String> = setOf("query", "state")

    /** The prefix of the system commands' names and segments, `wow.lifecycle.` among them. */
    const val SYSTEM_SEGMENT_PREFIX: String = "wow."

    /** The method of the route of `wow.lifecycle.DeleteAggregate` ([deleteRoute]). */
    const val DELETE: String = "DELETE"

    /** The path segment a context's schedule jobs are routed under ([jobRoute]). */
    const val JOBS: String = "wow.schedule"

    private val ACRONYM_END = Regex("([A-Z]+)([A-Z][a-z])")
    private val WORD_END = Regex("([a-z0-9])([A-Z])")
    private val SEPARATORS = Regex("[_\\s]+")

    /**
     * The command [name] in kebab case: a word starts at an upper-case letter that follows a lower-case letter or a
     * digit, and at the last letter of an upper-case run followed by a lower-case one; underscores and white space
     * separate words too, and every letter is lowered. `AddCartItem` → `add-cart-item`, `HTTPRequest` →
     * `http-request`, `AddItemV2` → `add-item-v2`, `add_item` → `add-item`. Dots are kept, so a system name keeps
     * its namespace: `wow.lifecycle.RestoreAggregate` → `wow.lifecycle.restore-aggregate`.
     */
    fun segment(name: String): String =
        name.replace(ACRONYM_END, "$1-$2")
            .replace(WORD_END, "$1-$2")
            .replace(SEPARATORS, "-")
            .lowercase()

    /**
     * The routes of the command [name] of [aggregate] in [context], which creates its aggregate as [creation] says;
     * [unaddressed] when a request may name no aggregate, which a transformer finds (a mounted or `@Unaddressed`
     * command).
     */
    fun of(
        context: String,
        aggregate: String,
        name: String,
        creation: Creation,
        unaddressed: Boolean = false,
    ): List<RouteManifest> {
        val byId = RouteManifest(POST, byIdPath(context, aggregate, name))
        return if (creation == Creation.NONE && !unaddressed) {
            listOf(byId)
        } else {
            listOf(byId, unaddressed(context, aggregate, name))
        }
    }

    /** The path of the command [name] of [aggregate] in [context] to the aggregate of the path's id. */
    fun byIdPath(context: String, aggregate: String, name: String): String =
        "/$context/$aggregate/$ID/${segment(name)}"

    /** The route of [routes] to an aggregate by its id (a path with [ID]) when [byId], else one that names no id. */
    fun routeOf(routes: List<RouteManifest>, byId: Boolean): RouteManifest? =
        routes.firstOrNull { (ID in it.path) == byId }

    /** The path of [route] to the aggregate of [encodedId], already encoded as a path segment; the route's own without. */
    fun pathOf(route: RouteManifest, encodedId: String?): String =
        if (encodedId == null) route.path else route.path.replace(ID, encodedId)

    /** The route of the command [name] of [aggregate] in [context] that names no aggregate id. */
    fun unaddressed(context: String, aggregate: String, name: String): RouteManifest =
        RouteManifest(POST, "/$context/$aggregate/${segment(name)}")

    /** The route of `wow.lifecycle.DeleteAggregate` to an aggregate of [aggregate] in [context]: `DELETE` its path. */
    fun deleteRoute(context: String, aggregate: String): RouteManifest =
        RouteManifest(DELETE, "/$context/$aggregate/$ID")

    /**
     * The route of `wow.lifecycle.DeleteAggregate` to the caller's own aggregate of [aggregate] in [context], one whose
     * id is its owner's: `DELETE /{context}/{aggregate}`.
     */
    fun ownDeleteRoute(context: String, aggregate: String): RouteManifest =
        RouteManifest(DELETE, "/$context/$aggregate")

    /**
     * The route of the schedule job command [name] (`wow.schedule.Enable`) of [context], to the job the path's id
     * names: `POST /{context}/wow.schedule/{id}/enable`.
     */
    fun jobRoute(context: String, name: String): RouteManifest =
        RouteManifest(POST, "/$context/$JOBS/$ID/${segment(name.substringAfterLast('.'))}")

    /** Whether [segment], the last segment of a command route, is one the framework keeps for itself. */
    fun isReserved(segment: String): Boolean =
        segment in RESERVED_SEGMENTS || segment.startsWith(SYSTEM_SEGMENT_PREFIX)
}

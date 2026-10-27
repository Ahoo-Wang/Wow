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
 * The lifecycle commands an aggregate lists ([AggregateManifest.lifecycle]) get no routes here: the edge derives
 * them from that list, as `DELETE /{context}/{aggregate}/{id}` for `DeleteAggregate` and
 * `POST /{context}/{aggregate}/{id}/wow.lifecycle.<segment>` for the others, the [segment] of the system name
 * (`wow.lifecycle.restore-aggregate`).
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

    /** The prefix of the system commands' segments, `wow.lifecycle.` among them. */
    const val SYSTEM_SEGMENT_PREFIX: String = "wow."

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

    /** The route of the command [name] of [aggregate] in [context] that names no aggregate id. */
    fun unaddressed(context: String, aggregate: String, name: String): RouteManifest =
        RouteManifest(POST, "/$context/$aggregate/${segment(name)}")

    /** Whether [segment], the last segment of a command route, is one the framework keeps for itself. */
    fun isReserved(segment: String): Boolean =
        segment in RESERVED_SEGMENTS || segment.startsWith(SYSTEM_SEGMENT_PREFIX)
}

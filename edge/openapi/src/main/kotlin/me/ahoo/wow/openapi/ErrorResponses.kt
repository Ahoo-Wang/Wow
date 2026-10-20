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

package me.ahoo.wow.openapi

import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest

/**
 * The problem+json responses of a route (decisions D101, D102), one per status: each lists the error codes answered
 * with it, in its description and as `x-wow-error-codes`.
 *
 * A code's status is the edge's (`wow-http`'s `ErrorStatuses`): the host's [overrides] first, then the table that
 * lists the code: the context's for its domain rejection codes (decision D121), the Manifest's framework table, or
 * [FrameworkErrors.ALL] for a Manifest that lists none. Any other domain code is 422
 * ([FrameworkErrors.DOMAIN_REJECTION_STATUS]), which every route that sends a domain command lists.
 */
internal class ErrorResponses(manifest: Manifest, private val overrides: Map<String, Int>) {
    private val framework: Map<String, ErrorManifest> =
        FrameworkErrors.BY_CODE + manifest.errors.associateBy { it.code }
    private val byContext: Map<String, List<ErrorManifest>> = manifest.contexts.associate { it.name to it.errors }

    private val registered = LinkedHashMap<String, Map<String, Any?>>()

    /** The responses the routes refer to, as `components.responses`, in the order they were first needed. */
    val components: Map<String, Map<String, Any?>> get() = registered

    /** One error a response lists: its [code], or none for an error that is HTTP's alone, and what it means. */
    private data class Entry(val code: String?, val description: String?)

    /**
     * The responses of a route of the [kind] (`command`, `state`, `query`, ...) that answers the framework [codes];
     * with [domain], also the domain rejections of its [context]; with [ifMatch], `wow.aggregate.version-mismatch` as
     * 412 for a request with `If-Match`; and the [http] statuses that carry no Wow code (a missing route, 406, 415).
     *
     * Each is a reference to a component response, `<scope>.<kind>.<status>`: the scope is the [context] for a route
     * with [domain] rejections, whose table is the context's, and `wow` otherwise.
     */
    fun of(
        kind: String,
        codes: List<String>,
        context: String? = null,
        domain: Boolean = false,
        ifMatch: Boolean = false,
        http: Map<Int, String> = emptyMap(),
    ): Map<String, Any?> {
        val entries = mutableListOf<Pair<Int, Entry>>()
        codes.map(framework::getValue).forEach { entries += status(it) to Entry(it.code, it.description) }
        if (ifMatch && FrameworkErrors.AGGREGATE_VERSION_MISMATCH !in overrides) {
            entries += FrameworkErrors.PRECONDITION_FAILED_STATUS to Entry(
                FrameworkErrors.AGGREGATE_VERSION_MISMATCH,
                "The aggregate is not at the version If-Match names.",
            )
        }
        if (domain) {
            byContext[context].orEmpty().filterNot { it.code.startsWith(FrameworkErrors.PREFIX) }.forEach {
                entries += status(it) to Entry(it.code, it.description)
            }
            entries += FrameworkErrors.DOMAIN_REJECTION_STATUS to Entry(null, DOMAIN_REJECTION)
        }
        http.forEach { (status, description) -> entries += status to Entry(null, description) }
        val scope = if (domain) checkNotNull(context) else WowSchemas.PREFIX.removeSuffix(".")
        return entries.groupBy({ it.first }, { it.second }).toSortedMap()
            .entries.associateTo(LinkedHashMap()) { (status, listed) ->
                status.toString() to register("$scope.$kind.$status", response(listed))
            }
    }

    /** A reference to the component response [name], registering [response] as it. */
    fun register(name: String, response: Map<String, Any?>): Map<String, Any?> {
        val known = registered.putIfAbsent(name, response)
        check(known == null || known == response) { "The response $name is registered with another content." }
        return Json.ref(RESPONSES, name)
    }

    private fun response(entries: List<Entry>): Map<String, Any?> {
        val codes = entries.mapNotNull { it.code }.distinct()
        return Json.obj(
            "description" to entries.distinct().joinToString("\n") { entry ->
                listOfNotNull(entry.code?.let { "`$it`" }, entry.description).joinToString(": ")
            },
            "headers" to Json.obj(
                WowHttpContract.RETRY_AFTER_HEADER to Json.ref(Json.HEADERS, WowHttpContract.RETRY_AFTER_HEADER),
            ).takeIf { FrameworkErrors.STORAGE_UNAVAILABLE in codes },
            "content" to Json.obj(PROBLEM_JSON to Json.obj("schema" to Json.schemaRef(WowSchemas.PROBLEM))),
            "x-wow-error-codes" to codes.takeIf { it.isNotEmpty() },
        )
    }

    private fun status(error: ErrorManifest): Int = overrides[error.code] ?: error.status

    companion object {
        const val PROBLEM_JSON: String = "application/problem+json"
        const val RESPONSES: String = "responses"
        private const val DOMAIN_REJECTION =
            "A rejection by the domain, with the code it chose (decision D121); the problem carries the command's result."

        /**
         * The codes a command route answers: every framework code but the query ones and the internal port's call
         * code ([FrameworkErrors.CALL_COMMAND_FORBIDDEN]), which no documented route answers.
         */
        val COMMAND: List<String> = FrameworkErrors.ALL.map { it.code }
            .filterNot { it.startsWith(QUERY_PREFIX) || it == FrameworkErrors.CALL_COMMAND_FORBIDDEN }

        /**
         * The codes a state read or a trace answers (review R4-Q): a parameter it cannot read, or a replay longer than
         * the node's bound, is a query error (Q-4, Q-1); whatever the caller may not see is not found (Q-5); a replay
         * runs under the log store's query bulkhead (Q-1).
         */
        val STATE: List<String> = listOf(
            FrameworkErrors.AUTH_UNAUTHENTICATED,
            FrameworkErrors.AUTH_FORBIDDEN,
            FrameworkErrors.QUERY_MALFORMED,
            FrameworkErrors.QUERY_INVALID,
            FrameworkErrors.AGGREGATE_NOT_FOUND,
            FrameworkErrors.QUERY_BUSY,
            FrameworkErrors.STORAGE_UNAVAILABLE,
        )

        /** The codes a query answers: the query codes (decisions D130, D150), and not-found for a `single` one. */
        val QUERY: List<String> = listOf(FrameworkErrors.AUTH_UNAUTHENTICATED, FrameworkErrors.AUTH_FORBIDDEN) +
            FrameworkErrors.ALL.map { it.code }.filter { it.startsWith(QUERY_PREFIX) } +
            listOf(FrameworkErrors.AGGREGATE_NOT_FOUND, FrameworkErrors.STORAGE_UNAVAILABLE)

        /**
         * The codes a dataset query answers: a query's, but `wow.query.busy`, which only the stores' query bulkheads
         * answer (review R3.4-2), and a dataset backend has none.
         */
        val DATASET_QUERY: List<String> = QUERY - FrameworkErrors.QUERY_BUSY

        /** The codes a query model's capability descriptor answers. */
        val DESCRIPTOR: List<String> = listOf(
            FrameworkErrors.AUTH_UNAUTHENTICATED,
            FrameworkErrors.QUERY_FAILED,
            FrameworkErrors.STORAGE_UNAVAILABLE,
        )

        /** The codes the dataset list answers. */
        val DATASET_LIST: List<String> = listOf(
            FrameworkErrors.AUTH_UNAUTHENTICATED,
            FrameworkErrors.AUTH_FORBIDDEN,
            FrameworkErrors.QUERY_MALFORMED,
        )

        /** The codes of the framework's own endpoints: the caller must be authenticated. */
        val FRAMEWORK: List<String> = listOf(FrameworkErrors.AUTH_UNAUTHENTICATED)

        private const val QUERY_PREFIX = "wow.query."

        /** A route that answers no type the request accepts. */
        val NOT_ACCEPTABLE: Pair<Int, String> = 406 to "Not Acceptable: the route answers in none of the types Accept names."

        /** A body sent as another type than JSON. */
        val UNSUPPORTED_MEDIA_TYPE: Pair<Int, String> = 415 to "Unsupported Media Type: the body is sent as application/json."
    }
}

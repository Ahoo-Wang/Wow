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

import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest

/**
 * The problem+json responses of a route, one per status: each lists the error codes answered
 * with it, in its description and as `x-wow-error-codes`.
 *
 * A code's status is the edge's (`wow-http`'s `ErrorStatuses`): the host's [overrides] first, then the table that
 * lists the code: the context's for its domain rejection codes, the Manifest's framework table, or
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
        if (ifMatch && FrameworkCodes.AGGREGATE_VERSION_MISMATCH !in overrides) {
            entries += FrameworkErrors.PRECONDITION_FAILED_STATUS to Entry(
                FrameworkCodes.AGGREGATE_VERSION_MISMATCH,
                "The aggregate is not at the version If-Match names.",
            )
        }
        if (domain) {
            byContext[context].orEmpty().filterNot { it.code.startsWith(FrameworkCodes.PREFIX) }.forEach {
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

    /**
     * The responses of the service route [name] of [context]: the framework's answers to a route ([ROUTE]), in a
     * route's words, and the statuses of the codes it refuses with, its [errors], each with the codes of that status
     * alone. A status the framework's codes alone answer is the shared `wow.route.<status>`; one that carries a code of
     * the route's is the route's own, `<context>.route.<name>.<status>`. A route refuses with its codes' statuses, so
     * it lists no `422` of a domain rejection it does not list. A code's status is the edge's: the host's override,
     * else its table's.
     *
     * @throws IllegalStateException naming the route and the code, for a code neither the context's table nor the
     * framework's declares: the guard of a Manifest that was never merged, since the merger refuses such a route.
     */
    fun ofRoute(context: String, name: String, errors: List<String>): Map<String, Any?> {
        // A route is one of its context's, which the Manifest lists.
        val table = byContext.getValue(context).associateBy { it.code }
        val answers = ROUTE.map(framework::getValue).map { status(it) to Entry(it.code, routeWords(it)) }
        val own = errors.map { code ->
            val error = checkNotNull(table[code] ?: framework[code]) {
                "Service route $context.$name refuses with $code, which neither its context nor the framework declares."
            }
            status(error) to Entry(error.code, error.description)
        }
        val routeStatuses = own.map { it.first }.toSet()
        return (answers + own).groupBy({ it.first }, { it.second }).toSortedMap()
            .entries.associateTo(LinkedHashMap()) { (status, listed) ->
                val scope = if (status in routeStatuses) "$context.route.$name" else "${WowSchemas.PREFIX}route"
                status.toString() to register("$scope.$status", response(listed))
            }
    }

    /** What a framework code means for a service route, which sends no command. */
    private fun routeWords(error: ErrorManifest): String? = ROUTE_WORDS[error.code] ?: error.description

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
            ).takeIf { FrameworkCodes.STORAGE_UNAVAILABLE in codes },
            "content" to Json.obj(PROBLEM_JSON to Json.obj("schema" to Json.schemaRef(WowSchemas.PROBLEM))),
            "x-wow-error-codes" to codes.takeIf { it.isNotEmpty() },
        )
    }

    private fun status(error: ErrorManifest): Int = overrides[error.code] ?: error.status

    companion object {
        const val PROBLEM_JSON: String = "application/problem+json"
        const val RESPONSES: String = "responses"
        private const val DOMAIN_REJECTION =
            "A rejection by the domain, with the code it chose; the problem carries the command's result."

        /** The framework codes no documented route answers, initialized before [COMMAND] reads them. */
        private val NOT_ROUTED = setOf(FrameworkCodes.CALL_COMMAND_FORBIDDEN, FrameworkCodes.LEDGER_STALE)

        /**
         * The codes a command route answers: every framework code but the query ones, the internal port's call code
         * ([FrameworkCodes.CALL_COMMAND_FORBIDDEN]) and the ledger's stale action ([FrameworkCodes.LEDGER_STALE],
         * answered to a ledger command, which arrives on the command topic), which no documented route answers.
         */
        val COMMAND: List<String> = FrameworkErrors.ALL.map { it.code }
            .filterNot { it.startsWith(QUERY_PREFIX) || it in NOT_ROUTED }

        /**
         * The codes a state read or a trace answers: a parameter it cannot read, or a replay longer than
         * the node's bound, is a query error; whatever the caller may not see is not found; a replay
         * runs under the log store's query bulkhead.
         */
        val STATE: List<String> = listOf(
            FrameworkCodes.AUTH_UNAUTHENTICATED,
            FrameworkCodes.AUTH_FORBIDDEN,
            FrameworkCodes.QUERY_MALFORMED,
            FrameworkCodes.QUERY_INVALID,
            FrameworkCodes.AGGREGATE_NOT_FOUND,
            FrameworkCodes.QUERY_BUSY,
            FrameworkCodes.STORAGE_UNAVAILABLE,
        )

        /** The query codes only a dataset's routes answer: one not served, and one not queryable now. */
        private val DATASET_ONLY = setOf(
            FrameworkCodes.QUERY_DATASET_UNAVAILABLE,
            FrameworkCodes.QUERY_DATASET_NOT_FOUND,
        )

        /**
         * The codes a state read answers: a [STATE] code, or a minimum version not reached within its wait.
         */
        val STATE_READ: List<String> = STATE + FrameworkCodes.QUERY_MIN_VERSION_NOT_REACHED

        /**
         * The codes a query answers: the query codes but a dataset's and a state read's,
         * `wow.query.not-found` a `single` one that matches nothing among them.
         */
        val QUERY: List<String> = listOf(FrameworkCodes.AUTH_UNAUTHENTICATED, FrameworkCodes.AUTH_FORBIDDEN) +
            FrameworkErrors.ALL.map { it.code }.filter {
                it.startsWith(QUERY_PREFIX) && it !in DATASET_ONLY && it != FrameworkCodes.QUERY_MIN_VERSION_NOT_REACHED
            } +
            FrameworkCodes.STORAGE_UNAVAILABLE

        /**
         * The codes a dataset query answers: a query's, but `wow.query.busy`, which only the stores' query bulkheads
         * answer, and a dataset backend has none; and `wow.query.dataset-unavailable`, a dataset served
         * but not queryable now (-2); and `wow.query.dataset-not-found`, one not served to the caller.
         */
        val DATASET_QUERY: List<String> = QUERY - FrameworkCodes.QUERY_BUSY + DATASET_ONLY

        /** The codes a query model's capability descriptor answers. */
        val DESCRIPTOR: List<String> = listOf(
            FrameworkCodes.AUTH_UNAUTHENTICATED,
            FrameworkCodes.QUERY_FAILED,
            FrameworkCodes.STORAGE_UNAVAILABLE,
        )

        /** The codes a dataset's descriptor answers: a model's, and `wow.query.dataset-not-found`. */
        val DATASET_DESCRIPTOR: List<String> = DESCRIPTOR + FrameworkCodes.QUERY_DATASET_NOT_FOUND

        /** The codes the dataset list answers. */
        val DATASET_LIST: List<String> = listOf(
            FrameworkCodes.AUTH_UNAUTHENTICATED,
            FrameworkCodes.AUTH_FORBIDDEN,
            FrameworkCodes.QUERY_MALFORMED,
        )

        /** The codes of the framework's own endpoints: the caller must be authenticated. */
        val FRAMEWORK: List<String> = listOf(FrameworkCodes.AUTH_UNAUTHENTICATED)

        /** A service route's: its caller refused, a parameter that does not read, and a failure. */
        val ROUTE: List<String> = listOf(
            FrameworkCodes.AUTH_UNAUTHENTICATED,
            FrameworkCodes.AUTH_FORBIDDEN,
            FrameworkCodes.ROUTE_MALFORMED,
        )

        /** The framework codes whose description speaks of a command, as a route's answers say them. */
        private val ROUTE_WORDS: Map<String, String> = mapOf(
            FrameworkCodes.AUTH_FORBIDDEN to "The caller may not call this route: an authorizer refused it, or it " +
                "names a scope it may not act in.",
        )

        private const val QUERY_PREFIX = "wow.query."

        /** A route that answers no type the request accepts. */
        val NOT_ACCEPTABLE: Pair<Int, String> = 406 to "Not Acceptable: the route answers in none of the types Accept names."

        /** A body sent as another type than JSON. */
        val UNSUPPORTED_MEDIA_TYPE: Pair<Int, String> = 415 to "Unsupported Media Type: the body is sent as application/json."
    }
}

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
 * The framework's error codes and the HTTP status each is answered with (design 05 §3, decisions D102, D103): the
 * one table the Manifest's top-level `errors` ([Manifest.errors]) and the edge's problem+json answers both read.
 *
 * The query codes (decisions D130, D150) are answered by the query routes (`POST /{context}/{aggregate}/{model}/query`).
 *
 * The kernel and the runtime define the codes they produce themselves (`KernelRejections`, `ResultCodes`); the
 * strings are the same. A domain rejection's code is not here: it is answered with [DOMAIN_REJECTION_STATUS] unless
 * the host configures another status for it.
 */
object FrameworkErrors {
    const val AUTH_UNAUTHENTICATED: String = "wow.auth.unauthenticated"
    const val AUTH_FORBIDDEN: String = "wow.auth.forbidden"
    const val AGGREGATE_FORBIDDEN: String = "wow.aggregate.forbidden"
    const val COMMAND_MALFORMED: String = "wow.command.malformed"
    const val COMMAND_INVALID: String = "wow.command.invalid"
    const val WAIT_INVALID: String = "wow.wait.invalid"
    const val COMMAND_UNHANDLED: String = "wow.command.unhandled"
    const val AGGREGATE_NOT_FOUND: String = "wow.aggregate.not-found"
    const val AGGREGATE_EXISTS: String = "wow.aggregate.exists"
    const val AGGREGATE_VERSION_MISMATCH: String = "wow.aggregate.version-mismatch"
    const val COMMAND_REQUEST_ID_REUSED: String = "wow.command.request-id-reused"
    const val COMMAND_CONTENDED: String = "wow.command.contended"
    const val AGGREGATE_DELETED: String = "wow.aggregate.deleted"
    const val COMMAND_BUSY: String = "wow.command.busy"
    const val COMMAND_UNKNOWN: String = "wow.command.unknown"
    const val NODE_CLOSING: String = "wow.node.closing"
    const val SUBSCRIPTION_PARKED: String = "wow.subscription.parked"
    const val WAIT_TIMEOUT: String = "wow.wait.timeout"
    const val QUERY_MALFORMED: String = "wow.query.malformed"
    const val QUERY_INVALID: String = "wow.query.invalid"
    const val QUERY_FAILED: String = "wow.query.failed"
    const val QUERY_TIMEOUT: String = "wow.query.timeout"

    /** The status of a domain rejection, a code the domain chose, unless the host configures another for the code. */
    const val DOMAIN_REJECTION_STATUS: Int = 422

    /** The status of [AGGREGATE_VERSION_MISMATCH] when the request carried `If-Match`. */
    const val PRECONDITION_FAILED_STATUS: Int = 412

    /** Every framework code, in the order of the status table. */
    val ALL: List<ErrorManifest> = listOf(
        ErrorManifest(AUTH_UNAUTHENTICATED, 401, "The request carries no valid credentials."),
        ErrorManifest(AUTH_FORBIDDEN, 403, "The caller may not send this command."),
        ErrorManifest(AGGREGATE_FORBIDDEN, 403, "The aggregate is outside the caller's scope."),
        ErrorManifest(COMMAND_MALFORMED, 400, "The command body cannot be read as the command type."),
        ErrorManifest(COMMAND_INVALID, 400, "The command failed validation; the problem lists the violations."),
        ErrorManifest(WAIT_INVALID, 400, "The wait is not a chain the runtime can follow; the command was not run."),
        ErrorManifest(COMMAND_UNHANDLED, 400, "The aggregate does not handle this command."),
        ErrorManifest(AGGREGATE_NOT_FOUND, 404, "The aggregate does not exist."),
        ErrorManifest(AGGREGATE_EXISTS, 409, "The aggregate already exists."),
        ErrorManifest(
            AGGREGATE_VERSION_MISMATCH,
            409,
            "The aggregate is not at the expected version (412 when the request carried If-Match).",
        ),
        ErrorManifest(COMMAND_REQUEST_ID_REUSED, 409, "The request id was used by another command."),
        ErrorManifest(
            COMMAND_CONTENDED,
            409,
            "Concurrent writers kept winning the version; retrying with the same request id is safe.",
        ),
        ErrorManifest(AGGREGATE_DELETED, 410, "The aggregate is deleted; only RestoreAggregate applies."),
        ErrorManifest(COMMAND_BUSY, 429, "The node has too many commands in flight; retry later."),
        ErrorManifest(
            COMMAND_UNKNOWN,
            503,
            "Whether the command committed is unknown; retrying with the same request id is safe.",
        ),
        ErrorManifest(NODE_CLOSING, 503, "The node is shutting down; retry on another node."),
        ErrorManifest(
            SUBSCRIPTION_PARKED,
            500,
            "A subscription on the wait chain parked the aggregate; the wait cannot be reached until it resumes.",
        ),
        ErrorManifest(
            WAIT_TIMEOUT,
            504,
            "The wait ran out of time; the command may have committed. The problem carries the last stage reached.",
        ),
        ErrorManifest(QUERY_MALFORMED, 400, "The query body cannot be read as a query."),
        ErrorManifest(
            QUERY_INVALID,
            400,
            "The query failed admission; the problem lists the violations, each with its code (UNKNOWN_FIELD, ...).",
        ),
        ErrorManifest(QUERY_FAILED, 500, "The query backend failed to run the query."),
        ErrorManifest(
            QUERY_TIMEOUT,
            503,
            "The query ran longer than its budget allows; narrow it, or retry later.",
        ),
    )

    /** [ALL] by code. */
    val BY_CODE: Map<String, ErrorManifest> = ALL.associateBy { it.code }

    /** The prefix every framework code, and no domain code, starts with. */
    const val PREFIX: String = "wow."
}

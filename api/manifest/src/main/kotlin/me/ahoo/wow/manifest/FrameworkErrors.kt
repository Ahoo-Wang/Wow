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

import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import kotlin.time.Duration.Companion.seconds

/**
 * The framework's error codes and the HTTP status each is answered with: the
 * one table the Manifest's top-level `errors` ([Manifest.errors]) and the edge's problem+json answers both read.
 *
 * The query codes are answered by the query routes (`POST /{context}/{aggregate}/{model}/query`).
 *
 * The call code ([CALL_COMMAND_FORBIDDEN]) is answered by the internal port alone, to a service's
 * synchronous call.
 *
 * This table is the framework's codes as the JSON contract states them, each with its HTTP status and description; the
 * codes the modules produce are `wow-api`'s `FrameworkCodes`, which this module cannot depend on, and a test of this
 * module checks that the two name the same set. A domain rejection's code is not here: it is answered
 * with [DOMAIN_REJECTION_STATUS] unless the host configures another status for it.
 *
 * The TypeScript client's code table (`@ahoo-wang/wow-client`, `FrameworkErrorCodes`) is checked against this one
 * through the snapshot `framework-errors.json` of this module's tests: changing a code or a status here
 * changes that snapshot, and the client's test fails until its table follows.
 */
object FrameworkErrors {
    const val AUTH_UNAUTHENTICATED: String = "wow.auth.unauthenticated"
    const val AUTH_FORBIDDEN: String = "wow.auth.forbidden"
    const val AGGREGATE_FORBIDDEN: String = "wow.aggregate.forbidden"
    const val REQUEST_MALFORMED: String = "wow.request.malformed"
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
    const val COMMAND_FAILED: String = "wow.command.failed"
    const val COMMAND_REFUSED: String = "wow.command.refused"
    const val COMMAND_UNDECLARED_EVENT: String = "wow.command.undeclared-event"
    const val NODE_CLOSING: String = "wow.node.closing"
    const val NODE_STANDBY: String = "wow.node.standby"
    const val STORAGE_UNAVAILABLE: String = "wow.storage.unavailable"
    const val SCHEDULE_NOT_DUE: String = "wow.schedule.not-due"
    const val SCHEDULE_JOB_INVALID: String = "wow.schedule.job-invalid"
    const val SUBSCRIPTION_PARKED: String = "wow.subscription.parked"
    const val LEDGER_STALE: String = "wow.ledger.stale"
    const val WAIT_TIMEOUT: String = "wow.wait.timeout"
    const val ROUTE_MALFORMED: String = "wow.route.malformed"
    const val QUERY_MALFORMED: String = "wow.query.malformed"
    const val QUERY_INVALID: String = "wow.query.invalid"
    const val QUERY_FAILED: String = "wow.query.failed"
    const val QUERY_TIMEOUT: String = "wow.query.timeout"
    const val QUERY_BUSY: String = "wow.query.busy"
    const val QUERY_MIN_VERSION_NOT_REACHED: String = "wow.query.min-version-not-reached"
    const val QUERY_DATASET_UNAVAILABLE: String = "wow.query.dataset-unavailable"
    const val QUERY_NOT_FOUND: String = "wow.query.not-found"
    const val QUERY_DATASET_NOT_FOUND: String = "wow.query.dataset-not-found"
    const val QUERY_TOO_MANY_GROUPS: String = "wow.query.too-many-groups"
    const val CALL_COMMAND_FORBIDDEN: String = "wow.call.command-forbidden"
    const val PEER_UNAVAILABLE: String = "wow.peer.unavailable"

    /** The status of a domain rejection, a code the domain chose, unless the host configures another for the code. */
    const val DOMAIN_REJECTION_STATUS: Int = 422

    /** The status of [AGGREGATE_VERSION_MISMATCH] when the request carried `If-Match`. */
    const val PRECONDITION_FAILED_STATUS: Int = 412

    /**
     * The `Retry-After` of every framework code a retry may get past: a store restarting or failing over, a busy lane or
     * a closing node is back within seconds, and a client that waits a second spreads its retries rather than repeating
     * them at once.
     */
    const val RETRY_AFTER_SECONDS: Int = 1

    /** Every framework code, in the order of the status table, each with what a retry may do. */
    val ALL: List<ErrorManifest> = listOf(
        ErrorManifest(AUTH_UNAUTHENTICATED, 401, "The request carries no valid credentials."),
        ErrorManifest(AUTH_FORBIDDEN, 403, "The caller may not send this command."),
        ErrorManifest(AGGREGATE_FORBIDDEN, 403, "The aggregate is outside the caller's scope."),
        ErrorManifest(
            REQUEST_MALFORMED,
            400,
            "A request header or parameter does not parse, or names what Wow does not take.",
        ),
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
            retry = Retry.SAME_REQUEST,
            retryAfter = RETRY_AFTER_SECONDS,
        ),
        ErrorManifest(AGGREGATE_DELETED, 410, "The aggregate is deleted; only RestoreAggregate applies."),
        ErrorManifest(
            COMMAND_BUSY,
            429,
            "The command was not taken: its aggregate has too many commands waiting, or the node too many in flight; " +
                "retry later.",
            retry = Retry.SAME_REQUEST,
            retryAfter = RETRY_AFTER_SECONDS,
        ),
        ErrorManifest(
            COMMAND_UNKNOWN,
            503,
            "Whether the command committed is unknown; retrying with the same request id is safe.",
            retry = Retry.SAME_REQUEST,
            retryAfter = RETRY_AFTER_SECONDS,
        ),
        ErrorManifest(
            COMMAND_FAILED,
            500,
            "The command's handling failed with an unexpected error; it committed nothing, and a retry fails alike.",
        ),
        ErrorManifest(
            COMMAND_REFUSED,
            409,
            "The aggregate's current state refuses the command (a check of its decide function); the message says why.",
        ),
        ErrorManifest(
            COMMAND_UNDECLARED_EVENT,
            500,
            "The command's handling produced an event its decide function or after hooks do not declare: a bug in " +
                "the model, which the message names; it committed nothing.",
        ),
        ErrorManifest(
            NODE_CLOSING,
            503,
            "The node is shutting down; retry on another node.",
            retry = Retry.SAME_REQUEST,
            retryAfter = RETRY_AFTER_SECONDS
        ),
        ErrorManifest(
            NODE_STANDBY,
            503,
            "The node is on standby until the cut-over: it serves reads only.",
            retry = Retry.SAME_REQUEST,
            retryAfter = RETRY_AFTER_SECONDS
        ),
        ErrorManifest(
            STORAGE_UNAVAILABLE,
            503,
            "A store could not be reached or did not answer in time; retry later, a command with the same request id.",
            retry = Retry.SAME_REQUEST,
            retryAfter = RETRY_AFTER_SECONDS,
        ),
        ErrorManifest(
            SCHEDULE_NOT_DUE,
            503,
            "A scheduled command arrived too long before its due time on this node's clock; it is sent again later.",
            retry = Retry.SAME_REQUEST,
            retryAfter = RETRY_AFTER_SECONDS,
        ),
        ErrorManifest(
            SCHEDULE_JOB_INVALID,
            400,
            "The job command does not fit its job or instance: an unknown job, another scope, or an instance not enabled.",
        ),
        ErrorManifest(
            LEDGER_STALE,
            409,
            "The action names a parking the entry no longer is: the aggregate was resolved and parked again since; " +
                "read the entry again before acting on it.",
        ),
        ErrorManifest(
            SUBSCRIPTION_PARKED,
            500,
            "A subscription on the wait chain parked the aggregate; the wait cannot be reached until it resumes.",
        ),
        ErrorManifest(
            WAIT_TIMEOUT,
            504,
            "The wait ran out of time; the command may have committed. The problem carries the last stage reached.",
            retry = Retry.SAME_REQUEST,
            retryAfter = RETRY_AFTER_SECONDS,
        ),
        ErrorManifest(
            ROUTE_MALFORMED,
            400,
            "A service route's parameter or body cannot be read as its type; the violation names the parameter.",
        ),
        ErrorManifest(QUERY_MALFORMED, 400, "The query body cannot be read as a query."),
        ErrorManifest(
            QUERY_INVALID,
            400,
            "The query failed admission; the problem lists the violations, each with its code (UNKNOWN_FIELD, ...).",
        ),
        ErrorManifest(QUERY_FAILED, 500, "The query backend failed to run the query."),
        ErrorManifest(
            QUERY_NOT_FOUND,
            404,
            "A single query matched no record: a query result, not a statement that an aggregate does not exist.",
        ),
        ErrorManifest(QUERY_DATASET_NOT_FOUND, 404, "No dataset of that name is served to the caller."),
        ErrorManifest(
            QUERY_TOO_MANY_GROUPS,
            400,
            "An aggregation that names no sort has more groups than its limit; add a sort for a top-N, or raise " +
                "limit.",
        ),
        ErrorManifest(
            QUERY_TIMEOUT,
            504,
            "The query ran longer than its budget allows; narrow it rather than run it again.",
        ),
        ErrorManifest(
            QUERY_MIN_VERSION_NOT_REACHED,
            409,
            "The state read asked for a version the aggregate has not reached within the wait; the problem carries " +
                "its current version: retry later.",
            retry = Retry.NEW_REQUEST,
            retryAfter = RETRY_AFTER_SECONDS,
        ),
        ErrorManifest(
            QUERY_BUSY,
            429,
            "The query was not run: its store has as many queries running as it allows; retry later.",
            retry = Retry.NEW_REQUEST,
            retryAfter = RETRY_AFTER_SECONDS,
        ),
        ErrorManifest(
            QUERY_DATASET_UNAVAILABLE,
            409,
            "The dataset is served but cannot be queried now, for the reason the problem names (it needs revision).",
        ),
        ErrorManifest(
            CALL_COMMAND_FORBIDDEN,
            403,
            "The calling service may not call this command: its wow.callers.<service>.commands do not list it.",
        ),
        ErrorManifest(
            PEER_UNAVAILABLE,
            503,
            "A client reached no node of the peer, or lost the connection before a query's answer; nothing was " +
                "changed, so the request may be sent again.",
            retry = Retry.NEW_REQUEST,
            retryAfter = RETRY_AFTER_SECONDS,
        ),
    )

    /**
     * The short title of each framework code, a problem's `title` (RFC 9457): a summary that is the same for every
     * occurrence of the code. A code's description in [ALL] explains it further, in the Manifest and the reference.
     */
    val TITLES: Map<String, String> = mapOf(
        AUTH_UNAUTHENTICATED to "Unauthenticated",
        AUTH_FORBIDDEN to "Command forbidden",
        AGGREGATE_FORBIDDEN to "Aggregate out of scope",
        REQUEST_MALFORMED to "Malformed request",
        COMMAND_MALFORMED to "Malformed command",
        COMMAND_INVALID to "Invalid command",
        WAIT_INVALID to "Invalid wait",
        COMMAND_UNHANDLED to "Command not handled",
        AGGREGATE_NOT_FOUND to "Aggregate not found",
        AGGREGATE_EXISTS to "Aggregate exists",
        AGGREGATE_VERSION_MISMATCH to "Version mismatch",
        COMMAND_REQUEST_ID_REUSED to "Request id reused",
        COMMAND_CONTENDED to "Command contended",
        AGGREGATE_DELETED to "Aggregate deleted",
        COMMAND_BUSY to "Command busy",
        COMMAND_UNKNOWN to "Command outcome unknown",
        COMMAND_FAILED to "Command failed",
        COMMAND_REFUSED to "Command refused",
        COMMAND_UNDECLARED_EVENT to "Undeclared event",
        NODE_CLOSING to "Node closing",
        NODE_STANDBY to "Node on standby",
        STORAGE_UNAVAILABLE to "Storage unavailable",
        SCHEDULE_NOT_DUE to "Schedule not due",
        SCHEDULE_JOB_INVALID to "Invalid schedule job",
        LEDGER_STALE to "Stale ledger action",
        SUBSCRIPTION_PARKED to "Subscription parked",
        WAIT_TIMEOUT to "Wait timed out",
        ROUTE_MALFORMED to "Malformed route request",
        QUERY_MALFORMED to "Malformed query",
        QUERY_INVALID to "Invalid query",
        QUERY_FAILED to "Query failed",
        QUERY_NOT_FOUND to "No matching record",
        QUERY_DATASET_NOT_FOUND to "Dataset not found",
        QUERY_TOO_MANY_GROUPS to "Too many groups",
        QUERY_TIMEOUT to "Query timed out",
        QUERY_MIN_VERSION_NOT_REACHED to "Version not reached",
        QUERY_BUSY to "Query busy",
        QUERY_DATASET_UNAVAILABLE to "Dataset unavailable",
        CALL_COMMAND_FORBIDDEN to "Call forbidden",
        PEER_UNAVAILABLE to "Peer unavailable",
    )

    /** [ALL] by code. */
    val BY_CODE: Map<String, ErrorManifest> = ALL.associateBy { it.code }

    /**
     * The exception of a request a boundary did not carry out for the framework [code], said as [message]: its status,
     * retry and `Retry-After` as [ALL] declares them, a code it does not list a 500 no retry gets past. Each boundary
     * (the in-process clients and queries, a client's transport) maps its own failures with it, once.
     */
    @JvmStatic
    @JvmOverloads
    fun problemOf(
        code: String,
        message: String,
        result: CommandResult? = null,
        cause: Throwable? = null,
    ): WowProblemException {
        val error = BY_CODE[code]
        return WowProblemException(
            code,
            error?.status ?: INTERNAL_ERROR_STATUS,
            error?.retry ?: Retry.NONE,
            error?.retryAfter?.seconds,
            message,
            result,
            cause,
        )
    }

    /** The status of a framework code [ALL] does not list. */
    const val INTERNAL_ERROR_STATUS: Int = 500

    /** The prefix every framework code, and no domain code, starts with. */
    const val PREFIX: String = "wow."
}

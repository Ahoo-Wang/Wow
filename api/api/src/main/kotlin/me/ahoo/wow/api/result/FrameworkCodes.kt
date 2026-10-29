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

package me.ahoo.wow.api.result

/**
 * The error codes the framework itself answers with, `wow.*`, once, where every
 * module that produces or reads one sees them: the kernel's rejections, admission's, the runtime's results, the query
 * routes' and the internal port's. A domain's own codes are not among them, and a domain does not reject with one.
 *
 * The Manifest's error table (`FrameworkErrors` in `wow-manifest`, the JSON contract with each code's HTTP status and
 * description) holds the same codes; a test of `wow-manifest` checks that the two name the same set.
 */
object FrameworkCodes {
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

    /** A client reached no node of the peer, or lost a query's connection before its answer; it may be sent again. */
    const val PEER_UNAVAILABLE: String = "wow.peer.unavailable"

    /** The prefix every framework code starts with, which a domain's own codes do not. */
    const val PREFIX: String = "wow."

    /** Every framework code. */
    @JvmField
    val ALL: Set<String> = setOf(
        AUTH_UNAUTHENTICATED,
        AUTH_FORBIDDEN,
        AGGREGATE_FORBIDDEN,
        REQUEST_MALFORMED,
        COMMAND_MALFORMED,
        COMMAND_INVALID,
        WAIT_INVALID,
        COMMAND_UNHANDLED,
        AGGREGATE_NOT_FOUND,
        AGGREGATE_EXISTS,
        AGGREGATE_VERSION_MISMATCH,
        COMMAND_REQUEST_ID_REUSED,
        COMMAND_CONTENDED,
        AGGREGATE_DELETED,
        COMMAND_BUSY,
        COMMAND_UNKNOWN,
        COMMAND_FAILED,
        COMMAND_REFUSED,
        COMMAND_UNDECLARED_EVENT,
        NODE_CLOSING,
        NODE_STANDBY,
        STORAGE_UNAVAILABLE,
        SCHEDULE_NOT_DUE,
        SCHEDULE_JOB_INVALID,
        SUBSCRIPTION_PARKED,
        LEDGER_STALE,
        WAIT_TIMEOUT,
        ROUTE_MALFORMED,
        QUERY_MALFORMED,
        QUERY_INVALID,
        QUERY_FAILED,
        QUERY_TIMEOUT,
        QUERY_BUSY,
        QUERY_MIN_VERSION_NOT_REACHED,
        QUERY_DATASET_UNAVAILABLE,
        QUERY_NOT_FOUND,
        QUERY_DATASET_NOT_FOUND,
        QUERY_TOO_MANY_GROUPS,
        CALL_COMMAND_FORBIDDEN,
        PEER_UNAVAILABLE,
    )
}

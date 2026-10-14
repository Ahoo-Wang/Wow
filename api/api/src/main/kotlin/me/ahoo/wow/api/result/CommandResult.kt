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

import me.ahoo.wow.api.aggregate.AggregateRef

/** The kind of function that produced a result. */
enum class FunctionKind {
    /** A command's decide function, on the aggregate. */
    COMMAND,

    /** A projection's handler. */
    PROJECT,

    /** A reactor's handler. */
    REACT,

    /** A saga's handler: it may derive commands, and only its hop may be followed by more hops. */
    SAGA,

    /** The built-in subscription that writes snapshots. */
    SNAPSHOT,

    /** The built-in subscription that confirms reservations (decision D50). */
    RESERVATION,

    /** The built-in subscription that writes the timers of scheduled commands (decision D166). */
    TIMERS,
}

/**
 * Who produced a result (design 08 §7): a function of [kind] in [context]. A subscription is named
 * `processor.function` (design 04 §1), so [processor] and [name] come from its definition; for a command they are the
 * aggregate and the command's name.
 */
data class FunctionInfo(val kind: FunctionKind, val context: String, val processor: String, val name: String) {
    init {
        require(context.isNotBlank()) { "A function's context is not blank." }
        require(processor.isNotBlank()) { "A function's processor is not blank." }
        require(name.isNotBlank()) { "A function's name is not blank." }
    }
}

/** A field-level validation error: the [path] of the field, a [code] and a human-readable [message]. */
data class Violation(val path: String, val code: String, val message: String)

/**
 * Why a command was rejected or failed: an error [code] (`wow.*` or a domain's own), a [message], [violations], and the
 * rejection's structured [extensions] (decision D211), which an HTTP problem writes as its extension members.
 */
data class ErrorInfo(
    val code: String,
    val message: String,
    val violations: List<Violation> = emptyList(),
    val extensions: Map<String, Any?> = emptyMap(),
) {
    init {
        require(code.isNotBlank()) { "An error needs a code." }
    }
}

/**
 * The one outward result of a command (design 08 §7, decision D60), answered over HTTP and SSE, carried by wait
 * signals and cross-context receipts: what the command reached at one [hop] of its wait chain.
 *
 * - [function] is who produced it: the command's decide function, or the subscription that handled the commit.
 * - [version] is the aggregate's version after the commit, or the unchanged one.
 * - [duplicate]: the request was already committed, so it was not decided again; [results] are then empty, since
 *   they are only answered by the first commit (D51).
 * - [error] is set when [status] is [Status.REJECTED] or [Status.FAILED].
 * - [derived] lists, on a saga's hop, the ids of the commands it derived from the commit (D62).
 * - [signalTime] is when it was produced, in epoch milliseconds.
 * - [transformedFrom] is the command the request named when a transformer turned it into this one (decision D182);
 *   null when none did.
 */
data class CommandResult(
    val format: Int = FORMAT,
    val commandId: String,
    val requestId: String,
    val correlationId: String,
    val ref: AggregateRef,
    val hop: Hop,
    val function: FunctionInfo,
    val status: Status,
    val version: Int? = null,
    val duplicate: Boolean = false,
    val error: ErrorInfo? = null,
    val results: Map<String, Any> = emptyMap(),
    val derived: List<String> = emptyList(),
    val signalTime: Long,
    val transformedFrom: TransformedFrom? = null,
) {
    init {
        require(commandId.isNotBlank()) { "A result names its command." }
        require((status == Status.SUCCEEDED) == (error == null)) { "Only a result that did not succeed has an error." }
    }

    /** How a command ended at a hop. */
    enum class Status {
        /** The hop is reached. */
        SUCCEEDED,

        /** The command was refused by a rule: a business outcome, nothing was written. */
        REJECTED,

        /** The hop was not reached for a technical reason, or not in time. */
        FAILED,
    }

    companion object {
        /** The format version of this structure (decision D59). */
        const val FORMAT: Int = 1
    }
}

/**
 * The command a request named before a transformer turned it into the one that ran (decision D182): its full type
 * name [command] (`context.aggregate.Command`) and the aggregate id the request gave, null when it gave none.
 */
data class TransformedFrom(val command: String, val aggregateId: String? = null) {
    init {
        require(command.isNotBlank()) { "A transformed command names the command it came from." }
        require(aggregateId == null || aggregateId.isNotBlank()) { "An aggregate id, when given, is not blank." }
    }
}

/**
 * A wait signal (design 08 §8): the [result] one hop reached for the wait [waitId], sent to the node that waits.
 * Signals are deduplicated by ([waitId], the result's hop and command, its source): the source is the result's
 * [CommandResult.function], the context and function that signalled it.
 */
data class WaitSignal(val format: Int = FORMAT, val waitId: String, val result: CommandResult) {
    init {
        require(waitId.isNotBlank()) { "A wait signal names its wait." }
    }

    companion object {
        /** The format version of this structure (decision D59). */
        const val FORMAT: Int = 1
    }
}

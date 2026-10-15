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
import me.ahoo.wow.api.annotation.InternalWowApi

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
 *
 * Fields may be added (decision D59): construct one with [of]; the constructor is internal to Wow.
 */
class ErrorInfo @InternalWowApi constructor(
    val code: String,
    val message: String,
    val violations: List<Violation> = emptyList(),
    val extensions: Map<String, Any?> = emptyMap(),
) {
    init {
        require(code.isNotBlank()) { "An error needs a code." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ErrorInfo && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ErrorInfo(code=$code, message=$message, violations=$violations, extensions=$extensions)"

    private fun valueFields(): List<Any?> = listOf(code, message, violations, extensions)

    companion object {
        /** The error [code] with [message], the field-level [violations] and the structured [extensions]. */
        @JvmStatic
        @JvmOverloads
        fun of(
            code: String,
            message: String,
            violations: List<Violation> = emptyList(),
            extensions: Map<String, Any?> = emptyMap(),
        ): ErrorInfo = ErrorInfo(code, message, violations, extensions)
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
 * - [chainEnded] marks the final result of a wait whose chain ended before its plan did (decision D212): a hop, such
 *   as a saga's that derived no command (a declined payment, say), left nothing to reach the plan's later hops. The
 *   result stays at that hop, with its own status: it is not the plan's last hop reached. [pending] lists the hops of
 *   the plan that were not reached, in order; it is empty unless [chainEnded].
 *
 * Fields may be added (decision D59): construct a result with [succeeded], [rejected] or [failed], such as a fake of
 * a command gateway answers in a test; the constructor is internal to Wow.
 */
class CommandResult @InternalWowApi constructor(
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
    val chainEnded: Boolean = false,
    val pending: List<Hop> = emptyList(),
) {
    init {
        require(commandId.isNotBlank()) { "A result names its command." }
        require((status == Status.SUCCEEDED) == (error == null)) { "Only a result that did not succeed has an error." }
        require(chainEnded == pending.isNotEmpty()) { "A chain that ended early names its pending hops, and only it." }
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

    /** This CommandResult with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        format: Int = this.format,
        commandId: String = this.commandId,
        requestId: String = this.requestId,
        correlationId: String = this.correlationId,
        ref: AggregateRef = this.ref,
        hop: Hop = this.hop,
        function: FunctionInfo = this.function,
        status: Status = this.status,
        version: Int? = this.version,
        duplicate: Boolean = this.duplicate,
        error: ErrorInfo? = this.error,
        results: Map<String, Any> = this.results,
        derived: List<String> = this.derived,
        signalTime: Long = this.signalTime,
        transformedFrom: TransformedFrom? = this.transformedFrom,
        chainEnded: Boolean = this.chainEnded,
        pending: List<Hop> = this.pending,
    ): CommandResult = CommandResult(
        format,
        commandId,
        requestId,
        correlationId,
        ref,
        hop,
        function,
        status,
        version,
        duplicate,
        error,
        results,
        derived,
        signalTime,
        transformedFrom,
        chainEnded,
        pending,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is CommandResult && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CommandResult(format=$format, commandId=$commandId, requestId=$requestId, " +
            "correlationId=$correlationId, ref=$ref, hop=$hop, function=$function, status=$status, " +
            "version=$version, duplicate=$duplicate, error=$error, results=$results, derived=$derived, " +
            "signalTime=$signalTime, transformedFrom=$transformedFrom, chainEnded=$chainEnded, pending=$pending)"

    private fun valueFields(): List<Any?> = listOf(
        format, commandId, requestId, correlationId, ref, hop, function, status, version, duplicate, error, results,
        derived, signalTime, transformedFrom, chainEnded, pending
    )

    companion object {
        /** The format version of this structure (decision D59). */
        const val FORMAT: Int = 1

        /**
         * The result of command [commandId] (of request [requestId], in the chain [correlationId]) that reached [hop] on
         * [ref], produced by [function] at [signalTime] (epoch milliseconds), the aggregate then at [version].
         */
        @JvmStatic
        @Suppress("LongParameterList")
        fun succeeded(
            commandId: String,
            requestId: String,
            correlationId: String,
            ref: AggregateRef,
            hop: Hop,
            function: FunctionInfo,
            version: Int?,
            signalTime: Long,
        ): CommandResult = CommandResult(
            commandId = commandId,
            requestId = requestId,
            correlationId = correlationId,
            ref = ref,
            hop = hop,
            function = function,
            status = Status.SUCCEEDED,
            version = version,
            signalTime = signalTime,
        )

        /** The result of a command a rule refused at [hop] with [error]; the other fields are [succeeded]'s. */
        @JvmStatic
        @Suppress("LongParameterList")
        fun rejected(
            commandId: String,
            requestId: String,
            correlationId: String,
            ref: AggregateRef,
            hop: Hop,
            function: FunctionInfo,
            version: Int?,
            error: ErrorInfo,
            signalTime: Long,
        ): CommandResult = notSucceeded(
            commandId, requestId, correlationId, ref, hop, function, version, Status.REJECTED, error, signalTime,
        )

        /** The result of a command that did not reach [hop] for a technical reason, [error]; see [succeeded]. */
        @JvmStatic
        @Suppress("LongParameterList")
        fun failed(
            commandId: String,
            requestId: String,
            correlationId: String,
            ref: AggregateRef,
            hop: Hop,
            function: FunctionInfo,
            version: Int?,
            error: ErrorInfo,
            signalTime: Long,
        ): CommandResult = notSucceeded(
            commandId, requestId, correlationId, ref, hop, function, version, Status.FAILED, error, signalTime,
        )

        @Suppress("LongParameterList")
        private fun notSucceeded(
            commandId: String,
            requestId: String,
            correlationId: String,
            ref: AggregateRef,
            hop: Hop,
            function: FunctionInfo,
            version: Int?,
            status: Status,
            error: ErrorInfo,
            signalTime: Long,
        ) = CommandResult(
            commandId = commandId,
            requestId = requestId,
            correlationId = correlationId,
            ref = ref,
            hop = hop,
            function = function,
            status = status,
            version = version,
            error = error,
            signalTime = signalTime,
        )
    }
}

/**
 * The command a request named before a transformer turned it into the one that ran (decision D182): its full type
 * name [command] (`context.aggregate.Command`) and the aggregate id the request gave, null when it gave none.
 *
 * Fields may be added (decision D216): construct it with [of]; the constructor is internal to Wow.
 */
class TransformedFrom @InternalWowApi constructor(val command: String, val aggregateId: String? = null) {
    init {
        require(command.isNotBlank()) { "A transformed command names the command it came from." }
        require(aggregateId == null || aggregateId.isNotBlank()) { "An aggregate id, when given, is not blank." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is TransformedFrom && command == other.command && aggregateId == other.aggregateId

    override fun hashCode(): Int = 31 * command.hashCode() + (aggregateId?.hashCode() ?: 0)

    override fun toString(): String = "TransformedFrom(command=$command, aggregateId=$aggregateId)"

    companion object {
        /** The request named [command] (its full type name), and gave [aggregateId], null when it gave none. */
        @JvmStatic
        @JvmOverloads
        fun of(command: String, aggregateId: String? = null): TransformedFrom = TransformedFrom(command, aggregateId)
    }
}

/**
 * A wait signal (design 08 §8): the [result] one hop reached for the wait [waitId], sent to the node that waits.
 * Signals are deduplicated by ([waitId], the result's hop and command, its source): the source is the result's
 * [CommandResult.function], the context and function that signalled it.
 *
 * Fields may be added (decision D59); only Wow builds a signal.
 */
class WaitSignal @InternalWowApi constructor(
    val format: Int = FORMAT,
    val waitId: String,
    val result: CommandResult,
) {
    init {
        require(waitId.isNotBlank()) { "A wait signal names its wait." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is WaitSignal && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "WaitSignal(format=$format, waitId=$waitId, result=$result)"

    private fun valueFields(): List<Any?> = listOf(format, waitId, result)

    companion object {
        /** The format version of this structure (decision D59). */
        const val FORMAT: Int = 1
    }
}

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

package me.ahoo.wow.runtime.wait

import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.spi.log.EventStreamRecord

/** The error codes the runtime answers with (design 08 §7), beside the kernel's rejection codes. */
object ResultCodes {
    /** Concurrent writers kept winning the version slot; retrying with the same request id is safe. */
    const val CONTENDED = "wow.command.contended"

    /** Whether the command committed is unknown; retrying with the same request id is safe (D46). */
    const val UNKNOWN = "wow.command.unknown"

    /** The wait ran out of time. It says only that the wait gave up, not that the command failed (D64). */
    const val WAIT_TIMEOUT = "wow.wait.timeout"

    /** The wait plan is not a chain the runtime can follow, so the command was not executed (D62). */
    const val WAIT_INVALID = "wow.wait.invalid"

    /** A subscription on the chain parked the aggregate: the hop will not be reached until it is resumed. */
    const val SUBSCRIPTION_PARKED = "wow.subscription.parked"
}

/** The function of [command]'s decide function on [model]: the aggregate and the command's model name. */
fun commandFunction(model: AggregateModel<*>, command: Command<*>): FunctionInfo {
    val type = command.ref.type
    val bodyType = command.body.javaClass
    val name = if (model.decider(bodyType) != null) {
        model.commandType(bodyType).name.substringAfterLast('.')
    } else {
        bodyType.simpleName.ifBlank { bodyType.name }
    }
    return FunctionInfo(FunctionKind.COMMAND, type.context, type.name, name)
}

/**
 * The function of a subscription named [subscription] (`processor.function`, design 04 §1) of [kind] in [context]:
 * the processor is the part before the last dot; a name without one is both.
 */
fun subscriptionFunction(kind: FunctionKind, context: String, subscription: String): FunctionInfo {
    val dot = subscription.lastIndexOf('.')
    return if (dot in 1 until subscription.lastIndex) {
        FunctionInfo(kind, context, subscription.substring(0, dot), subscription.substring(dot + 1))
    } else {
        FunctionInfo(kind, context, subscription, subscription)
    }
}

/** The root of the chain [command] belongs to: its causation's correlation id, or itself (design 08 §4). */
val Command<*>.correlationId: String get() = causation?.correlationId ?: id

/** The root of the chain the command of this stream belongs to. */
val EventStreamRecord.correlationId: String get() = causation?.correlationId ?: commandId

/**
 * Maps the kernel's [CommandOutcome] of [command] to the outward [CommandResult] at [hop] (design 08 §7):
 *
 * | outcome | status | |
 * |---|---|---|
 * | Committed | SUCCEEDED | the new version, with the decide function's results |
 * | AlreadyCommitted | SUCCEEDED | `duplicate`, the committed version, no results |
 * | Unchanged | SUCCEEDED | the unchanged version |
 * | Rejected | REJECTED | the rejection's code |
 * | Contended | FAILED | [ResultCodes.CONTENDED] |
 * | Unknown | FAILED | [ResultCodes.UNKNOWN] |
 */
fun CommandOutcome.toResult(command: Command<*>, function: FunctionInfo, hop: Hop, now: Long): CommandResult {
    val result = CommandResult(
        commandId = command.id,
        requestId = command.requestId,
        correlationId = command.correlationId,
        ref = command.ref,
        hop = hop,
        function = function,
        status = CommandResult.Status.SUCCEEDED,
        signalTime = now,
    )
    return when (this) {
        is CommandOutcome.Committed -> result.copy(version = stream.version, results = results)
        is CommandOutcome.AlreadyCommitted -> result.copy(version = stream.version, duplicate = true)
        is CommandOutcome.Unchanged -> result.copy(version = version)
        is CommandOutcome.Rejected -> result.copy(
            status = CommandResult.Status.REJECTED,
            error = ErrorInfo(rejection.code, rejection.message),
        )

        is CommandOutcome.Contended -> result.failed(
            ResultCodes.CONTENDED,
            "Concurrent writers kept winning the version slot for $attempts attempts.",
        )

        is CommandOutcome.Unknown -> result.failed(
            ResultCodes.UNKNOWN,
            "Whether the command committed is unknown: ${cause.message ?: cause.javaClass.name}",
        )
    }
}

/** The result of a subscription's [function] at [hop] for [stream]: SUCCEEDED with [derived], or FAILED with [error]. */
fun handledResult(
    stream: EventStreamRecord,
    hop: Hop,
    function: FunctionInfo,
    now: Long,
    derived: List<String> = emptyList(),
    error: ErrorInfo? = null,
): CommandResult = CommandResult(
    commandId = stream.commandId,
    requestId = stream.requestId,
    correlationId = stream.correlationId,
    ref = stream.ref,
    hop = hop,
    function = function,
    status = if (error == null) CommandResult.Status.SUCCEEDED else CommandResult.Status.FAILED,
    version = stream.version,
    error = error,
    derived = derived,
    signalTime = now,
)

/** This result, failed with [code] and [message]. */
fun CommandResult.failed(code: String, message: String): CommandResult =
    copy(status = CommandResult.Status.FAILED, error = ErrorInfo(code, message))

/** The result of [command] reaching [hop] without an outcome of the kernel to report, such as ACCEPTED. */
internal fun reachedResult(command: Command<*>, function: FunctionInfo, hop: Hop, now: Long) = CommandResult(
    commandId = command.id,
    requestId = command.requestId,
    correlationId = command.correlationId,
    ref = command.ref,
    hop = hop,
    function = function,
    status = CommandResult.Status.SUCCEEDED,
    signalTime = now,
)

/** The result of [command] refused at admission with [code], before anything was reached. */
internal fun admissionRejected(
    command: Command<*>,
    function: FunctionInfo,
    code: String,
    message: String,
    now: Long,
) = CommandResult(
    commandId = command.id,
    requestId = command.requestId,
    correlationId = command.correlationId,
    ref = command.ref,
    hop = Hop.accepted(command.ref.type.context),
    function = function,
    status = CommandResult.Status.REJECTED,
    error = ErrorInfo(code, message),
    signalTime = now,
)

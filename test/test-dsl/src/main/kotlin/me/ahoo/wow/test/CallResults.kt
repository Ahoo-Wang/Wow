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

package me.ahoo.wow.test

import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop

/**
 * The result a target answers a synchronous call of [command] with when it committed it: at its
 * COMMITTED hop, on the aggregate [aggregateId] (the command's, or `<aggregate>-1` for a creation that left its id to
 * the target), at [version], [duplicate] when the target had already committed the request.
 */
@JvmOverloads
fun committedResult(
    command: Command<*>,
    version: Int = 1,
    aggregateId: String = if (command.ref.isAssigned) command.ref.id else "${command.ref.type.name}-1",
    duplicate: Boolean = false,
): CommandResult = resultOf(command, aggregateId, CommandResult.Status.SUCCEEDED).copyWith(
    version = version,
    duplicate = duplicate,
)

/** The result a target answers a synchronous call of [command] with when it refused it with [code]. */
@JvmOverloads
fun rejectedResult(command: Command<*>, code: String, message: String = code): CommandResult =
    resultOf(command, command.ref.id, CommandResult.Status.REJECTED).copyWith(error = ErrorInfo(code, message))

/**
 * The result a target answers a synchronous call of [command] with when it failed for a technical reason [code],
 * such as a store it could not reach.
 */
@JvmOverloads
fun failedResult(command: Command<*>, code: String, message: String = code): CommandResult =
    resultOf(command, command.ref.id, CommandResult.Status.FAILED).copyWith(error = ErrorInfo(code, message))

private fun resultOf(command: Command<*>, aggregateId: String, status: CommandResult.Status): CommandResult {
    val type = command.ref.type
    val causation = command.causation
    return CommandResult(
        commandId = command.id,
        requestId = command.requestId,
        correlationId = causation?.correlationId ?: command.id,
        ref = command.ref.copy(id = aggregateId),
        hop = Hop.committed(type.context),
        function = FunctionInfo(FunctionKind.COMMAND, type.context, type.name, command.body.javaClass.simpleName),
        status = status,
        error = if (status == CommandResult.Status.SUCCEEDED) null else ErrorInfo(status.name, status.name),
        signalTime = 0L,
    )
}

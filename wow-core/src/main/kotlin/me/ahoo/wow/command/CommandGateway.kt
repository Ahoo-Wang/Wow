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

package me.ahoo.wow.command

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.stage.WaitingForStage
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.id.generateGlobalId
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

const val COMMAND_GATEWAY_PROCESSOR_NAME = "CommandGateway"

val COMMAND_GATEWAY_FUNCTION =
    FunctionInfoData(
        functionKind = FunctionKind.COMMAND,
        contextName = Wow.WOW,
        processorName = COMMAND_GATEWAY_PROCESSOR_NAME,
        name = "send",
    )

fun CommandMessage<*>.commandSentSignal(
    waitCommandId: String,
    error: Throwable? = null
): WaitSignal {
    val errorInfo = error?.toErrorInfo() ?: ErrorInfo.OK
    return SimpleWaitSignal(
        id = generateGlobalId(),
        waitCommandId = waitCommandId,
        commandId = commandId,
        aggregateId = aggregateId,
        stage = CommandStage.SENT,
        function = COMMAND_GATEWAY_FUNCTION,
        aggregateVersion = aggregateVersion,
        isLastProjection = true,
        errorCode = errorInfo.errorCode,
        errorMsg = errorInfo.errorMsg,
        bindingErrors = errorInfo.bindingErrors,
        result = emptyMap(),
        signalTime = System.currentTimeMillis(),
    )
}

/**
 * Command Gateway interface for sending commands and waiting for their results.
 *
 * The Command Gateway provides a high-level API for sending commands to aggregates
 * and optionally waiting for their processing results. It supports various waiting
 * strategies to control how long to wait and what stage of processing to wait for.
 *
 * @author ahoo wang
 * @see CommandBus
 * @see WaitStrategy
 * @see CommandResult
 */
interface CommandGateway : CommandBus {
    /**
     * Sends a command message with a specified wait strategy.
     *
     * This method sends the command to the appropriate aggregate processor and returns
     * a Mono that completes when the command reaches the stage specified by the wait strategy.
     *
     * @param C the type of the command
     * @param command the command message to send
     * @param waitStrategy the strategy defining how long to wait and what to wait for
     * @return a Mono emitting the client command exchange when the wait condition is met
     * @see WaitStrategy
     * @see ClientCommandExchange
     */
    fun <C : Any> send(
        command: CommandMessage<C>,
        waitStrategy: WaitStrategy
    ): Mono<out ClientCommandExchange<C>>

    /**
     * Sends a command and returns a stream of command results as processing progresses.
     *
     * This method provides real-time updates on the command's processing status,
     * emitting CommandResult objects at various stages of the command lifecycle.
     *
     * @param C the type of the command
     * @param command the command message to send
     * @param waitStrategy the strategy defining the processing stages to monitor
     * @return a Flux emitting CommandResult objects as the command progresses
     * @see CommandResult
     * @see WaitStrategy
     */
    fun <C : Any> sendAndWaitStream(
        command: CommandMessage<C>,
        waitStrategy: WaitStrategy
    ): Flux<CommandResult>

    /**
     * Sends a command and waits for the final result.
     *
     * This method blocks until the command processing is complete or fails.
     * If the command fails, it throws a CommandResultException containing the error details.
     *
     * @param C the type of the command
     * @param command the command message to send
     * @param waitStrategy the strategy defining what stage to wait for
     * @return a Mono emitting the final CommandResult
     * @throws CommandResultException if the command processing fails
     * @see CommandResult
     * @see WaitStrategy
     * @see CommandResultException
     */
    @Throws(CommandResultException::class)
    fun <C : Any> sendAndWait(
        command: CommandMessage<C>,
        waitStrategy: WaitStrategy
    ): Mono<CommandResult>

    /**
     * Sends a command and waits until it is successfully sent to the command bus.
     *
     * This convenience method waits for the command to be accepted by the command bus
     * but does not wait for actual processing by the aggregate.
     *
     * @param C the type of the command
     * @param command the command message to send
     * @return a Mono emitting the CommandResult when the command is sent
     * @see sendAndWait
     * @see WaitingForStage.sent
     */
    fun <C : Any> sendAndWaitForSent(command: CommandMessage<C>): Mono<CommandResult> =
        sendAndWait(command, WaitingForStage.sent(command.commandId))

    /**
     * Sends a command and waits until it is fully processed by the aggregate.
     *
     * This convenience method waits for the command to be validated, executed by the
     * aggregate, and for any resulting events to be published.
     *
     * @param C the type of the command
     * @param command the command message to send
     * @return a Mono emitting the CommandResult when processing is complete
     * @see sendAndWait
     * @see WaitingForStage.processed
     */
    fun <C : Any> sendAndWaitForProcessed(command: CommandMessage<C>): Mono<CommandResult> =
        sendAndWait(command, WaitingForStage.processed(command.commandId))

    /**
     * Sends a command and waits until the aggregate state is snapshotted.
     *
     * This convenience method waits for the command processing and subsequent
     * snapshot creation, which is useful for ensuring data consistency in
     * high-throughput scenarios.
     *
     * @param C the type of the command
     * @param command the command message to send
     * @return a Mono emitting the CommandResult when snapshot is created
     * @see sendAndWait
     * @see WaitingForStage.snapshot
     */
    fun <C : Any> sendAndWaitForSnapshot(command: CommandMessage<C>): Mono<CommandResult> =
        sendAndWait(command, WaitingForStage.snapshot(command.commandId))
}

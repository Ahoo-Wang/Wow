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

import jakarta.validation.Validator
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.command.validation.CommandValidator
import me.ahoo.wow.command.validation.validateCommand
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.WaitCoordinator
import me.ahoo.wow.command.wait.WaitHandle
import me.ahoo.wow.command.wait.WaitPlan
import me.ahoo.wow.command.wait.extractWaitPlan
import me.ahoo.wow.command.wait.notifyAndForget
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.reactor.thenDefer
import me.ahoo.wow.reactor.thenRunnable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Default implementation of the CommandGateway interface.
 * This gateway provides comprehensive command handling including validation,
 * idempotency checking, and various sending strategies with optional waiting.
 *
 * @property commandWaitEndpoint The endpoint for command waiting functionality.
 * @property commandBus The underlying command bus for sending commands.
 * @property validator The validator for command body validation.
 * @property requestIdChecker Checker for command request ID idempotency.
 * @property waitCoordinator Coordinator for managing wait handles.
 * @property commandWaitNotifier Notifier for command wait signals.
 */
class DefaultCommandGateway(
    private val commandWaitEndpoint: CommandWaitEndpoint,
    private val commandBus: CommandBus,
    private val validator: Validator,
    private val requestIdChecker: RequestIdChecker,
    private val waitCoordinator: WaitCoordinator,
    private val commandWaitNotifier: CommandWaitNotifier,
) : CommandGateway,
    CommandBus by commandBus {
    /**
     * Validates the command body using both self-validation (if implements CommandValidator)
     * and external validation through the configured validator.
     *
     * @param C The type of the command body.
     * @param commandBody The command body to validate.
     * @throws jakarta.validation.ConstraintViolationException if validation fails.
     */
    private fun <C : Any> validate(commandBody: C) {
        if (commandBody is CommandValidator) {
            commandBody.validate()
        }
        validator.validateCommand(commandBody)
    }

    /**
     * Performs idempotency check for the command to prevent duplicate processing.
     * If the command has already been processed, throws DuplicateRequestIdException.
     *
     * @param command The command message to check for idempotency.
     * @return A Mono that completes when the check passes, or errors if duplicate.
     * @throws DuplicateRequestIdException if the command request ID is not unique.
     */
    private fun idempotencyCheck(command: CommandMessage<*>): Mono<Void> =
        requestIdChecker
            .check(command.aggregateId, command.requestId)
            .flatMap {
                /*
                 * 检查命令幂等性，如果该命令通过幂等性检查则返回 {@code true},表示该命令不重复.
                 */
                if (it) {
                    return@flatMap Mono.empty<Void>()
                }
                Mono.error(DuplicateRequestIdException(command.aggregateId, command.requestId))
            }

    /**
     * Performs comprehensive pre-send checks including idempotency and validation.
     *
     * @param C The type of the command body.
     * @param command The command message to check.
     * @return A Mono that completes when all checks pass.
     * @throws DuplicateRequestIdException if the command is not idempotent.
     * @throws jakarta.validation.ConstraintViolationException if validation fails.
     */
    private fun <C : Any> check(command: CommandMessage<C>): Mono<Void> =
        idempotencyCheck(command)
            .thenRunnable {
                validate(command.body)
            }

    /**
     * Sends a command message through the command bus after performing validation and idempotency checks.
     * Notifies wait plans if configured in the message header.
     *
     * @param message The command message to send.
     * @return A Mono that completes when the command is successfully sent.
     * @throws DuplicateRequestIdException if the command is not idempotent.
     * @throws jakarta.validation.ConstraintViolationException if validation fails.
     */
    override fun send(message: CommandMessage<*>): Mono<Void> {
        return check(message)
            .thenDefer {
                commandBus.send(message)
            }
            .doOnSuccess {
                val waitPlan = message.header.extractWaitPlan() ?: return@doOnSuccess
                val waitSignal = message.commandSentSignal(waitPlan.waitCommandId)
                commandWaitNotifier.notifyAndForget(waitPlan, waitSignal)
            }.doOnError {
                val waitPlan = message.header.extractWaitPlan() ?: return@doOnError
                val waitSignal = message.commandSentSignal(waitPlan.waitCommandId, it)
                commandWaitNotifier.notifyAndForget(waitPlan, waitSignal)
            }
    }

    /**
     * Sends a command and completes with the SENT stage result as soon as the command bus accepts it.
     *
     * The SENT signal is synthesized by this gateway itself once [CommandBus.send] completes, so this
     * fast path skips the wait plan propagation, handle allocation, and wait-header propagation that
     * [sendAndWait] requires. Downstream stage notifiers see no wait headers and therefore stay no-op,
     * which matches the SENT-only contract: no stage after SENT is ever waited on.
     *
     * @param C The type of the command body.
     * @param command The command message to send.
     * @return A Mono emitting the SENT stage CommandResult.
     * @throws CommandResultException if the pre-send checks fail or the command bus rejects the command.
     */
    override fun <C : Any> sendAndWaitForSent(command: CommandMessage<C>): Mono<CommandResult> =
        check(command)
            .thenDefer {
                commandBus.send(command)
            }.then(
                Mono.fromCallable {
                    CommandResult(
                        id = generateGlobalId(),
                        waitCommandId = command.commandId,
                        stage = CommandStage.SENT,
                        contextName = command.aggregateId.contextName,
                        aggregateName = command.aggregateId.aggregateName,
                        tenantId = command.aggregateId.tenantId,
                        aggregateId = command.aggregateId.id,
                        aggregateVersion = command.aggregateVersion,
                        requestId = command.requestId,
                        commandId = command.commandId,
                        function = COMMAND_GATEWAY_FUNCTION,
                    )
                },
            ).onErrorMap {
                CommandResultException(
                    it.toResult(
                        waitCommandId = command.commandId,
                        commandMessage = command,
                    ),
                    it,
                )
            }

    /**
     * Sends a command and returns a stream of command results as they become available.
     * This method allows monitoring the progress of command execution in real-time.
     *
     * @param C The type of the command body.
     * @param command The command message to send.
     * @param waitPlan The plan defining how and what to wait for.
     * @return A Flux emitting CommandResult instances as they are produced.
     * @throws DuplicateRequestIdException if the command is not idempotent.
     * @throws jakarta.validation.ConstraintViolationException if validation fails.
     * @throws IllegalArgumentException if the wait plan doesn't support void commands when needed.
     */
    override fun <C : Any> sendAndWaitStream(
        command: CommandMessage<C>,
        waitPlan: WaitPlan
    ): Flux<CommandResult> =
        Flux.defer {
            validateVoidCommandWaitPlan(command, waitPlan)
            check(command)
                .mapToCommandResultException(command, waitPlan)
                .thenMany(
                    Flux.defer {
                        val handle = waitCoordinator.createStream(waitPlan)
                        sendWithRegisteredWaitHandle(command, waitPlan, handle)
                            .thenMany(
                                handle.stream().map { waitSignal ->
                                    waitSignal.toResult(command)
                                }
                            ).doOnCancel {
                                handle.cancel()
                            }
                    }
                )
        }

    /**
     * Sends a command and waits for the final result.
     * Throws CommandResultException if the command execution fails.
     *
     * @param C The type of the command body.
     * @param command The command message to send.
     * @param waitPlan The plan defining how and what to wait for.
     * @return A Mono emitting the final CommandResult.
     * @throws DuplicateRequestIdException if the command is not idempotent.
     * @throws jakarta.validation.ConstraintViolationException if validation fails.
     * @throws IllegalArgumentException if the wait plan doesn't support void commands when needed.
     * @throws CommandResultException if the command execution fails.
     */
    override fun <C : Any> sendAndWait(
        command: CommandMessage<C>,
        waitPlan: WaitPlan
    ): Mono<CommandResult> =
        Mono.defer {
            validateVoidCommandWaitPlan(command, waitPlan)
            check(command)
                .mapToCommandResultException(command, waitPlan)
                .then(
                    Mono.defer {
                        val handle = waitCoordinator.createLast(waitPlan)
                        sendWithRegisteredWaitHandle(command, waitPlan, handle)
                            .then(
                                handle.await()
                                    .map { waitSignal ->
                                        waitSignal.toResult(command)
                                            .apply {
                                                if (!succeeded) {
                                                    throw CommandResultException(this)
                                                }
                                            }
                                    }
                            ).doOnCancel {
                                handle.cancel()
                            }
                    }
                )
        }

    /**
     * Sends a command with a specific wait plan.
     * This method handles wait plan propagation and handle cleanup.
     *
     * @param C The type of the command body.
     * @param command The command message to send.
     * @param waitPlan The plan defining how and what to wait for.
     * @return A Mono that completes when the command is sent successfully.
     * @throws DuplicateRequestIdException if the command is not idempotent.
     * @throws jakarta.validation.ConstraintViolationException if validation fails.
     * @throws IllegalArgumentException if the wait plan doesn't support void commands when needed.
     *
     */
    private fun <C : Any> sendWithRegisteredWaitHandle(
        command: CommandMessage<C>,
        waitPlan: WaitPlan,
        waitHandle: WaitHandle
    ): Mono<Void> {
        return Mono.defer {
            waitPlan.propagate(commandWaitEndpoint, command.header)
            commandBus.send(command)
        }.doOnSuccess {
            val waitSignal = command.commandSentSignal(waitPlan.waitCommandId)
            waitHandle.next(waitSignal)
        }.doOnError {
            val waitSignal = command.commandSentSignal(waitPlan.waitCommandId, it)
            waitHandle.next(waitSignal)
            waitHandle.error(it)
        }.doOnCancel {
            waitHandle.cancel()
        }.mapToCommandResultException(command, waitPlan)
    }

    private fun <C : Any> validateVoidCommandWaitPlan(
        command: CommandMessage<C>,
        waitPlan: WaitPlan
    ) {
        if (!command.isVoid || waitPlan.supportVoidCommand) {
            return
        }
        val error = IllegalArgumentException(
            "The wait plan[${waitPlan.javaClass.simpleName}] for the void command must support void command."
        )
        throw error
    }

    private fun <T : Any, C : Any> Mono<T>.mapToCommandResultException(
        command: CommandMessage<C>,
        waitPlan: WaitPlan
    ): Mono<T> =
        onErrorMap {
            CommandResultException(
                it.toResult(
                    waitCommandId = waitPlan.waitCommandId,
                    commandMessage = command,
                ),
                it,
            )
        }
}

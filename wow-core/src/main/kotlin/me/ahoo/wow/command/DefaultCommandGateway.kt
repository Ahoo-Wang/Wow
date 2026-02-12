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
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.WaitStrategyRegistrar
import me.ahoo.wow.command.wait.extractWaitStrategy
import me.ahoo.wow.command.wait.notifyAndForget
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.modeling.materialize
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
 * @property idempotencyCheckerProvider Provider for checking command idempotency.
 * @property waitStrategyRegistrar Registrar for managing wait strategies.
 * @property commandWaitNotifier Notifier for command wait signals.
 */
class DefaultCommandGateway(
    private val commandWaitEndpoint: CommandWaitEndpoint,
    private val commandBus: CommandBus,
    private val validator: Validator,
    private val idempotencyCheckerProvider: AggregateIdempotencyCheckerProvider,
    private val waitStrategyRegistrar: WaitStrategyRegistrar,
    private val commandWaitNotifier: CommandWaitNotifier
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
        idempotencyCheckerProvider
            .getChecker(command.aggregateId.namedAggregate.materialize())
            .check(command.requestId)
            .doOnNext {
                /*
                 * 检查命令幂等性，如果该命令通过幂等性检查则返回 {@code true},表示该命令不重复.
                 */
                if (!it) {
                    throw DuplicateRequestIdException(command.aggregateId, command.requestId)
                }
            }.then()

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
     * Notifies wait strategies if configured in the message header.
     *
     * @param message The command message to send.
     * @return A Mono that completes when the command is successfully sent.
     * @throws DuplicateRequestIdException if the command is not idempotent.
     * @throws jakarta.validation.ConstraintViolationException if validation fails.
     */
    override fun send(message: CommandMessage<*>): Mono<Void> {
        return check(message)
            .then(commandBus.send(message))
            .doOnSuccess {
                val waitStrategy = message.header.extractWaitStrategy() ?: return@doOnSuccess
                val waitSignal = message.commandSentSignal(waitStrategy.waitCommandId)
                commandWaitNotifier.notifyAndForget(waitStrategy, waitSignal)
            }.doOnError {
                val waitStrategy = message.header.extractWaitStrategy() ?: return@doOnError
                val waitSignal = message.commandSentSignal(waitStrategy.waitCommandId, it)
                commandWaitNotifier.notifyAndForget(waitStrategy, waitSignal)
            }
    }

    /**
     * Sends a command and returns a stream of command results as they become available.
     * This method allows monitoring the progress of command execution in real-time.
     *
     * @param C The type of the command body.
     * @param command The command message to send.
     * @param waitStrategy The strategy defining how and what to wait for.
     * @return A Flux emitting CommandResult instances as they are produced.
     * @throws DuplicateRequestIdException if the command is not idempotent.
     * @throws jakarta.validation.ConstraintViolationException if validation fails.
     * @throws IllegalArgumentException if the wait strategy doesn't support void commands when needed.
     */
    override fun <C : Any> sendAndWaitStream(
        command: CommandMessage<C>,
        waitStrategy: WaitStrategy
    ): Flux<CommandResult> =
        send(command, waitStrategy)
            .flatMapMany {
                waitStrategy
                    .waiting()
                    .map { waitSignal ->
                        waitSignal.toResult(it.message)
                    }
            }

    /**
     * Sends a command and waits for the final result.
     * Throws CommandResultException if the command execution fails.
     *
     * @param C The type of the command body.
     * @param command The command message to send.
     * @param waitStrategy The strategy defining how and what to wait for.
     * @return A Mono emitting the final CommandResult.
     * @throws DuplicateRequestIdException if the command is not idempotent.
     * @throws jakarta.validation.ConstraintViolationException if validation fails.
     * @throws IllegalArgumentException if the wait strategy doesn't support void commands when needed.
     * @throws CommandResultException if the command execution fails.
     */
    override fun <C : Any> sendAndWait(
        command: CommandMessage<C>,
        waitStrategy: WaitStrategy
    ): Mono<CommandResult> =
        send(command, waitStrategy)
            .flatMap {
                waitStrategy
                    .waitingLast()
                    .map { waitSignal ->
                        waitSignal
                            .toResult(it.message)
                            .apply {
                                if (!succeeded) {
                                    throw CommandResultException(this)
                                }
                            }
                    }
            }

    /**
     * Sends a command with a specific wait strategy and returns a command exchange for tracking.
     * This method handles wait strategy registration, propagation, and cleanup.
     *
     * @param C The type of the command body.
     * @param command The command message to send.
     * @param waitStrategy The strategy defining how and what to wait for.
     * @return A Mono emitting a ClientCommandExchange for tracking the command execution.
     * @throws DuplicateRequestIdException if the command is not idempotent.
     * @throws jakarta.validation.ConstraintViolationException if validation fails.
     * @throws IllegalArgumentException if the wait strategy doesn't support void commands when needed.
     *
     * @sample
     * ```
     * val command = SimpleCommandMessage(body = MyCommand(), aggregateId = myAggregateId)
     * val waitStrategy = SimpleWaitStrategy()
     * val exchange = gateway.send(command, waitStrategy).block()
     * // Use exchange to track command progress
     * ```
     */
    override fun <C : Any> send(
        command: CommandMessage<C>,
        waitStrategy: WaitStrategy
    ): Mono<out ClientCommandExchange<C>> {
        if (command.isVoid) {
            require(waitStrategy.supportVoidCommand) {
                "The wait strategy[${waitStrategy.javaClass.simpleName}] for the void command must support void command."
            }
        }
        val commandExchange: ClientCommandExchange<C> = SimpleClientCommandExchange(command, waitStrategy)
        return check(command)
            .thenDefer {
                waitStrategy.propagate(commandWaitEndpoint.endpoint, command.header)
                commandBus
                    .send(command)
                    .doOnSubscribe {
                        waitStrategyRegistrar.register(waitStrategy)
                        waitStrategy.onFinally {
                            waitStrategyRegistrar.unregister(waitStrategy.waitCommandId)
                        }
                    }.doOnError {
                        waitStrategyRegistrar.unregister(waitStrategy.waitCommandId)
                    }.doOnCancel {
                        waitStrategyRegistrar.unregister(waitStrategy.waitCommandId)
                    }
            }.doOnSuccess {
                val waitSignal = command.commandSentSignal(waitStrategy.waitCommandId)
                waitStrategy.next(waitSignal)
            }.doOnError {
                val waitSignal = command.commandSentSignal(waitStrategy.waitCommandId, it)
                waitStrategy.next(waitSignal)
            }.onErrorMap {
                CommandResultException(
                    it.toResult(
                        waitCommandId = waitStrategy.waitCommandId,
                        commandMessage = command,
                    ),
                    it,
                )
            }.thenReturn(commandExchange)
    }
}

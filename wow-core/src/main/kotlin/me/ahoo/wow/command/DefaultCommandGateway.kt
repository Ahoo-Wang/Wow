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
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.DEFAULT_WAIT_TIMEOUT
import me.ahoo.wow.command.wait.ExtractedWaitPlan
import me.ahoo.wow.command.wait.SkipsSuccessfulSentSignal
import me.ahoo.wow.command.wait.WaitCoordinator
import me.ahoo.wow.command.wait.WaitHandle
import me.ahoo.wow.command.wait.WaitPlan
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.COMMAND_WAIT_TAIL_STAGE
import me.ahoo.wow.command.wait.extractWaitPlan
import me.ahoo.wow.command.wait.notifyAndForget
import me.ahoo.wow.command.wait.timeout
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * The command gateway: a facade that puts admission in front of a [CommandBus] it does not own.
 *
 * Every send goes through one admission chain ([CommandAdmission]): validate, check the request ID, and, for a wait,
 * register the wait handle and build the message with its wait headers. The SENT signal is produced in one place
 * ([sendAdmitted]), after the bus accepted or rejected the message, and handed to whoever waits for it. Every wait is
 * bounded by one deadline implementation ([WaitDeadline]).
 *
 * [receiver] delegates to [commandBus]. [close] releases only the gateway's own deadline timer: the bus has its own
 * owner (in Spring, its own bean) and is closed by it.
 *
 * @param commandWaitEndpoint The endpoint wait signals for this node are sent to.
 * @param commandBus The underlying command bus the admitted commands are sent through.
 * @param validator The validator for command bodies.
 * @param requestIdChecker Checker for command request ID idempotency.
 * @param waitCoordinator Coordinator for managing wait handles.
 * @param commandWaitNotifier Notifier for the SENT signal of a command that carries an upstream wait plan.
 */
class DefaultCommandGateway(
    commandWaitEndpoint: CommandWaitEndpoint,
    private val commandBus: CommandBus,
    validator: Validator,
    requestIdChecker: RequestIdChecker,
    private val waitCoordinator: WaitCoordinator,
    private val commandWaitNotifier: CommandWaitNotifier,
) : CommandGateway,
    CommandBus by commandBus {
    override val enforcesCommandWaitTimeout: Boolean = true

    private val admission = CommandAdmission(commandWaitEndpoint, validator, requestIdChecker)

    private val waitTimer by lazy { Schedulers.newSingle("wow-command-wait", true) }
    private val deadline by lazy { WaitDeadline(waitTimer) }

    /**
     * Releases the deadline timer; registered deadlines stay alive without retaining a shutdown-waiting thread. The
     * command bus is not closed: the gateway did not create it.
     */
    override fun close() {
        waitTimer.disposeGracefully().subscribe().dispose()
    }

    override fun receiver(
        subscription: MessageSubscription,
    ): MessageReceiver<ServerCommandExchange<*>> =
        commandBus.receiver(subscription)

    /**
     * Sends an admitted [message]. When the bus rejects it, its request-ID reservation is released, so that retrying
     * the same request is not rejected as a duplicate. [onSent], when someone waits, is told the outcome (`null` on
     * success, the failure otherwise); it is the only place a SENT signal is produced. Nothing is built when nobody
     * waits.
     */
    private fun sendAdmitted(
        message: CommandMessage<*>,
        onSent: ((Throwable?) -> Unit)? = null,
    ): Mono<Void> {
        val sending = Mono.defer { commandBus.send(message) }
        if (onSent == null) {
            return sending.doOnError { admission.release(message) }
        }
        return sending
            .doOnSuccess { onSent(null) }
            .doOnError {
                admission.release(message)
                onSent(it)
            }
    }

    /**
     * Sends a command through the admission chain. When the message carries the wait plan of an upstream command
     * (a command a saga sends for a waiting chain), its SENT signal, or its admission failure, goes to that plan.
     *
     * @throws DuplicateRequestIdException if the request ID was already used.
     * @throws jakarta.validation.ConstraintViolationException if validation fails.
     */
    override fun send(message: CommandMessage<*>): Mono<Void> {
        val upstreamWait = message.header.extractWaitPlan()
            ?: return admission.admit(message).then(sendAdmitted(message))
        return admission.admit(message)
            .doOnError { upstreamWait.notifySent(message, it) }
            .then(
                sendAdmitted(message) { error ->
                    if (error == null) {
                        commandWaitNotifier.notifyAndForget(
                            upstreamWait,
                            message.commandSentSignal(upstreamWait.waitCommandId)
                        )
                    } else {
                        upstreamWait.notifySent(message, error)
                    }
                }
            )
    }

    /**
     * Reports the SENT failure of [message] to this upstream wait, except for a saga-sent command in the tail of a
     * waiting chain: its parent saga reports the terminal send failure after its retries finish.
     */
    private fun ExtractedWaitPlan.notifySent(message: CommandMessage<*>, error: Throwable) {
        if (waitCommandId != message.commandId && message.header.containsKey(COMMAND_WAIT_TAIL_STAGE)) {
            return
        }
        commandWaitNotifier.notifyAndForget(this, message.commandSentSignal(waitCommandId, error))
    }

    /**
     * Sends a command and completes with the SENT stage result as soon as the command bus accepts it, without
     * registering a wait handle or writing wait headers: no stage after SENT is waited on. Bounded by the default
     * command wait timeout.
     *
     * @throws CommandResultException if admission fails or the command bus rejects the command.
     * @throws java.util.concurrent.TimeoutException if the default command wait deadline expires.
     */
    override fun <C : Any> sendAndWaitForSent(command: CommandMessage<C>): Mono<CommandResult> =
        admission.admit(command)
            .then(sendAdmitted(command))
            .then(Mono.fromCallable { command.sentResult() })
            .onErrorMap {
                CommandResultException(
                    it.toResult(waitCommandId = command.commandId, commandMessage = command),
                    it,
                )
            }.let { deadline.bound(it, DEFAULT_WAIT_TIMEOUT) }

    /**
     * Sends a command and streams its results as the waited stages report them.
     *
     * @throws CommandResultException if admission fails or the command bus rejects the command.
     * @throws IllegalArgumentException if [waitPlan] does not support a void command.
     */
    override fun <C : Any> sendAndWaitStream(
        command: CommandMessage<C>,
        waitPlan: WaitPlan
    ): Flux<CommandResult> =
        Flux.defer {
            validateVoidCommandWaitPlan(command, waitPlan)
            admission.admit(command)
                .mapToCommandResultException(command, waitPlan)
                .thenMany(
                    Flux.using(
                        { admission.registerWait(command, waitPlan, waitCoordinator::createStream) },
                        { admitted ->
                            sendWaited(admitted, waitPlan)
                                .thenMany(admitted.handle.stream().map { it.toResult(command) })
                        },
                        { it.handle.cancel() },
                    )
                )
        }.let { deadline.bound(it, waitPlan.timeout) }

    /**
     * Sends a command and waits for the result of the waited stage.
     *
     * @throws CommandResultException if admission fails, the command bus rejects the command, or the command failed.
     * @throws IllegalArgumentException if [waitPlan] does not support a void command.
     */
    override fun <C : Any> sendAndWait(
        command: CommandMessage<C>,
        waitPlan: WaitPlan
    ): Mono<CommandResult> =
        Mono.defer {
            validateVoidCommandWaitPlan(command, waitPlan)
            admission.admit(command)
                .mapToCommandResultException(command, waitPlan)
                .then(
                    Mono.using(
                        { admission.registerWait(command, waitPlan, waitCoordinator::createLast) },
                        { admitted ->
                            sendWaited(admitted, waitPlan)
                                .then(
                                    admitted.handle.await().map { signal ->
                                        signal.toResult(command).apply {
                                            if (!succeeded) {
                                                throw CommandResultException(this)
                                            }
                                        }
                                    }
                                )
                        },
                        { it.handle.cancel() },
                    )
                )
        }.let { deadline.bound(it, waitPlan.timeout) }

    /** Sends a command whose wait handle is registered, and hands its SENT signal to that handle. */
    private fun sendWaited(admitted: AdmittedWait<out WaitHandle>, waitPlan: WaitPlan): Mono<Void> {
        val handle = admitted.handle
        val message = admitted.message
        return sendAdmitted(message) { error ->
            if (error != null) {
                handle.next(message.commandSentSignal(waitPlan.waitCommandId, error))
                handle.error(error)
            } else if (handle !is SkipsSuccessfulSentSignal || waitPlan.target.stage == CommandStage.SENT) {
                handle.next(message.commandSentSignal(waitPlan.waitCommandId))
            }
        }.mapToCommandResultException(admitted.message, waitPlan)
    }

    private fun validateVoidCommandWaitPlan(
        command: CommandMessage<*>,
        waitPlan: WaitPlan
    ) {
        require(!command.isVoid || waitPlan.supportVoidCommand) {
            "The wait plan[${waitPlan.javaClass.simpleName}] for the void command must support void command."
        }
    }

    private fun <T : Any> Mono<T>.mapToCommandResultException(
        command: CommandMessage<*>,
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

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

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Validator
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.command.validation.CommandValidator
import me.ahoo.wow.command.validation.validateCommand
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitSignal.Companion.toWaitSignal
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.WaitStrategyRegistrar
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.reactor.thenDefer
import me.ahoo.wow.reactor.thenRunnable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks

class DefaultCommandGateway(
    private val commandWaitEndpoint: CommandWaitEndpoint,
    private val commandBus: CommandBus,
    private val validator: Validator,
    private val idempotencyCheckerProvider: AggregateIdempotencyCheckerProvider,
    private val waitStrategyRegistrar: WaitStrategyRegistrar
) : CommandGateway, CommandBus by commandBus {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    private fun <C : Any> validate(commandBody: C) {
        if (commandBody is CommandValidator) {
            commandBody.validate()
        }
        validator.validateCommand(commandBody)
    }

    private fun idempotencyCheck(command: CommandMessage<*>): Mono<Void> {
        return idempotencyCheckerProvider.getChecker(command.aggregateId.namedAggregate.materialize())
            .check(command.requestId)
            .doOnNext {
                /*
                 * 检查命令幂等性，如果该命令通过幂等性检查则返回 {@code true},表示该命令不重复.
                 */
                if (!it) {
                    throw DuplicateRequestIdException(command.aggregateId, command.requestId)
                }
            }.then()
    }

    private fun <C : Any> check(command: CommandMessage<C>): Mono<Void> {
        return idempotencyCheck(command)
            .thenRunnable {
                validate(command.body)
            }
    }

    override fun send(message: CommandMessage<*>): Mono<Void> {
        return check(message).then(commandBus.send(message))
    }

    override fun <C : Any> sendAndWaitStream(
        command: CommandMessage<C>,
        waitStrategy: WaitStrategy
    ): Flux<CommandResult> {
        return send(command, waitStrategy)
            .flatMapMany {
                waitStrategy.waiting()
                    .map { waitSignal ->
                        waitSignal.toResult(it.message)
                    }
            }
    }

    override fun <C : Any> sendAndWait(command: CommandMessage<C>, waitStrategy: WaitStrategy): Mono<CommandResult> {
        return send(command, waitStrategy)
            .flatMap {
                waitStrategy.waitingLast()
                    .map { waitSignal ->
                        waitSignal.toResult(it.message)
                            .apply {
                                if (!succeeded) {
                                    throw CommandResultException(this)
                                }
                            }
                    }
            }
    }

    override fun <C : Any> send(
        command: CommandMessage<C>,
        waitStrategy: WaitStrategy
    ): Mono<out ClientCommandExchange<C>> {
        return check(command).thenDefer {
            waitStrategy.inject(commandWaitEndpoint, command.header)
            waitStrategyRegistrar.register(command.commandId, waitStrategy)
            waitStrategy.onFinally {
                waitStrategyRegistrar.unregister(command.commandId)
            }
            val commandExchange: ClientCommandExchange<C> = SimpleClientCommandExchange(command, waitStrategy)
            commandBus.send(command)
                .thenEmitSentSignal(command, waitStrategy)
                .ensureUnregister(command)
                .thenReturn(commandExchange)
        }.onErrorMap {
            it.toCommandResultException(command)
        }
    }

    private fun Throwable.toCommandResultException(command: CommandMessage<*>): CommandResultException {
        return CommandResultException(this.toResult(command, processorName = COMMAND_GATEWAY_PROCESSOR_NAME), this)
    }

    private fun Mono<Void>.thenEmitSentSignal(command: CommandMessage<*>, waitStrategy: WaitStrategy): Mono<Void> {
        return doOnSuccess {
            safeEmitSentSignal(command, waitStrategy)
        }
    }

    private fun Mono<Void>.ensureUnregister(command: CommandMessage<*>): Mono<Void> {
        return doOnCancel {
            waitStrategyRegistrar.unregister(command.commandId)
        }.doOnError {
            waitStrategyRegistrar.unregister(command.commandId)
        }
    }

    private fun safeEmitSentSignal(command: CommandMessage<*>, waitStrategy: WaitStrategy) {
        val waitSignal = COMMAND_GATEWAY_FUNCTION.toWaitSignal(
            id = generateGlobalId(),
            commandId = command.commandId,
            stage = CommandStage.SENT,
        )
        try {
            // 防止基于内存的消息总线聚合处理信号早于命令发送完成而抛出异常。
            waitStrategy.next(waitSignal)
        } catch (emissionError: Sinks.EmissionException) {
            log.warn(emissionError) {
                "The wait strategy [${command.commandId}] is cancelled or terminated, so the signal is not sent."
            }
        }
    }
}

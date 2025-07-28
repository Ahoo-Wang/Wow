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
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.WaitStrategyRegistrar
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.reactor.thenDefer
import me.ahoo.wow.reactor.thenRunnable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class DefaultCommandGateway(
    private val commandWaitEndpoint: CommandWaitEndpoint,
    private val commandBus: CommandBus,
    private val validator: Validator,
    private val idempotencyCheckerProvider: AggregateIdempotencyCheckerProvider,
    private val waitStrategyRegistrar: WaitStrategyRegistrar
) : CommandGateway, CommandBus by commandBus {

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
        if (command.isVoid) {
            require(waitStrategy.supportVoidCommand) {
                "The wait strategy[${waitStrategy.javaClass.simpleName}] for the void command must support void command."
            }
        }
        val commandExchange: ClientCommandExchange<C> = SimpleClientCommandExchange(command, waitStrategy)
        return check(command).thenDefer {
            waitStrategy.inject(commandWaitEndpoint, command.header)
            waitStrategyRegistrar.register(command.commandId, waitStrategy)
            waitStrategy.onFinally {
                waitStrategyRegistrar.unregister(command.commandId)
            }
            commandBus.send(command)
                .doOnCancel {
                    waitStrategyRegistrar.unregister(command.commandId)
                }
        }.doOnSuccess {
            val waitSignal = command.commandSentSignal()
            waitStrategy.next(waitSignal)
        }.onErrorResume {
            val waitSignal = command.commandSentSignal(it)
            waitStrategy.next(waitSignal)
            Mono.empty()
        }.thenReturn(commandExchange)
    }
}

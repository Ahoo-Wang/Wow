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

import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWaitEndpoint
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.command.wait.WaitStrategyRegistrar
import me.ahoo.wow.command.wait.WaitingFor
import me.ahoo.wow.command.wait.injectWaitStrategy
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import reactor.core.publisher.Mono
import javax.validation.Validator

class DefaultCommandGateway(
    private val commandWaitEndpoint: CommandWaitEndpoint,
    private val commandBus: CommandBus,
    private val idempotencyChecker: IdempotencyChecker,
    private val waitStrategyRegistrar: WaitStrategyRegistrar,
    private val validator: Validator
) : CommandGateway, CommandBus by commandBus {

    private fun validate(command: CommandMessage<*>): Mono<Boolean> {
        val constraintViolations = validator.validate(command.body)
        if (constraintViolations.isNotEmpty()) {
            return Mono.error(CommandValidationException(command, constraintViolations))
        }
        return idempotencyChecker.check(command.requestId)
            .doOnNext {
                /*
                 * 检查命令幂等性，如果该命令通过幂等性检查则返回 {@code true},表示该命令不重复.
                 */
                if (!it) {
                    throw DuplicateRequestIdException(command.aggregateId, command.requestId)
                }
            }
    }

    override fun send(message: CommandMessage<*>): Mono<Void> {
        return validate(message).flatMap {
            commandBus.send(message)
        }
    }

    override fun <C : Any> send(
        command: CommandMessage<C>,
        waitStrategy: WaitStrategy
    ): Mono<out ClientCommandExchange<C>> {
        require(waitStrategy is WaitingFor) { "waitStrategy must be WaitingFor." }
        require(waitStrategy.stage != CommandStage.SENT) {
            "waitStrategy.stage must not be CommandStage.SENT. Use sendAndWaitForSent instead."
        }
        return validate(command).flatMap {
            command.header.injectWaitStrategy(
                commandWaitEndpoint = commandWaitEndpoint.endpoint,
                stage = waitStrategy.stage,
            )
            if (waitStrategy.stage != CommandStage.SENT) {
                waitStrategyRegistrar.register(command.commandId, waitStrategy)
            }
            val commandExchange: ClientCommandExchange<C> = SimpleClientCommandExchange(command, waitStrategy)
            commandBus.send(command)
                .doOnError {
                    waitStrategyRegistrar.unregister(command.commandId)
                }
                .thenReturn(commandExchange)
        }
    }
}

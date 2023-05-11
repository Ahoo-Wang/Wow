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

package me.ahoo.wow.tck.command

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandErrorCodes
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.configuration.asRequiredNamedAggregate
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.metrics.Metrics.metrizable
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.TimeUnit

abstract class CommandGatewaySpec {
    protected val namedAggregate = MockSendCommand::class.java.asRequiredNamedAggregate()
    protected val waitStrategyRegistrar = SimpleWaitStrategyRegistrar
    protected val idempotencyChecker: IdempotencyChecker = BloomFilterIdempotencyChecker(1000000, 0.000001)
    protected lateinit var commandBus: CommandBus
    protected lateinit var commandGateway: CommandGateway
    protected abstract fun createCommandBus(): CommandBus

    @BeforeEach
    fun setup() {
        commandBus = createCommandBus().metrizable()
        commandGateway = DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = commandBus,
            idempotencyChecker = idempotencyChecker,
            waitStrategyRegistrar = waitStrategyRegistrar,
            NoOpValidator,
        )
    }

    @Test
    fun send() {
        Schedulers.single().schedule {
            commandGateway
                .receive(setOf(namedAggregate)).subscribe()
        }
        commandGateway.sendAndWaitForSent(MockSendCommand(GlobalIdGenerator.generateAsString()).asCommandMessage())
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun sendGivenTimeout() {
        commandGateway.sendAndWaitForProcessed(MockSendCommand(GlobalIdGenerator.generateAsString()).asCommandMessage())
            .timeout(Duration.ofMillis(100))
            .test()
            .verifyTimeout(Duration.ofMillis(150))
    }

    @Test
    fun sendGivenDuplicate() {
        val commandMessage = MockSendCommand(GlobalIdGenerator.generateAsString()).asCommandMessage()
        Schedulers.single().schedule {
            commandBus
                .receive(setOf(namedAggregate)).subscribe()
        }
        commandGateway.sendAndWaitForSent(commandMessage)
            .test()
            .expectNextCount(1)
            .verifyComplete()

        commandGateway.sendAndWaitForSent(commandMessage)
            .test()
            .consumeNextWith {
                assertThat(it.stage, equalTo(CommandStage.SENT))
                assertThat(it.errorCode, equalTo(CommandErrorCodes.COMMAND_DUPLICATE))
            }
            .verifyComplete()
    }

    @Test
    fun sendThenWaitingForAggregate() {
        Schedulers.single().schedule {
            commandGateway
                .receive(setOf(namedAggregate))
                .doOnNext {
                    Schedulers.boundedElastic().schedule(
                        {
                            waitStrategyRegistrar.next(
                                SimpleWaitSignal(
                                    it.message.commandId,
                                    CommandStage.PROCESSED,
                                ),
                            )
                        },
                        10,
                        TimeUnit.MILLISECONDS
                    )
                }
                .subscribe()
        }
        commandGateway.sendAndWaitForProcessed(MockSendCommand(GlobalIdGenerator.generateAsString()).asCommandMessage())
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun receive() {
        commandGateway.receive(setOf(namedAggregate))
            .test()
            .consumeSubscriptionWith {
                Schedulers.single().schedule(
                    {
                        Flux.range(0, 10)
                            .flatMap {
                                commandGateway.sendAndWaitForSent(
                                    MockSendCommand(GlobalIdGenerator.generateAsString()).asCommandMessage(),
                                )
                            }.subscribe()
                    },
                    10,
                    TimeUnit.MILLISECONDS,
                )
            }
            .expectNextCount(10)
            .verifyTimeout(Duration.ofSeconds(2))
    }
}

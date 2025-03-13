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

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.TopicKind
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.COMMAND_GATEWAY_FUNCTION
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.CommandResultException
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.toResult
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.command.wait.WaitingFor
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import me.ahoo.wow.infra.idempotency.DefaultAggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.tck.messaging.MessageBusSpec
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.tck.mock.WrongCommandMessage
import me.ahoo.wow.test.validation.TestValidator
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.TimeUnit

abstract class CommandGatewaySpec : MessageBusSpec<CommandMessage<*>, ServerCommandExchange<*>, CommandGateway>() {
    override val topicKind: TopicKind
        get() = TopicKind.COMMAND
    override val namedAggregate: NamedAggregate
        get() = requiredNamedAggregate<MockCreateAggregate>()

    override fun createMessage(): CommandMessage<*> {
        return MockCreateAggregate(
            id = generateGlobalId(),
            data = generateGlobalId(),
        ).toCommandMessage()
    }

    protected val waitStrategyRegistrar = SimpleWaitStrategyRegistrar
    protected val idempotencyChecker: IdempotencyChecker = BloomFilterIdempotencyChecker(
        Duration.ofSeconds(1),
    ) {
        BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 1000000)
    }

    protected abstract fun createCommandBus(): CommandBus

    override fun createMessageBus(): CommandGateway {
        return DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = createCommandBus(),
            validator = TestValidator,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider { idempotencyChecker },
            waitStrategyRegistrar = waitStrategyRegistrar,
        )
    }

    @Test
    fun sendAndWaitForSent() {
        val message = createMessage()
        verify {
            sendAndWaitForSent(message)
                .test()
                .expectNextCount(1)
                .verifyComplete()
        }
    }

    @Test
    fun sendAndWaitForProcessed() {
        val message = createMessage()
        val processedSignal = SimpleWaitSignal(
            commandId = message.commandId,
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        verify {
            val waitStrategy = WaitingFor.processed()
            sendAndWait(message, waitStrategy)
                .test()
                .consumeSubscriptionWith {
                    waitStrategy.next(processedSignal)
                }
                .expectNextCount(1)
                .verifyComplete()
        }
    }

    @Test
    fun sendAndWaitForProcessedDefault() {
        val message = createMessage()
        val processedSignal = SimpleWaitSignal(
            commandId = message.commandId,
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        verify {
            sendAndWaitForProcessed(message)
                .test()
                .consumeSubscriptionWith {
                    Schedulers.boundedElastic().schedule({
                        waitStrategyRegistrar.next(processedSignal)
                    }, 100, TimeUnit.MILLISECONDS)
                }
                .expectNextCount(1)
                .verifyComplete()
        }
    }

    @Test
    fun sendAndWaitForSnapshot() {
        val message = createMessage()
        val processedSignal = SimpleWaitSignal(
            commandId = message.commandId,
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        val waitSignal = SimpleWaitSignal(
            commandId = message.commandId,
            stage = CommandStage.SNAPSHOT,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        verify {
            val waitStrategy = WaitingFor.snapshot()
            sendAndWait(message, waitStrategy)
                .test()
                .consumeSubscriptionWith {
                    waitStrategy.next(processedSignal)
                    waitStrategy.next(waitSignal)
                }
                .expectNextCount(1)
                .verifyComplete()
        }
    }

    @Test
    fun sendAndWaitForSnapshotDefault() {
        val message = createMessage()
        val processedSignal = SimpleWaitSignal(
            commandId = message.commandId,
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        val waitSignal = SimpleWaitSignal(
            commandId = message.commandId,
            stage = CommandStage.SNAPSHOT,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        verify {
            sendAndWaitForProcessed(message)
                .test()
                .consumeSubscriptionWith {
                    Schedulers.boundedElastic().schedule({
                        waitStrategyRegistrar.next(processedSignal)
                        waitStrategyRegistrar.next(waitSignal)
                    }, 100, TimeUnit.MILLISECONDS)
                }
                .expectNextCount(1)
                .verifyComplete()
        }
    }

    @Test
    fun sendGivenDuplicate() {
        val message = createMessage()
        verify {
            sendAndWaitForSent(message)
                .test()
                .expectNextCount(1)
                .verifyComplete()
            sendAndWaitForSent(message)
                .test()
                .consumeErrorWith {
                    assertThat(it, instanceOf(CommandResultException::class.java))
                    val commandResultException = it as CommandResultException
                    assertThat(commandResultException.commandResult.errorCode, equalTo(ErrorCodes.DUPLICATE_REQUEST_ID))
                    assertThat(commandResultException.cause, instanceOf(DuplicateRequestIdException::class.java))
                }
                .verify()
        }
    }

    @Test
    fun sendThenWaitingForProcessed() {
        val message = createMessage()
        verify {
            val waitStrategy = WaitingFor.processed()
            sendAndWait(message, waitStrategy)
                .test()
                .consumeSubscriptionWith {
                    waitStrategy.next(
                        SimpleWaitSignal(
                            message.commandId,
                            CommandStage.PROCESSED,
                            FunctionInfoData(FunctionKind.COMMAND, message.contextName, "", "")
                        ),
                    )
                }
                .expectNextCount(1)
                .verifyComplete()
        }
    }

    @Test
    fun sendThenWaitingForProcessedWhenError() {
        val message = createMessage()
        verify {
            val waitStrategy = WaitingFor.processed()
            sendAndWait(message, waitStrategy)
                .test()
                .consumeSubscriptionWith {
                    waitStrategy.next(
                        SimpleWaitSignal(
                            commandId = message.commandId,
                            stage = CommandStage.PROCESSED,
                            function = FunctionInfoData(FunctionKind.COMMAND, message.contextName, "", ""),
                            errorCode = "ERROR"
                        ),
                    )
                }
                .expectError(CommandResultException::class.java)
                .verify()
        }
    }

    @Test
    fun sendThenWaitingForProcessedWhenValidateError() {
        val message = WrongCommandMessage.toCommandMessage()
        verify {
            val waitStrategy = WaitingFor.processed()
            sendAndWait(message, waitStrategy)
                .test()
                .expectError(CommandResultException::class.java)
                .verify()
        }
    }

    @Test
    fun sendAndWaitForSnapshotStream() {
        val message = createMessage()
        val processedSignal = SimpleWaitSignal(
            commandId = message.commandId,
            stage = CommandStage.PROCESSED,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        val waitSignal = SimpleWaitSignal(
            commandId = message.commandId,
            stage = CommandStage.SNAPSHOT,
            function = COMMAND_GATEWAY_FUNCTION,
        )
        verify {
            val waitStrategy = WaitingFor.snapshot()
            sendAndWaitStream(message, waitStrategy)
                .test()
                .consumeSubscriptionWith {
                    waitStrategy.next(processedSignal)
                    waitStrategy.next(waitSignal)
                }
                .expectNext(processedSignal.toResult(message))
                .expectNext(waitSignal.toResult(message))
                .verifyComplete()
        }

    }
}

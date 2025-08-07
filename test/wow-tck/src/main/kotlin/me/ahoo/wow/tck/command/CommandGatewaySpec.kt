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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.TopicKind
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.CommandResultException
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.SimpleWaitStrategyRegistrar
import me.ahoo.wow.command.wait.stage.WaitingForStage
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
import org.junit.jupiter.api.Test
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.CountDownLatch

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

    private val functionInfo = FunctionInfoData(
        functionKind = FunctionKind.COMMAND,
        contextName = namedAggregate.contextName,
        processorName = namedAggregate.aggregateName,
        name = CommandGatewaySpec::class.simpleName!!,
    )
    protected val waitStrategyRegistrar = SimpleWaitStrategyRegistrar
    protected val idempotencyChecker: IdempotencyChecker = BloomFilterIdempotencyChecker(
        Duration.ofSeconds(1),
    ) {
        BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 2000000)
    }

    protected abstract fun createCommandBus(): CommandBus

    override fun createMessageBus(): CommandGateway {
        return DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = createCommandBus(),
            validator = TestValidator,
            idempotencyCheckerProvider = DefaultAggregateIdempotencyCheckerProvider { idempotencyChecker },
            waitStrategyRegistrar = waitStrategyRegistrar,
            LocalCommandWaitNotifier(SimpleWaitStrategyRegistrar)
        )
    }

    private fun verifyWaitStrategyDestroyed(commandWaitId: String) {
        repeat(10) {
            if (waitStrategyRegistrar.contains(commandWaitId)) {
                Thread.sleep(5)
            }
        }
        waitStrategyRegistrar.contains(commandWaitId).assert().isFalse()
    }

    @Test
    fun sendAndWaitForSent() {
        val message = createMessage()
        val waitStrategy = WaitingForStage.sent(message.commandId)
        verify {
            sendAndWaitStream(message, waitStrategy)
                .test()
                .expectNextCount(1)
                .verifyComplete()
        }
        verifyWaitStrategyDestroyed(waitStrategy.waitCommandId)
    }

    @Test
    fun sendAndWaitForSentDefault() {
        val message = createMessage()
        verify {
            sendAndWaitForSent(message)
                .test()
                .expectNextCount(1)
                .verifyComplete()
        }
        verifyWaitStrategyDestroyed(message.commandId)
    }

    @Test
    fun sendAndWaitForProcessed() {
        val message = createMessage()
        val waitStrategy = WaitingForStage.processed(message.commandId)
        val processedSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = message.commandId,
            aggregateId = message.aggregateId,
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        verify {
            sendAndWaitStream(message, waitStrategy)
                .test()
                .expectNextCount(1)
                .then {
                    waitStrategy.next(processedSignal)
                }
                .expectNextCount(1)
                .verifyComplete()
        }
        verifyWaitStrategyDestroyed(waitStrategy.waitCommandId)
    }

    @Test
    fun sendAndWaitForProcessedDefault() {
        val message = createMessage()
        val processedSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = message.commandId,
            commandId = message.commandId,
            aggregateId = message.aggregateId,
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        verify {
            sendAndWaitForProcessed(message)
                .test()
                .thenAwait(Duration.ofMillis(10))
                .then {
                    waitStrategyRegistrar.next(processedSignal)
                }
                .expectNextCount(1)
                .verifyComplete()
        }
        verifyWaitStrategyDestroyed(message.commandId)
    }

    @Test
    fun sendAndWaitForSnapshot() {
        val message = createMessage()
        val waitStrategy = WaitingForStage.snapshot(message.commandId)
        val processedSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = message.commandId,
            aggregateId = message.aggregateId,
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = message.commandId,
            aggregateId = message.aggregateId,
            stage = CommandStage.SNAPSHOT,
            function = functionInfo,
        )
        verify {
            sendAndWaitStream(message, waitStrategy)
                .test()
                .expectNextCount(1)
                .then {
                    waitStrategy.next(processedSignal)
                    waitStrategy.next(waitSignal)
                }
                .expectNextCount(2)
                .verifyComplete()
        }
        verifyWaitStrategyDestroyed(waitStrategy.waitCommandId)
    }

    @Test
    fun sendAndWaitForSnapshotDefault() {
        val message = createMessage()
        val processedSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = message.commandId,
            commandId = message.commandId,
            aggregateId = message.aggregateId,
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = message.commandId,
            commandId = message.commandId,
            aggregateId = message.aggregateId,
            stage = CommandStage.SNAPSHOT,
            function = functionInfo,
        )
        verify {
            sendAndWaitForSnapshot(message)
                .test()
                .expectSubscription()
                .thenAwait(Duration.ofMillis(10))
                .then {
                    waitStrategyRegistrar.next(processedSignal)
                    waitStrategyRegistrar.next(waitSignal)
                }
                .expectNextCount(1)
                .verifyComplete()
        }
        verifyWaitStrategyDestroyed(waitSignal.waitCommandId)
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
                    it.assert().isInstanceOf(CommandResultException::class.java)
                    val commandResultException = it as CommandResultException
                    commandResultException.commandResult.errorCode.assert().isEqualTo(ErrorCodes.DUPLICATE_REQUEST_ID)
                }
                .verify()
        }
        verifyWaitStrategyDestroyed(message.commandId)
    }

    @Test
    fun sendThenWaitingForProcessedWhenError() {
        val message = createMessage()
        val waitStrategy = WaitingForStage.processed(message.commandId)
        val errorSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitStrategy.waitCommandId,
            commandId = message.commandId,
            aggregateId = message.aggregateId,
            stage = CommandStage.PROCESSED,
            function = FunctionInfoData(FunctionKind.COMMAND, message.contextName, "", ""),
            errorCode = "ERROR"
        )
        verify {
            sendAndWaitStream(message, waitStrategy)
                .test()
                .expectNextCount(1)
                .then {
                    waitStrategyRegistrar.next(errorSignal)
                }
                .consumeNextWith {
                    it.errorCode.assert().isEqualTo(errorSignal.errorCode)
                }
                .verifyComplete()
        }
        verifyWaitStrategyDestroyed(waitStrategy.waitCommandId)
    }

    @Test
    fun sendThenWaitingForProcessedWhenValidateError() {
        val message = WrongCommandMessage.toCommandMessage()
        val waitStrategy = WaitingForStage.processed(message.commandId)
        verify {
            sendAndWait(message, waitStrategy)
                .test()
                .expectError(CommandResultException::class.java)
                .verify()
        }
        verifyWaitStrategyDestroyed(waitStrategy.waitCommandId)
    }

    @Test
    fun sendThenNotify() {
        val message = createMessage()
        val waitStrategy = WaitingForStage.sent(message.commandId)
        val countDownLatch = CountDownLatch(1)
        waitStrategy.onFinally {
            waitStrategyRegistrar.unregister(waitStrategy.waitCommandId)
            countDownLatch.countDown()
        }
        waitStrategyRegistrar.register(waitStrategy)
        waitStrategy.propagate("", message.header)
        waitStrategy.waitingLast().subscribe()
        verify {
            send(message)
                .test()
                .expectNextCount(0)
                .verifyComplete()
        }
        countDownLatch.await()
        verifyWaitStrategyDestroyed(waitStrategy.waitCommandId)
    }

    @Test
    fun sendThenNotifyError() {
        val message = WrongCommandMessage.toCommandMessage()
        val waitStrategy = WaitingForStage.sent(message.commandId)
        val countDownLatch = CountDownLatch(1)
        waitStrategy.onFinally {
            waitStrategyRegistrar.unregister(waitStrategy.waitCommandId)
            countDownLatch.countDown()
        }
        waitStrategyRegistrar.register(waitStrategy)
        waitStrategy.propagate("", message.header)
        waitStrategy.waitingLast().subscribe()
        verify {
            send(message)
                .subscribeOn(Schedulers.parallel())
                .test()
                .expectError()
                .verify()
        }
        countDownLatch.await()
        verifyWaitStrategyDestroyed(waitStrategy.waitCommandId)
    }
}

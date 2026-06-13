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
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.command.wait.WaitCoordinator
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
import reactor.kotlin.test.test
import java.time.Duration

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
    protected val waitCoordinator: WaitCoordinator = DefaultWaitCoordinator()
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
            waitCoordinator = waitCoordinator,
            commandWaitNotifier = LocalCommandWaitNotifier(waitCoordinator)
        )
    }

    protected fun verifyWaitRegistrationDestroyed(commandWaitId: String) {
        repeat(10) {
            if (waitCoordinator.contains(commandWaitId)) {
                Thread.sleep(5)
            }
        }
        waitCoordinator.contains(commandWaitId).assert().isFalse()
    }

    @Test
    fun sendAndWaitForSent() {
        val message = createMessage()
        val waitPlan = CommandWait.sent(message.commandId)
        verify {
            sendAndWaitStream(message, waitPlan)
                .test()
                .expectNextCount(1)
                .verifyComplete()
        }
        verifyWaitRegistrationDestroyed(waitPlan.waitCommandId)
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
        verifyWaitRegistrationDestroyed(message.commandId)
    }

    @Test
    fun sendAndWaitForProcessed() {
        val message = createMessage()
        val waitPlan = CommandWait.processed(message.commandId)
        val processedSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitPlan.waitCommandId,
            commandId = message.commandId,
            aggregateId = message.aggregateId,
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        verify {
            sendAndWaitStream(message, waitPlan)
                .test()
                .expectNextCount(1)
                .then {
                    waitCoordinator.signal(processedSignal)
                }
                .expectNextCount(1)
                .verifyComplete()
        }
        verifyWaitRegistrationDestroyed(waitPlan.waitCommandId)
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
                    waitCoordinator.signal(processedSignal)
                }
                .expectNextCount(1)
                .verifyComplete()
        }
        verifyWaitRegistrationDestroyed(message.commandId)
    }

    @Test
    fun sendAndWaitForSnapshot() {
        val message = createMessage()
        val waitPlan = CommandWait.snapshot(message.commandId)
        val processedSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitPlan.waitCommandId,
            commandId = message.commandId,
            aggregateId = message.aggregateId,
            stage = CommandStage.PROCESSED,
            function = functionInfo,
        )
        val waitSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitPlan.waitCommandId,
            commandId = message.commandId,
            aggregateId = message.aggregateId,
            stage = CommandStage.SNAPSHOT,
            function = functionInfo,
        )
        verify {
            sendAndWaitStream(message, waitPlan)
                .test()
                .expectNextCount(1)
                .then {
                    waitCoordinator.signal(processedSignal)
                    waitCoordinator.signal(waitSignal)
                }
                .expectNextCount(2)
                .verifyComplete()
        }
        verifyWaitRegistrationDestroyed(waitPlan.waitCommandId)
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
                    waitCoordinator.signal(processedSignal)
                    waitCoordinator.signal(waitSignal)
                }
                .expectNextCount(1)
                .verifyComplete()
        }
        verifyWaitRegistrationDestroyed(waitSignal.waitCommandId)
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
        verifyWaitRegistrationDestroyed(message.commandId)
    }

    @Test
    fun sendThenWaitProcessedWhenError() {
        val message = createMessage()
        val waitPlan = CommandWait.processed(message.commandId)
        val errorSignal = SimpleWaitSignal(
            id = generateGlobalId(),
            waitCommandId = waitPlan.waitCommandId,
            commandId = message.commandId,
            aggregateId = message.aggregateId,
            stage = CommandStage.PROCESSED,
            function = FunctionInfoData(FunctionKind.COMMAND, message.contextName, "", ""),
            errorCode = "ERROR"
        )
        verify {
            sendAndWaitStream(message, waitPlan)
                .test()
                .expectNextCount(1)
                .then {
                    waitCoordinator.signal(errorSignal)
                }
                .consumeNextWith {
                    it.errorCode.assert().isEqualTo(errorSignal.errorCode)
                }
                .verifyComplete()
        }
        verifyWaitRegistrationDestroyed(waitPlan.waitCommandId)
    }

    @Test
    fun sendThenWaitProcessedWhenValidateError() {
        val message = WrongCommandMessage.toCommandMessage()
        val waitPlan = CommandWait.processed(message.commandId)
        verify {
            sendAndWait(message, waitPlan)
                .test()
                .expectError(CommandResultException::class.java)
                .verify()
        }
        verifyWaitRegistrationDestroyed(waitPlan.waitCommandId)
    }

    @Test
    fun sendThenNotify() {
        val message = createMessage()
        val waitPlan = CommandWait.sent(message.commandId)
        val handle = waitCoordinator.createLast(waitPlan)
        waitPlan.propagate(SimpleCommandWaitEndpoint(""), message.header)
        verify {
            send(message)
                .then(handle.await())
                .test()
                .expectNextMatches {
                    it.waitCommandId == waitPlan.waitCommandId && it.stage == CommandStage.SENT
                }
                .verifyComplete()
        }
        verifyWaitRegistrationDestroyed(waitPlan.waitCommandId)
    }

    @Test
    fun sendThenNotifyError() {
        val message = WrongCommandMessage.toCommandMessage()
        val waitPlan = CommandWait.sent(message.commandId)
        val handle = waitCoordinator.createLast(waitPlan)
        waitPlan.propagate(SimpleCommandWaitEndpoint(""), message.header)
        verify {
            send(message)
                .onErrorResume { reactor.core.publisher.Mono.empty() }
                .then(handle.await())
                .test()
                .expectNextMatches {
                    it.waitCommandId == waitPlan.waitCommandId &&
                        it.stage == CommandStage.SENT &&
                        !it.succeeded
                }
                .verifyComplete()
        }
        verifyWaitRegistrationDestroyed(waitPlan.waitCommandId)
    }
}

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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.DEFAULT_WAIT_TIMEOUT
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.RecordingCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.TestCommandMessage
import me.ahoo.wow.command.wait.WaitCoordinator
import me.ahoo.wow.command.wait.WaitLastHandle
import me.ahoo.wow.command.wait.WaitPlan
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitStreamHandle
import me.ahoo.wow.command.wait.testSignal
import me.ahoo.wow.command.wait.withTimeout
import me.ahoo.wow.messaging.MessageSubscription
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class DefaultCommandGatewayTimeoutTest {
    @Test
    fun `send and wait for sent default timeout bounds pending command bus`() {
        val gateway = commandGateway(
            commandBus = TimeoutTestCommandBus(Mono.never()),
            waitCoordinator = DefaultWaitCoordinator(),
        )

        StepVerifier.withVirtualTime {
            gateway.sendAndWaitForSent(TestCommandMessage(id = "command-id"))
        }
            .thenAwait(DEFAULT_WAIT_TIMEOUT)
            .expectError(TimeoutException::class.java)
            .verify(Duration.ofSeconds(1))
    }

    @Test
    fun `send and wait default timeout releases handle and allows wait id reuse`() {
        val waitCoordinator = DefaultWaitCoordinator()
        val gateway = commandGateway(waitCoordinator = waitCoordinator)
        val waitPlan = CommandWait.processed("wait-command-id")

        StepVerifier.withVirtualTime {
            gateway.sendAndWait(TestCommandMessage(id = "command-id"), waitPlan)
        }
            .then { waitCoordinator.contains("wait-command-id").assert().isTrue() }
            .thenAwait(DEFAULT_WAIT_TIMEOUT)
            .expectError(TimeoutException::class.java)
            .verify()

        waitCoordinator.contains("wait-command-id").assert().isFalse()
        waitCoordinator.createLast(CommandWait.processed("wait-command-id")).cancel()
    }

    @Test
    fun `send and wait timeout releases handle while command bus is pending`() {
        val commandBus = TimeoutTestCommandBus(Mono.never())
        val waitCoordinator = DefaultWaitCoordinator()
        val gateway = commandGateway(commandBus = commandBus, waitCoordinator = waitCoordinator)
        val waitPlan = CommandWait.processed("wait-command-id")
            .withTimeout(Duration.ofSeconds(1))

        StepVerifier.withVirtualTime {
            gateway.sendAndWait(TestCommandMessage(id = "command-id"), waitPlan)
        }
            .then { waitCoordinator.contains("wait-command-id").assert().isTrue() }
            .thenAwait(Duration.ofSeconds(1))
            .expectError(TimeoutException::class.java)
            .verify()

        waitCoordinator.contains("wait-command-id").assert().isFalse()
    }

    @Test
    fun `send and wait timeout cancels custom handle once while command bus is pending`() {
        val waitCoordinator = CountingWaitCoordinator()
        val gateway = commandGateway(
            commandBus = TimeoutTestCommandBus(Mono.never()),
            waitCoordinator = waitCoordinator,
        )
        val waitPlan = CommandWait.processed("wait-command-id")
            .withTimeout(Duration.ofSeconds(1))

        StepVerifier.withVirtualTime {
            gateway.sendAndWait(TestCommandMessage(id = "command-id"), waitPlan)
        }
            .thenAwait(Duration.ofSeconds(1))
            .expectError(TimeoutException::class.java)
            .verify()

        waitCoordinator.lastHandle.cancelCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `send and wait stream timeout is an absolute deadline and releases handle`() {
        val waitCoordinator = DefaultWaitCoordinator()
        val gateway = commandGateway(waitCoordinator = waitCoordinator)
        val waitPlan = CommandWait.snapshot("wait-command-id")
            .withTimeout(Duration.ofSeconds(1))

        StepVerifier.withVirtualTime {
            gateway.sendAndWaitStream(TestCommandMessage(id = "command-id"), waitPlan)
        }
            .assertNext { it.stage.assert().isEqualTo(CommandStage.SENT) }
            .thenAwait(Duration.ofMillis(900))
            .then {
                waitCoordinator.signal(
                    testSignal(
                        stage = CommandStage.PROCESSED,
                        waitCommandId = "wait-command-id",
                        commandId = "command-id",
                    )
                ).assert().isTrue()
            }
            .assertNext { it.stage.assert().isEqualTo(CommandStage.PROCESSED) }
            .thenAwait(Duration.ofMillis(100))
            .expectError(TimeoutException::class.java)
            .verify()

        waitCoordinator.contains("wait-command-id").assert().isFalse()
    }

    private fun commandGateway(
        commandBus: CommandBus = TimeoutTestCommandBus(),
        waitCoordinator: WaitCoordinator,
    ): DefaultCommandGateway =
        DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint("test-command-wait-endpoint"),
            commandBus = commandBus,
            validator = NoOpValidator,
            requestIdChecker = RequestIdChecker { _, _ -> Mono.just(true) },
            waitCoordinator = waitCoordinator,
            commandWaitNotifier = RecordingCommandWaitNotifier(),
        )
}

private class CountingWaitCoordinator : WaitCoordinator {
    lateinit var lastHandle: CountingWaitLastHandle
        private set

    override fun createLast(plan: WaitPlan): WaitLastHandle =
        CountingWaitLastHandle(plan).also {
            lastHandle = it
        }

    override fun createStream(plan: WaitPlan): WaitStreamHandle =
        error("Stream handle is not used by this test.")

    override fun signal(signal: WaitSignal): Boolean = false

    override fun contains(waitCommandId: String): Boolean = false
}

private class CountingWaitLastHandle(
    override val plan: WaitPlan,
) : WaitLastHandle {
    override val waitCommandId: String = plan.waitCommandId
    val cancelCalls = AtomicInteger()

    override fun await(): Mono<WaitSignal> = Mono.never()

    override fun next(signal: WaitSignal): Boolean = false

    override fun error(throwable: Throwable) = Unit

    override fun cancel() {
        cancelCalls.incrementAndGet()
    }
}

private class TimeoutTestCommandBus(
    private val sendResult: Mono<Void> = Mono.empty(),
) : CommandBus {
    override fun send(message: CommandMessage<*>): Mono<Void> = sendResult

    override fun receive(subscription: MessageSubscription): Flux<ServerCommandExchange<*>> = Flux.empty()
}

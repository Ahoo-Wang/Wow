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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class DefaultCommandGatewayTimeoutTest {
    private val gateways = mutableListOf<DefaultCommandGateway>()

    @AfterEach
    fun closeGateways() {
        gateways.forEach(DefaultCommandGateway::close)
    }

    @Test
    fun `gateway close disposes its timer even when command bus close fails`() {
        val timer = Schedulers.newSingle("test-command-timer")
        val snapshot = Schedulers.setFactoryWithSnapshot(object : Schedulers.Factory {
            override fun newSingle(threadFactory: ThreadFactory): Scheduler = timer
        })
        val failure = IllegalStateException("close failed")
        val commandBus = object : CommandBus by TimeoutTestCommandBus() {
            override fun close() = throw failure
        }
        val gateway = commandGateway(commandBus, DefaultWaitCoordinator())
        try {
            StepVerifier.create(gateway.sendAndWaitForSent(TestCommandMessage(id = "sent")))
                .expectNextCount(1)
                .verifyComplete()
            runCatching { gateway.close() }.exceptionOrNull().assert().isSameAs(failure)
            timer.isDisposed.assert().isTrue()
        } finally {
            gateways.remove(gateway)
            Schedulers.resetFrom(snapshot)
            timer.dispose()
        }
    }

    @Test
    fun `global single scheduler work does not delay command timeout`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val work = Schedulers.single().schedule {
            try {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            } finally {
                finished.countDown()
            }
        }
        val gateway = commandGateway(waitCoordinator = DefaultWaitCoordinator())
        try {
            entered.await(1, TimeUnit.SECONDS).assert().isTrue()
            StepVerifier.create(
                gateway.sendAndWait(
                    TestCommandMessage(id = "independent-deadline"),
                    CommandWait.processed("independent-deadline").withTimeout(Duration.ofMillis(50)),
                )
            )
                .expectError(TimeoutException::class.java)
                .verify(Duration.ofSeconds(1))
        } finally {
            release.countDown()
            finished.await(1, TimeUnit.SECONDS).assert().isTrue()
            work.dispose()
        }
    }

    @Test
    fun `slow timeout observer does not occupy the timer thread`() {
        val delivery = Schedulers.newParallel("test-timeout-delivery", 2)
        val snapshot = Schedulers.setFactoryWithSnapshot(object : Schedulers.Factory {
            override fun newParallel(parallelism: Int, threadFactory: ThreadFactory): Scheduler = delivery
        })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val gateway = commandGateway(waitCoordinator = DefaultWaitCoordinator())
        val first = gateway.sendAndWait(
            TestCommandMessage(id = "slow-observer"),
            CommandWait.processed("slow-observer").withTimeout(Duration.ofMillis(50)),
        ).doOnError {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }.subscribe({}, { delivered.countDown() })
        try {
            entered.await(1, TimeUnit.SECONDS).assert().isTrue()
            StepVerifier.create(
                gateway.sendAndWait(
                    TestCommandMessage(id = "next-deadline"),
                    CommandWait.processed("next-deadline").withTimeout(Duration.ofMillis(50)),
                )
            )
                .expectError(TimeoutException::class.java)
                .verify(Duration.ofSeconds(1))
        } finally {
            release.countDown()
            delivered.await(1, TimeUnit.SECONDS).assert().isTrue()
            first.dispose()
            Schedulers.resetFrom(snapshot)
            delivery.dispose()
        }
    }

    @Test
    fun `closing gateway preserves the deadline of an active stream`() {
        val coordinator = DefaultWaitCoordinator()
        val gateway = commandGateway(waitCoordinator = coordinator)
        val waitPlan = CommandWait.snapshot("closing-stream").withTimeout(Duration.ofMillis(200))

        StepVerifier.create(gateway.sendAndWaitStream(TestCommandMessage(id = "closing-stream"), waitPlan))
            .assertNext { it.stage.assert().isEqualTo(CommandStage.SENT) }
            .then {
                gateway.close()
                coordinator.signal(
                    testSignal(
                        stage = CommandStage.PROCESSED,
                        waitCommandId = "closing-stream",
                        commandId = "closing-stream",
                    ),
                )
            }
            .assertNext { it.stage.assert().isEqualTo(CommandStage.PROCESSED) }
            .expectError(TimeoutException::class.java)
            .verify(Duration.ofSeconds(2))

        coordinator.contains("closing-stream").assert().isFalse()
    }

    @Test
    fun `closing gateway preserves the deadline of an active mono`() {
        val coordinator = DefaultWaitCoordinator()
        val gateway = commandGateway(waitCoordinator = coordinator)
        val waitPlan = CommandWait.processed("closing-mono").withTimeout(Duration.ofMillis(200))

        StepVerifier.create(gateway.sendAndWait(TestCommandMessage(id = "closing-mono"), waitPlan))
            .then { gateway.close() }
            .expectError(TimeoutException::class.java)
            .verify(Duration.ofSeconds(2))

        coordinator.contains("closing-mono").assert().isFalse()
    }

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
        ).also { gateways += it }
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

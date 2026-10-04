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

package me.ahoo.wow.opentelemetry.wait

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.DefaultRequestIdChecker
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.opentelemetry.Tracing.tracing
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.concurrent.CopyOnWriteArrayList

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TracingCommandGatewayWaitTest {
    @AfterEach
    fun resetOpenTelemetry() {
        GlobalOpenTelemetry.resetForTest()
    }

    @Test
    fun `closing traced gateway releases its command bus`() {
        val bus = InMemoryCommandBus()
        val subscription = MessageSubscription(MOCK_AGGREGATE_METADATA.namedAggregate)
        val receiver = bus.receive(subscription).subscribe()
        val coordinator = DefaultWaitCoordinator()
        val gateway = DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint(""),
            commandBus = bus,
            validator = NoOpValidator,
            requestIdChecker = DefaultRequestIdChecker(
                AggregateIdempotencyCheckerProvider { NoOpIdempotencyChecker },
            ),
            waitCoordinator = coordinator,
            commandWaitNotifier = LocalCommandWaitNotifier(coordinator),
        ).tracing()
        try {
            bus.subscriberCount(subscription.namedAggregates.single()).assert().isOne()
            gateway.close()
            bus.subscriberCount(subscription.namedAggregates.single()).assert().isZero()
        } finally {
            receiver.dispose()
            bus.close()
        }
    }

    @Test
    fun `runtime receiver preserves delegate runtime admission protocol`() {
        val subscription = MessageSubscription(emptySet(), receiverGroup = "runtime")
        val expected = MessageReceiver<ServerCommandExchange<*>>(Flux.empty())
        val delegate = mockk<CommandGateway> {
            every { runtimeReceiver(subscription) } returns expected
        }

        val actual = TracingCommandGateway(delegate).runtimeReceiver(subscription)

        actual.assert().isSameAs(expected)
        verify(exactly = 1) {
            delegate.runtimeReceiver(subscription)
        }
        verify(exactly = 0) {
            delegate.receiver(subscription)
        }
    }

    @Test
    @Order(1)
    fun `send and wait traces waiting stream`() {
        GlobalOpenTelemetry.resetForTest()
        val spanExporter = RecordingSpanExporter()
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build()
        OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .buildAndRegisterGlobal()

        val commandBus: CommandBus = InMemoryCommandBus().tracing()
        val waitCoordinator = DefaultWaitCoordinator()
        val commandGateway = DefaultCommandGateway(
            commandWaitEndpoint = SimpleCommandWaitEndpoint("test-command-wait-endpoint"),
            commandBus = commandBus,
            validator = NoOpValidator,
            requestIdChecker = DefaultRequestIdChecker(
                idempotencyCheckerProvider = AggregateIdempotencyCheckerProvider { NoOpIdempotencyChecker },
            ),
            waitCoordinator = waitCoordinator,
            commandWaitNotifier = LocalCommandWaitNotifier(waitCoordinator),
        ).tracing()
        commandGateway.enforcesCommandWaitTimeout.assert().isTrue()
        val command = MockCreateAggregate(
            id = generateGlobalId(),
            data = generateGlobalId(),
        ).toCommandMessage()
        val waitPlan = CommandWait.sent(command.commandId)

        commandGateway.use { gateway ->
            gateway.sendAndWait(command, waitPlan)
                .test()
                .expectNextCount(1)
                .verifyComplete()
            tracerProvider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS)

            spanExporter.spans
                .any { it.name.endsWith(".waiting") }
                .assert()
                .isTrue()
        }
    }

    /**
     * Runs after `send and wait traces waiting stream`: subscribing initialises `WaitPlanInstrumenter`, which keeps
     * the global OpenTelemetry instance of its first use.
     */
    @Test
    @Order(2)
    fun `convenience waits forward to the delegate's own implementation`() {
        val command = MockCreateAggregate(generateGlobalId(), generateGlobalId()).toCommandMessage()
        val sent = mockk<CommandResult>()
        val processed = mockk<CommandResult>()
        val snapshot = mockk<CommandResult>()
        val delegate = mockk<CommandGateway> {
            every { sendAndWaitForSent(command) } returns Mono.just(sent)
            every { sendAndWaitForProcessed(command) } returns Mono.just(processed)
            every { sendAndWaitForSnapshot(command) } returns Mono.just(snapshot)
        }
        val gateway = TracingCommandGateway(delegate)

        gateway.sendAndWaitForSent(command).test().expectNext(sent).verifyComplete()
        gateway.sendAndWaitForProcessed(command).test().expectNext(processed).verifyComplete()
        gateway.sendAndWaitForSnapshot(command).test().expectNext(snapshot).verifyComplete()

        verify(exactly = 0) { delegate.sendAndWait(any<CommandMessage<*>>(), any()) }
    }
}

private class RecordingSpanExporter : SpanExporter {
    val spans = CopyOnWriteArrayList<SpanData>()

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        this.spans.addAll(spans)
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

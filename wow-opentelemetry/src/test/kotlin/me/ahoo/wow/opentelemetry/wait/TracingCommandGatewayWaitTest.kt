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

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.DefaultRequestIdChecker
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.LocalCommandWaitNotifier
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.NoOpIdempotencyChecker
import me.ahoo.wow.opentelemetry.Tracing.tracing
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test
import java.util.concurrent.CopyOnWriteArrayList

class TracingCommandGatewayWaitTest {
    @AfterEach
    fun resetOpenTelemetry() {
        GlobalOpenTelemetry.resetForTest()
    }

    @Test
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
        val command = MockCreateAggregate(
            id = generateGlobalId(),
            data = generateGlobalId(),
        ).toCommandMessage()
        val waitPlan = CommandWait.sent(command.commandId)

        commandGateway
            .sendAndWait(command, waitPlan)
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

private class RecordingSpanExporter : SpanExporter {
    val spans = CopyOnWriteArrayList<SpanData>()

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        this.spans.addAll(spans)
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

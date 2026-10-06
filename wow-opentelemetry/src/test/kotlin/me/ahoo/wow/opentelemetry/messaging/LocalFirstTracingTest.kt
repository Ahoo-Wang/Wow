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

package me.ahoo.wow.opentelemetry.messaging

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.command.LocalFirstCommandBus
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.LocalHandoff
import me.ahoo.wow.opentelemetry.ReactorTraceContext
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.util.concurrent.atomic.AtomicReference

class LocalFirstTracingTest {
    private val openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().build())
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()

    private val instrumenter: Instrumenter<CommandMessage<*>, Unit> =
        Instrumenter.builder<CommandMessage<*>, Unit>(openTelemetry, "local-first-test") { "${it.name} send" }
            .buildProducerInstrumenter(MessageTextMapSetter())

    @Test
    fun `a locally handed-off message stays in the sender's trace`() {
        val handedOff = AtomicReference<CommandMessage<*>>()
        val delegate = mockk<LocalCommandBus> {
            every { handOff(any()) } answers {
                handedOff.set(firstArg())
                Mono.just(LocalHandoff.accepted(Mono.just(true)))
            }
        }
        val distributedBus = mockk<DistributedCommandBus> {
            every { send(any()) } returns Mono.empty()
        }
        val bus = LocalFirstCommandBus(distributedBus, TracingLocalCommandBus(delegate, instrumenter))
        val command = MockCreateAggregate(generateGlobalId(), generateGlobalId()).toCommandMessage()
        val sender = openTelemetry.getTracer("sender").spanBuilder("sender").startSpan()
        val senderContext = Context.root().with(sender)

        bus.send(command)
            .contextWrite { ReactorTraceContext.set(it, senderContext) }
            .test()
            .verifyComplete()
        sender.end()

        // A local aggregate's command is handed off locally.
        val traceparent = handedOff.get().header["traceparent"]
        traceparent.assert().isNotNull()
        // W3C traceparent: version-traceId-spanId-flags. The hand-off span is a child in the sender's trace.
        traceparent!!.split("-")[1].assert().isEqualTo(sender.spanContext.traceId)
    }
}

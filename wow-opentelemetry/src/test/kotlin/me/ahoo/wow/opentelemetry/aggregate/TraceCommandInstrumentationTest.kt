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

package me.ahoo.wow.opentelemetry.aggregate

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.opentelemetry.ExchangeAttributesExtractor
import me.ahoo.wow.opentelemetry.messaging.MessageExchangeTextMapGetter
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The command instrumentation that replaced `TraceAggregateFilter` (V5): one consumer span per handled command, named
 * `<aggregate>.<command>`, ending with the handling's outcome.
 */
class TraceCommandInstrumentationTest {
    private val spans = CopyOnWriteArrayList<SpanData>()
    private val instrumenter: Instrumenter<ServerCommandExchange<*>, Unit> = run {
        val exporter = object : SpanExporter {
            override fun export(spans: Collection<SpanData>): CompletableResultCode {
                this@TraceCommandInstrumentationTest.spans += spans
                return CompletableResultCode.ofSuccess()
            }

            override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

            override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
        }
        val openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build(),
            ).build()
        Instrumenter.builder<ServerCommandExchange<*>, Unit>(
            openTelemetry,
            "test",
            AggregateSpanNameExtractor,
        ).addAttributesExtractor(ExchangeAttributesExtractor())
            .buildConsumerInstrumenter(MessageExchangeTextMapGetter())
    }

    private val exchange = SimpleServerCommandExchange(
        MockCreateAggregate(id = "trace-1", data = "data").toCommandMessage(),
    )

    @Test
    fun `traces the handling of a command as one consumer span`() {
        var subscribed = false

        StepVerifier.create(
            TraceCommandInstrumentation(instrumenter).around(exchange, Mono.fromRunnable { subscribed = true }),
        ).verifyComplete()

        subscribed.assert().isTrue()
        val span = spans.single()
        span.name.assert().isEqualTo("${exchange.message.aggregateName}.${exchange.message.name}")
        span.kind.assert().isEqualTo(SpanKind.CONSUMER)
        span.status.statusCode.assert().isNotEqualTo(StatusCode.ERROR)
    }

    @Test
    fun `passes the failure on and records it on the span`() {
        val failure = IllegalStateException("handling failed")

        StepVerifier.create(TraceCommandInstrumentation(instrumenter).around(exchange, Mono.error(failure)))
            .expectErrorMatches { it === failure }
            .verify()

        spans.single().status.statusCode.assert().isEqualTo(StatusCode.ERROR)
    }
}

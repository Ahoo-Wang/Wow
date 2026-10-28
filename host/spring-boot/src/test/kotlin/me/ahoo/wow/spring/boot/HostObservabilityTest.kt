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

package me.ahoo.wow.spring.boot

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.observability.NodeMetrics
import me.ahoo.wow.observability.OtelObserver
import me.ahoo.wow.observability.OtelQueryObserver
import me.ahoo.wow.observability.ParkedMetrics
import me.ahoo.wow.observability.TracingTopicProducer
import me.ahoo.wow.observability.WowMetrics
import me.ahoo.wow.query.observe.QueryObserver
import me.ahoo.wow.query.store.StoreQueryBounds
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Tracing and metrics wired by [ObservabilityAutoConfiguration] (decision D174): with wow-observability on the class
 * path, the node's work is traced through the application's [OpenTelemetry] and measured in its [MeterRegistry].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HostObservabilityTest {
    private val host = startHost(sources = listOf(HostTestApp::class.java, TelemetryBeans::class.java))

    @AfterAll
    fun close() {
        host.close()
    }

    @Test
    fun `a request's trace should go on through the node, and its commit be measured`(): Unit = runBlocking {
        val beans = host.context.getBean(TelemetryBeans::class.java)
        val trace = "4bf92f3577b34da6a3ce929d0e0e4736"
        val caller = "00f067aa0ba902b7"
        val answer = host.post(
            "/bank/account/open-account",
            """{"owner":"kim"}""",
            mapOf("traceparent" to "00-$trace-$caller-01"),
        )
        answer.status.assert().isEqualTo(200)
        withTimeout(10.seconds) {
            while (beans.spans.finishedSpanItems.none { it.name == "execute $BANK.account" }) {
                delay(5)
            }
        }
        val admit = beans.spans.finishedSpanItems.single { it.name == "admit $BANK.account" }
        admit.kind.assert().isEqualTo(SpanKind.INTERNAL)
        admit.traceId.assert().isEqualTo(trace)
        admit.parentSpanId.assert().isEqualTo(caller)
        beans.spans.finishedSpanItems.single { it.name == "execute $BANK.account" }.parentSpanId
            .assert().isEqualTo(admit.spanId)
        beans.meters.find(WowMetrics.COMMAND_DURATION.name)
            .tags(WowMetrics.CONTEXT, BANK, WowMetrics.OUTCOME, "committed").timer()!!
            .count().assert().isEqualTo(1L)
        host.context.getBean(Observer::class.java).assert().isInstanceOf(OtelObserver::class.java)
        host.context.getBeansOfType(QueryObserver::class.java).values.filterIsInstance<OtelQueryObserver>()
            .assert().hasSize(1)
        host.context.getBeansOfType(ParkedNotifier::class.java).values.filterIsInstance<ParkedMetrics>()
            .assert().hasSize(1)
        host.context.getBean(NodeMetrics::class.java).assert().isNotNull()
        // The default store's query bulkhead (decision D252): its limit, write pressure and decreases.
        val store = Tags.of(WowMetrics.STORE, ContextBackends.DEFAULT_STORE)
        beans.meters.find(WowMetrics.QUERY_LIMIT_CURRENT.name).tags(store).gauge()!!.value()
            .assert().isEqualTo(StoreQueryBounds.DEFAULT_MAX_CONCURRENT.toDouble())
        beans.meters.find(WowMetrics.QUERY_PRESSURE.name).tags(store).gauge()!!.value().assert().isZero()
        beans.meters.find(
            WowMetrics.QUERY_LIMIT_DECREASES.name
        ).tags(store).functionCounter()!!.count().assert().isZero()
    }

    @Test
    fun `without an OpenTelemetry or a registry of the application's, the global ones should serve`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration::class.java))
            .withPropertyValues("wow.observability.sample-interval=2s")
            .withBean("producer", TopicProducer::class.java, { RecordingProducer() })
            .run { context ->
                context.getBean(Observer::class.java).assert().isInstanceOf(OtelObserver::class.java)
                // No node, nothing to sample.
                context.getBeansOfType(NodeMetrics::class.java).assert().isEmpty()
                context.getBean(ObservabilityProperties::class.java).sampleInterval.assert()
                    .isEqualTo(Duration.ofSeconds(2))
                // The application's producer is wrapped once, so each produce is a span.
                val producer = context.getBean(TopicProducer::class.java)
                producer.assert().isInstanceOf(TracingTopicProducer::class.java)
                (producer as TracingTopicProducer).delegate.assert().isInstanceOf(RecordingProducer::class.java)
            }
    }

    @Test
    fun `a tracing producer of the application's should not be wrapped again, its observer told beside Otel's`() {
        val tracing = TracingTopicProducer(RecordingProducer(), OpenTelemetry.noop())
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration::class.java))
            .withBean("producer", TopicProducer::class.java, { tracing })
            .withBean("observer", Observer::class.java, { Observer.NONE })
            .run { context ->
                context.getBean(TopicProducer::class.java).assert().isSameAs(tracing)
                // Observers compose: the application's stays, and OpenTelemetry's watches beside it.
                context.getBeansOfType(
                    Observer::class.java
                ).keys.assert().containsExactlyInAnyOrder("observer", "wowObserver")
                context.getBean("observer").assert().isSameAs(Observer.NONE)
            }
        // An in-memory transport is its source and its admin too: a wrapper would hide them.
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration::class.java))
            .withBean("topics", InMemoryTopics::class.java, { InMemoryTopics() })
            .run { context -> context.getBean("topics").assert().isInstanceOf(InMemoryTopics::class.java) }
    }

    @Test
    fun `switched off, nothing should be wired`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration::class.java))
            .withPropertyValues("wow.observability.enabled=false")
            .run { context -> context.getBeansOfType(Observer::class.java).assert().isEmpty() }
    }

    private class RecordingProducer : TopicProducer {
        override suspend fun send(topic: String, messages: List<TopicMessage>) = Unit
    }

    /** An OpenTelemetry SDK exporting to memory, and a simple meter registry: the application's. */
    @Configuration(proxyBeanMethods = false)
    class TelemetryBeans {
        val spans: InMemorySpanExporter = InMemorySpanExporter.create()
        val meters = SimpleMeterRegistry()

        @Bean
        fun openTelemetry(): OpenTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build()

        @Bean
        fun meterRegistry(): MeterRegistry = meters
    }
}

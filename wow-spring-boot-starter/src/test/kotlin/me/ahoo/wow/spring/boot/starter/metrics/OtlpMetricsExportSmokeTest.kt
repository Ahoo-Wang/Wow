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

package me.ahoo.wow.spring.boot.starter.metrics

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.registry.otlp.OtlpMeterRegistry
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.metrics.v1.Metric
import me.ahoo.test.asserts.assert
import me.ahoo.wow.metrics.MetricDescriptor
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.spring.boot.starter.enableWowProperties
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono
import java.io.Closeable
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration as BootMetricsAutoConfiguration

class OtlpMetricsExportSmokeTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                BootMetricsAutoConfiguration::class.java,
                OtlpMetricsExportAutoConfiguration::class.java,
            ),
        )
        .enableWowProperties()

    @Test
    fun `should export Wow metrics through the application OTLP registry`() {
        OtlpHttpReceiver().use { receiver ->
            contextRunner
                .withPropertyValues(
                    "management.metrics.use-global-registry=false",
                    "management.otlp.metrics.export.url=${receiver.endpoint}",
                    "management.otlp.metrics.export.step=1h",
                )
                .withUserConfiguration(MetricsAutoConfiguration::class.java)
                .run { context ->
                    context.assert()
                        .hasSingleBean(OtlpMeterRegistry::class.java)
                        .hasSingleBean(WowMetrics::class.java)

                    val metrics = context.getBean(WowMetrics::class.java)
                    metrics.enabled.assert().isTrue()
                    metrics.operation(
                        Mono.just("ok"),
                        MetricDescriptor(component = "test", operation = "otlp"),
                    ).block()

                    publish(context.getBean(OtlpMeterRegistry::class.java))

                    val capturedRequest = receiver.awaitRequest(Duration.ofSeconds(5))
                    capturedRequest.method.assert().isEqualTo("POST")
                    capturedRequest.path.assert().isEqualTo("/v1/metrics")
                    capturedRequest.body.size.assert().isGreaterThan(0)

                    val exportRequest = ExportMetricsServiceRequest.parseFrom(capturedRequest.body)
                    val operationMetric = exportRequest.metrics()
                        .single { it.name == "wow.operation" }
                    operationMetric.hasHistogram().assert().isTrue()
                    operationMetric.histogram.dataPointsCount.assert().isGreaterThanOrEqualTo(1)

                    val attributes = operationMetric.histogram.dataPointsList
                        .flatMap { it.attributesList }
                        .associate { it.key to it.value.stringValue }
                    attributes["component"].assert().isEqualTo("test")
                    attributes["operation"].assert().isEqualTo("otlp")
                    attributes["outcome"].assert().isEqualTo("success")
                    attributes["exception"].assert().isEqualTo("none")
                }
        }
    }

    private fun publish(registry: OtlpMeterRegistry) {
        PUBLISH_METHOD.invoke(registry)
    }

    private companion object {
        val PUBLISH_METHOD = OtlpMeterRegistry::class.java.getDeclaredMethod("publish").apply {
            check(trySetAccessible()) { "Could not access OtlpMeterRegistry.publish()." }
        }
    }
}

private data class CapturedRequest(
    val method: String,
    val path: String,
    val body: ByteArray,
)

private class OtlpHttpReceiver : Closeable {
    private val requests = LinkedBlockingQueue<CapturedRequest>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/metrics", ::capture)
        start()
    }

    val endpoint: String = "http://127.0.0.1:${server.address.port}/v1/metrics"

    fun awaitRequest(timeout: Duration): CapturedRequest = requireNotNull(
        requests.poll(timeout.toMillis(), TimeUnit.MILLISECONDS),
    ) {
        "Timed out waiting for an OTLP metrics request."
    }

    override fun close() {
        server.stop(0)
    }

    private fun capture(exchange: HttpExchange) {
        try {
            requests.offer(
                CapturedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    body = exchange.requestBody.use { it.readAllBytes() },
                ),
            )
            exchange.sendResponseHeaders(200, -1)
        } finally {
            exchange.close()
        }
    }
}

private fun ExportMetricsServiceRequest.metrics(): List<Metric> = resourceMetricsList.flatMap { resourceMetrics ->
    resourceMetrics.scopeMetricsList.flatMap { it.metricsList }
}

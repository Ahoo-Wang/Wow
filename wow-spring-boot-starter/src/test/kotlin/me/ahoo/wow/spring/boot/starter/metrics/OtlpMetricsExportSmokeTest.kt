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
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration
import reactor.core.publisher.Mono
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration as BootMetricsAutoConfiguration

class OtlpMetricsExportSmokeTest {
    @Test
    fun `should export Wow metrics through the documented OTEL environment path`() {
        OtlpHttpReceiver().use { receiver ->
            runExportProcess(receiver.baseEndpoint)

            val capturedRequest = receiver.awaitRequest(Duration.ofSeconds(5))
            capturedRequest.method.assert().isEqualTo("POST")
            capturedRequest.path.assert().isEqualTo("/v1/metrics")
            capturedRequest.body.size.assert().isGreaterThan(0)

            val exportRequest = ExportMetricsServiceRequest.parseFrom(capturedRequest.body)
            exportRequest.resourceAttributes()["service.name"].assert().isEqualTo(SERVICE_NAME)

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

    private fun runExportProcess(endpoint: String) {
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            testRuntimeClasspath(),
            OtlpMetricsExportApplication::class.java.name,
        ).apply {
            environment()["OTEL_EXPORTER_OTLP_ENDPOINT"] = endpoint
            environment()["OTEL_SERVICE_NAME"] = SERVICE_NAME
            inheritIO()
        }.start()
        try {
            process.waitFor(30, TimeUnit.SECONDS).assert().isTrue()
            process.exitValue().assert().isZero()
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private fun testRuntimeClasspath(): String = System.getProperty("java.class.path")
        .also { check(it.isNotBlank()) { "Could not determine the test runtime classpath." } }

    private companion object {
        const val SERVICE_NAME = "wow-otlp-smoke-test"
    }
}

@SpringBootConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration(
    BootMetricsAutoConfiguration::class,
    OtlpMetricsExportAutoConfiguration::class,
    MetricsAutoConfiguration::class,
)
private class OtlpMetricsExportConfiguration

internal object OtlpMetricsExportApplication {
    @JvmStatic
    fun main(args: Array<String>) {
        SpringApplication(OtlpMetricsExportConfiguration::class.java).apply {
            setWebApplicationType(WebApplicationType.NONE)
            setDefaultProperties(
                mapOf(
                    "management.metrics.use-global-registry" to "false",
                    "management.otlp.metrics.export.step" to "1h",
                    "spring.main.banner-mode" to "off",
                ),
            )
        }.run(*args).use { context ->
            val metrics = context.getBean(WowMetrics::class.java)
            check(metrics.enabled) { "Wow metrics are not enabled." }
            metrics.operation(
                Mono.just("ok"),
                MetricDescriptor(component = "test", operation = "otlp"),
            ).block()

            PUBLISH_METHOD.invoke(context.getBean(OtlpMeterRegistry::class.java))
        }
    }

    private val PUBLISH_METHOD = OtlpMeterRegistry::class.java.getDeclaredMethod("publish").apply {
        check(trySetAccessible()) { "Could not access OtlpMeterRegistry.publish()." }
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

    val baseEndpoint: String = "http://127.0.0.1:${server.address.port}"

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

private fun ExportMetricsServiceRequest.resourceAttributes(): Map<String, String> = resourceMetricsList
    .flatMap { it.resource.attributesList }
    .associate { it.key to it.value.stringValue }

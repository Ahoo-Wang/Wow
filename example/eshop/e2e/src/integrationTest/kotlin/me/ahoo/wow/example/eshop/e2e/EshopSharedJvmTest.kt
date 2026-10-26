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

package me.ahoo.wow.example.eshop.e2e

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.eshop.basket.server.BasketServer
import me.ahoo.wow.example.eshop.catalog.server.CatalogServer
import me.ahoo.wow.example.eshop.inventory.server.InventoryServer
import me.ahoo.wow.example.eshop.ordering.server.OrderingServer
import me.ahoo.wow.example.eshop.payment.server.PaymentServer
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.node.SourceTopics
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * eShop services in this one JVM, on one class path that holds every server (decision D169, DX gaps G4 and G21): each
 * selects its own context with `wow.contexts.include`, so each serves that context alone, and none takes another
 * server's copy of its own Manifest for a peer. The basket's and the ordering's sagas send other contexts' commands,
 * so they get their command transport in memory ([InMemoryTopicsBeans]).
 */
class EshopSharedJvmTest {
    @Test
    fun `two services on one class path should each serve the context they include`() {
        start(CatalogServer::class.java, "catalog").use { catalog ->
            // The checkout saga calls the ordering (decision D259): the node checks it has its address, which no
            // checkout here uses.
            val ordering = "--wow.peers.eshop-ordering.url=http://127.0.0.1:$UNUSED_PORT"
            start(BasketServer::class.java, "basket", InMemoryTopicsBeans::class.java, arguments = listOf(ordering)).use { basket ->
                catalog.contexts().assert().containsExactly("eshop-catalog")
                basket.contexts().assert().containsExactly("eshop-basket")

                val product = catalog.post("/eshop-catalog/product/create-product", """{"name":"Wow mug","price":8.50}""")
                product.status.assert().describedAs(product.body).isEqualTo(OK)
                // The basket does not serve the catalog's routes.
                basket.post("/eshop-catalog/product/create-product", """{"name":"Wow mug","price":8.50}""")
                    .status.assert().isEqualTo(NOT_FOUND)

                val basketItem = basket.post(
                    "/eshop-basket/basket/alice/add-basket-item",
                    """{"productId":"${json.readTree(product.body)["ref"]["id"].asString()}","unitPrice":8.50,"quantity":2}""",
                )
                basketItem.status.assert().describedAs(basketItem.body).isEqualTo(OK)
            }
        }
    }

    /**
     * DX gap G21: the ordering server carries the inventory's Manifest as its peer, and the basket and payment servers
     * each carry the ordering's, so on one class path the inventory and ordering nodes find other servers' copies of
     * their own Manifests. They are no peers of the contexts the nodes serve.
     */
    @Test
    fun `services that are each other's peers should start on one class path`() {
        start(InventoryServer::class.java, "inventory").use { inventory ->
            start(
                OrderingServer::class.java,
                "ordering",
                InMemoryTopicsBeans::class.java,
                arguments = listOf("--eshop.ordering.payment-grace-period=30m"),
            ).use { ordering ->
                start(
                    PaymentServer::class.java,
                    "payment",
                    InMemoryTopicsBeans::class.java,
                    arguments = listOf("--eshop.payment.gateway.limit=100.00"),
                ).use { payment ->
                    inventory.contexts().assert().containsExactly("eshop-inventory")
                    ordering.contexts().assert().containsExactly("eshop-ordering")
                    payment.contexts().assert().containsExactly("eshop-payment")
                    val received = inventory.post("/eshop-inventory/stock/mug/receive-stock", """{"quantity":10}""")
                    received.status.assert().describedAs(received.body).isEqualTo(OK)
                }
            }
        }
    }

    /**
     * A request's trace (decision D174): with wow-opentelemetry on the class path, the catalog's admission of a
     * request is the child of the span its `traceparent` names, and its execution and the snapshot subscription's
     * handling of the commit go on in the same trace.
     */
    @Test
    fun `a request's trace should go on through the service that serves it`() {
        start(CatalogServer::class.java, "catalog", TracingBeans::class.java).use { catalog ->
            val spans = catalog.bean(InMemorySpanExporter::class.java)
            val trace = "4bf92f3577b34da6a3ce929d0e0e4736"
            val caller = "00f067aa0ba902b7"
            val product = catalog.post(
                "/eshop-catalog/product/create-product",
                """{"name":"Wow mug","price":8.50}""",
                mapOf("traceparent" to "00-$trace-$caller-01"),
            )
            product.status.assert().describedAs(product.body).isEqualTo(OK)
            val deadline = System.nanoTime() + TIMEOUT.toNanos()
            while (spans.finishedSpanItems.none { it.name == "handle snapshot" } && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
            val traced = spans.finishedSpanItems.filter { it.traceId == trace }
            val admit = traced.single { it.name == "admit eshop-catalog.product" }
            admit.parentSpanId.assert().isEqualTo(caller)
            traced.single { it.name == "execute eshop-catalog.product" }.parentSpanId.assert().isEqualTo(admit.spanId)
            traced.map { it.name }.assert().contains("append eshop-catalog.product", "handle snapshot")
        }
    }

    /** One service in this JVM, its ports on the loopback interface. */
    private class InJvmService(private val context: ConfigurableApplicationContext) : AutoCloseable {
        private val port = context.environment.getRequiredProperty("local.server.port", Int::class.java)
        private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

        fun contexts(): List<String> {
            val manifest = send(HttpRequest.newBuilder(uri("/wow/manifest")).GET())
            check(manifest.status == OK) { "GET /wow/manifest answered ${manifest.status}: ${manifest.body}" }
            return json.readTree(manifest.body)["contexts"].values().map { it["name"].asString() }
        }

        fun post(path: String, body: String, headers: Map<String, String> = emptyMap()): Answer = send(
            HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )

        fun <T : Any> bean(type: Class<T>): T = context.getBean(type)

        private fun uri(path: String): URI = URI.create("http://127.0.0.1:$port$path")

        private fun send(request: HttpRequest.Builder): Answer {
            val response = http.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString())
            return Answer(response.statusCode(), response.body())
        }

        override fun close() {
            try {
                context.close()
            } finally {
                http.shutdownNow()
            }
        }
    }

    private companion object {
        /** A port no ordering listens on: the basket's checkout call has an address, which no test here calls. */
        const val UNUSED_PORT = 9
        const val OK = 200
        const val NOT_FOUND = 404
        val TIMEOUT: Duration = Duration.ofSeconds(30)

        /**
         * Starts [application] as the eShop service of [context], serving the context `eshop-<context>` alone, with the
         * further configuration [sources] and command-line [arguments]. The services share one class path, so Spring
         * Boot reads one `application.yaml` for all of them: each gets what it needs as arguments.
         */
        fun start(
            application: Class<*>,
            context: String,
            vararg sources: Class<*>,
            arguments: List<String> = emptyList(),
        ): InJvmService = InJvmService(
            SpringApplicationBuilder(application, *sources).run(
                *arguments.toTypedArray(),
                "--spring.main.banner-mode=off",
                "--spring.application.name=eshop-$context-service",
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--wow.service=eshop-$context-service",
                "--wow.backend=memory",
                "--wow.internal.host=127.0.0.1",
                "--wow.internal.port=0",
                "--wow.contexts.include=eshop-$context",
                // The services share one class path, so one application.yaml: each is given its own secrets.
            ),
        )
    }
}

/** The application's OpenTelemetry, exporting its spans to memory and propagating the W3C trace context. */
@Configuration(proxyBeanMethods = false)
class TracingBeans {
    @Bean
    fun spanExporter(): InMemorySpanExporter = InMemorySpanExporter.create()

    @Bean
    fun openTelemetry(spans: InMemorySpanExporter): OpenTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()
}

/** A command transport in memory in place of Kafka, as the guide gives it to a test. */
@Configuration(proxyBeanMethods = false)
class InMemoryTopicsBeans {
    @Bean
    fun inMemoryTopics(): InMemoryTopics = InMemoryTopics(1)

    @Bean
    fun sourceTopics(topics: InMemoryTopics): SourceTopics = SourceTopics(topics, producer = topics)
}

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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.eshop.basket.server.BasketServer
import me.ahoo.wow.example.eshop.catalog.server.CatalogServer
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The catalog and basket services in this one JVM, on one class path that holds both domain modules (decision D169,
 * DX gap G4): each selects its own context with `wow.contexts.include`, so each serves that context alone.
 */
class EshopSharedJvmTest {
    @Test
    fun `two services on one class path should each serve the context they include`() {
        start(CatalogServer::class.java, "catalog").use { catalog ->
            start(BasketServer::class.java, "basket").use { basket ->
                catalog.contexts().assert().containsExactly("eshop-catalog")
                basket.contexts().assert().containsExactly("eshop-basket")

                val product = catalog.post("/eshop-catalog/product/create-product", """{"name":"Wow mug","price":8.50}""")
                product.status.assert().describedAs(product.body).isEqualTo(OK)
                // The basket does not serve the catalog's routes.
                basket.post("/eshop-catalog/product/create-product", """{"name":"Wow mug","price":8.50}""")
                    .status.assert().isEqualTo(NOT_FOUND)

                val basketItem = basket.post(
                    "/eshop-basket/basket/alice/add-basket-item",
                    """{"productId":"${json.readTree(product.body)["ref"]["id"].asString()}","quantity":2}""",
                )
                basketItem.status.assert().describedAs(basketItem.body).isEqualTo(OK)
            }
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

        fun post(path: String, body: String): Answer = send(
            HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)),
        )

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
        const val OK = 200
        const val NOT_FOUND = 404
        val TIMEOUT: Duration = Duration.ofSeconds(30)

        /** Starts [application] as the eShop service of [context], serving the context `eshop-<context>` alone. */
        fun start(application: Class<*>, context: String): InJvmService = InJvmService(
            SpringApplicationBuilder(application).run(
                "--spring.main.banner-mode=off",
                "--spring.application.name=eshop-$context-service",
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--wow.service=eshop-$context-service",
                "--wow.backend=memory",
                "--wow.internal.host=127.0.0.1",
                "--wow.internal.port=0",
                "--wow.contexts.include=eshop-$context",
            ),
        )
    }
}

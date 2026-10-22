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

package me.ahoo.wow.example.eshop.inventory.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.ServiceNode
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The inventory service as its `application.yaml` configures it, on random ports. */
class InventoryServerTest {
    @Test
    fun `the server should run one node of the inventory context on the memory backend`() {
        SpringApplicationBuilder(InventoryServer::class.java).run(
            "--spring.main.banner-mode=off",
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--wow.internal.port=0",
            "--wow.internal.host=127.0.0.1",
        ).use { context ->
            val node = context.getBean(ServiceNode::class.java)
            node.service.assert().isEqualTo("eshop-inventory-service")
            node.isReady.assert().isTrue()
            node.hostedContexts.assert().containsExactly("eshop-inventory")
            val manifest = context.getBean(Manifest::class.java)
            manifest.contexts.single().aggregates.map { it.name }.assert().containsExactly("stock")
        }
    }

    @Test
    fun `a quantity that is not positive should be invalid before the stock decides`() {
        SpringApplicationBuilder(InventoryServer::class.java).run(
            "--spring.main.banner-mode=off",
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--wow.internal.port=0",
            "--wow.internal.host=127.0.0.1",
        ).use { context ->
            val port = context.environment.getRequiredProperty("local.server.port", Int::class.java)
            HttpClient.newHttpClient().use { http ->
                fun post(path: String, body: String): HttpResponse<String> = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                val invalid = listOf(
                    "/eshop-inventory/stock/mug/receive-stock" to """{"quantity":0}""",
                    "/eshop-inventory/stock/mug/reserve-stock" to """{"orderId":"o-1","quantity":-1}""",
                )
                for ((path, body) in invalid) {
                    val response = post(path, body)
                    response.statusCode().assert().describedAs(response.body()).isEqualTo(BAD_REQUEST)
                    val problem = JsonMapper.builder().build().readTree(response.body())
                    problem["code"].asString().assert().isEqualTo(FrameworkErrors.COMMAND_INVALID)
                    problem["violations"].single().let {
                        it["path"].asString().assert().isEqualTo("quantity")
                        it["code"].asString().assert().isEqualTo("Positive")
                    }
                }
            }
        }
    }

    private companion object {
        const val BAD_REQUEST = 400
    }
}

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
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver
import me.ahoo.wow.test.server.wowServer
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The inventory service as its `application.yaml` configures it, on random ports. */
class InventoryServerTest {
    @Test
    fun `the server should run one node of the inventory context on the memory backend`() {
        wowServer<InventoryServer>("dev").use { server ->
            val node = server.bean<ServiceNode>()
            node.service.assert().isEqualTo("eshop-inventory-service")
            node.isReady.assert().isTrue()
            node.hostedContexts.assert().containsExactly("eshop-inventory")
            val manifest = server.bean<Manifest>()
            manifest.contexts.single().aggregates.map { it.name }.assert().containsExactly("stock")
        }
    }

    @Test
    fun `the server should list its routes in springdoc's document and serve Swagger UI`() {
        wowServer<InventoryServer>("dev").use { server ->
            val docs = server.http.get("/v3/api-docs")
            docs.status.assert().describedAs(docs.text).isEqualTo(200)
            // The service's Wow routes, and the development headers Swagger UI's Authorize dialog sets.
            docs.text.assert().contains("\"/eshop-inventory/", "\"${DevelopmentCallerResolver.USER}\"")
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().use { browser ->
                browser.send(
                    HttpRequest.newBuilder(server.http.base.resolve("/swagger-ui.html")).GET().build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode().assert().isEqualTo(200)
            }
        }
    }

    @Test
    fun `a quantity that is not positive should be invalid before the stock decides`() {
        wowServer<InventoryServer>("dev").use { server ->
            val invalid = listOf(
                "/eshop-inventory/stock/mug/receive-stock" to """{"quantity":0}""",
                "/eshop-inventory/stock/mug/reserve-stock" to """{"orderId":"o-1","quantity":-1}""",
            )
            for ((path, body) in invalid) {
                val answer = server.http.post(path, body)
                answer.status.assert().describedAs(answer.text).isEqualTo(BAD_REQUEST)
                val problem = answer.body!!
                problem["code"].asString().assert().isEqualTo(FrameworkErrors.COMMAND_INVALID)
                problem["violations"].single().let {
                    it["path"].asString().assert().isEqualTo("quantity")
                    it["code"].asString().assert().isEqualTo("positive")
                }
            }
        }
    }

    private companion object {
        const val BAD_REQUEST = 400
    }
}

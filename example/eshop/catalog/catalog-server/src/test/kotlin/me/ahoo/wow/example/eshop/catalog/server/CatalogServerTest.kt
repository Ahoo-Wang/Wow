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

package me.ahoo.wow.example.eshop.catalog.server

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.CommandRequest
import me.ahoo.wow.runtime.node.ServiceNode
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

/** The catalog service as its `application.yaml` configures it, on random ports. */
class CatalogServerTest {
    private fun start(): ConfigurableApplicationContext = SpringApplicationBuilder(CatalogServer::class.java).run(
        "--spring.main.banner-mode=off",
        "--server.port=0",
        "--server.address=127.0.0.1",
        "--wow.internal.port=0",
        "--wow.internal.host=127.0.0.1",
    )

    @Test
    fun `the server should run one node of the catalog context on the memory backend`() {
        start().use { context ->
            val node = context.getBean(ServiceNode::class.java)
            node.service.assert().isEqualTo("eshop-catalog-service")
            node.isReady.assert().isTrue()
            node.hostedContexts.assert().containsExactly("eshop-catalog")
            val manifest = context.getBean(Manifest::class.java)
            manifest.contexts.single().aggregates.map { it.name }.assert()
                .containsExactlyInAnyOrder("product", "brand", "category")
        }
    }

    /** The application sends a command in process through the node's gateway (decision D92), as a user module does. */
    @Test
    fun `the application should send a command through the command gateway`() {
        start().use { context ->
            val gateway = context.getBean(CommandGateway::class.java)
            val request = CommandRequest(
                context = "eshop-catalog",
                aggregate = "brand",
                command = "CreateBrand",
                body = CommandBody.of("""{"name":"In-process brand"}"""),
            )
            val result = runBlocking { gateway.execute(request) }
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.ref.id.assert().isNotBlank()
            result.version.assert().isEqualTo(1)
        }
    }
}

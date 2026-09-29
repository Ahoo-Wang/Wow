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

package me.ahoo.wow.viewstore.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.viewstore.ViewStoreService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode

/**
 * The server boots with its own configuration, on in-memory stores so the test needs no MongoDB, and serves the
 * view store under `/view-store`.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "wow.eventsourcing.store.storage=in_memory",
        "wow.eventsourcing.snapshot.storage=in_memory",
        "wow.mongo.enabled=false",
        "wow.view-store.system-views[0].definition-id=orders",
        "wow.view-store.system-views[0].id=orders-open",
        "wow.view-store.system-views[0].title=Open orders",
        "wow.view-store.system-views[0].config={\"kind\":\"record\"}",
    ],
)
class ViewStoreServerBootTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    private val client: WebTestClient by lazy {
        val port = applicationContext.environment.getRequiredProperty("local.server.port")
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:$port")
            .codecs { it.defaultCodecs().maxInMemorySize(OPENAPI_BUFFER_BYTES) }
            .build()
    }

    @Test
    fun `serves the generated and the custom routes in its OpenAPI`() {
        val paths = client.get().uri("/v3/api-docs").exchange()
            .expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!
            .get("paths").propertyNames().toList()
        val scope = "/view-store/tenant/{tenantId}/owner/{ownerId}"
        paths.assert().contains(
            "$scope/view",
            "$scope/view/{id}/save",
            "$scope/view/{id}/rename",
            "$scope/view/{id}/audience",
            "$scope/view/{id}",
            "$scope/view/snapshot/list",
            "$scope/view/snapshot/single",
            "$scope/system-views",
            "$scope/definitions/{definitionId}/preferences",
            "$scope/view/requests/{requestId}",
        )
    }

    @Test
    fun `creates a view and reads preferences never written as version 0`() {
        client.post().uri("/view-store/tenant/t1/owner/alice/view")
            .header(ViewStoreService.APP_ID_HEADER, "console")
            .header(CommandComponent.Header.WAIT_STAGE, "SNAPSHOT")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"definitionId":"orders","title":"Mine","config":{"kind":"record"}}""")
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.errorCode").isEqualTo("Ok")

        client.get().uri("/view-store/tenant/t1/owner/alice/definitions/orders/preferences")
            .header(ViewStoreService.APP_ID_HEADER, "console")
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.version").isEqualTo(0)
    }

    @Test
    fun `serves the configured system views under the shared owner`() {
        client.get().uri("/view-store/tenant/t1/owner/(shared)/system-views?definitionId=orders")
            .header(ViewStoreService.APP_ID_HEADER, "console")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].id").isEqualTo("orders-open")
            .jsonPath("$[0].scope").isEqualTo("system")
    }
}

private const val OPENAPI_BUFFER_BYTES = 16 * 1024 * 1024

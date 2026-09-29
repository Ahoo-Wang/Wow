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
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.domain.view.SharedBoardReferences
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.Base64

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
    /** The in-memory snapshot store has no query schema, so the shared-board check finds no board here. */
    @TestConfiguration
    class NoSharedBoards {
        @Bean
        fun sharedBoardReferences(): SharedBoardReferences = SharedBoardReferences { _, _, _ -> Flux.empty() }
    }

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

    private fun createShared(): String =
        client.post().uri("/view-store/tenant/t1/owner/(shared)/view")
            .header(ViewStoreService.APP_ID_HEADER, "console")
            .header(CommandComponent.Header.WAIT_STAGE, "SNAPSHOT")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"definitionId":"orders","title":"Team","config":{"kind":"record"}}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!
            .get("aggregateId").stringValue()

    private fun makePersonal(id: String, authorization: String?): WebTestClient.ResponseSpec {
        val spec = client.put().uri("/view-store/tenant/t1/owner/(shared)/view/$id/audience")
            .header(ViewStoreService.APP_ID_HEADER, "console")
            .header(CommandComponent.Header.WAIT_STAGE, "SNAPSHOT")
            .header(CommandComponent.Header.AGGREGATE_VERSION, "1")
            .contentType(MediaType.APPLICATION_JSON)
        authorization?.let { spec.header(HttpHeaders.AUTHORIZATION, it) }
        return spec.bodyValue("""{"audience":"personal"}""").exchange()
    }

    /** The user is the subject of the token the gateway forwards; without one there is no user to own the view. */
    @Test
    fun `a view made personal belongs to the user of the forwarded token`() {
        makePersonal(createShared(), authorization = null)
            .expectStatus().isUnauthorized
            .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_OPERATOR_REQUIRED)
        val id = createShared()
        makePersonal(id, "Bearer ${token("bob")}")
            .expectStatus().isOk
            .expectBody().jsonPath("$.errorCode").isEqualTo("Ok")
        // The view is bob's now: a write under his owner path is accepted, one under the shared path is not.
        rename("bob", id).expectStatus().isOk
        rename("(shared)", id).expectStatus().isForbidden
    }

    private fun rename(owner: String, id: String): WebTestClient.ResponseSpec =
        client.put().uri("/view-store/tenant/t1/owner/$owner/view/$id/rename")
            .header(ViewStoreService.APP_ID_HEADER, "console")
            .header(CommandComponent.Header.WAIT_STAGE, "SNAPSHOT")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"title":"Bob's"}""")
            .exchange()

    /** A token as the gateway forwards it; the service reads it without verifying the signature again. */
    private fun token(subject: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun encode(json: String) = encoder.encodeToString(json.toByteArray(Charsets.UTF_8))
        val expiresAt = Instant.now().plusSeconds(TOKEN_TTL_SECONDS).epochSecond
        return encode("""{"alg":"HS256","typ":"JWT"}""") + "." +
            encode("""{"jti":"token-$subject","sub":"$subject","exp":$expiresAt}""") + "." +
            encode("signature")
    }
}


private const val OPENAPI_BUFFER_BYTES = 16 * 1024 * 1024
private const val TOKEN_TTL_SECONDS = 600L

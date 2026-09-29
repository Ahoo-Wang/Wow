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

package me.ahoo.wow.viewstore.starter

import com.mongodb.reactivestreams.client.MongoClients
import me.ahoo.test.asserts.assert
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.tck.container.WowTestContainers
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import java.net.URI
import java.util.UUID

/**
 * The view store embedded in a host that matches paths case-insensitively (`setUseCaseSensitiveMatch(false)`), on
 * MongoDB: every rule of the starter applies to every case of a path the host routes to the view store.
 */
@SpringBootTest(
    classes = [ViewStoreHostApplication::class, CaseInsensitivePaths::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.application.name=example-service",
        "wow.command.bus.type=in_memory",
        "wow.event.bus.type=in_memory",
        "wow.eventsourcing.state.bus.type=in_memory",
        "wow.kafka.enabled=false",
        "cosid.machine.enabled=true",
        "cosid.machine.distributor.type=manual",
        "cosid.machine.distributor.manual.machine-id=1",
        "cosid.generator.enabled=true",
        "wow.eventsourcing.store.storage=mongo",
        "wow.eventsourcing.snapshot.storage=mongo",
        "wow.view-store.system-views[0].definition-id=orders",
        "wow.view-store.system-views[0].id=orders-open",
        "wow.view-store.system-views[0].title=Open orders",
        "wow.view-store.system-views[0].config={\"kind\":\"record\"}",
    ],
)
class ViewStoreCaseInsensitiveHostTest {
    companion object {
        private val DATABASE = "view_store_it_" + UUID.randomUUID().toString().replace("-", "").take(12)
        private const val SCOPE = "/view-store/tenant/t1/owner"
        private const val APP = "console"
        private const val OTHER_APP = "portal"

        @JvmStatic
        @DynamicPropertySource
        fun mongo(registry: DynamicPropertyRegistry) {
            registry.add("spring.mongodb.uri") { WowTestContainers.mongo.connectionString + "/" + DATABASE }
            registry.add("spring.mongodb.database") { DATABASE }
        }

        @JvmStatic
        @AfterAll
        fun dropDatabase() {
            MongoClients.create(WowTestContainers.mongo.connectionString).use {
                reactor.core.publisher.Mono.from(it.getDatabase(DATABASE).drop()).block()
            }
        }
    }

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    private val port: String by lazy { applicationContext.environment.getRequiredProperty("local.server.port") }

    private val client: WebTestClient by lazy {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    /** [path] sent exactly as written, `;` parameters and `%xx` escapes included. */
    private fun raw(path: String): URI = URI.create("http://localhost:$port$path")

    private fun write(
        method: String,
        uri: String,
        body: String?,
        appId: String? = APP,
        version: Int? = null,
        requestId: String = UUID.randomUUID().toString(),
    ): WebTestClient.ResponseSpec = write(method, raw(uri), body, appId, version, requestId)

    private fun write(
        method: String,
        uri: URI,
        body: String?,
        appId: String? = APP,
        version: Int? = null,
        requestId: String = UUID.randomUUID().toString(),
    ): WebTestClient.ResponseSpec {
        val spec = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(uri)
            .header(CommandComponent.Header.WAIT_STAGE, "SNAPSHOT")
            .header(CommandComponent.Header.REQUEST_ID, requestId)
            .contentType(MediaType.APPLICATION_JSON)
        appId?.let { spec.header(ViewStoreService.APP_ID_HEADER, it) }
        version?.let { spec.header(CommandComponent.Header.AGGREGATE_VERSION, it.toString()) }
        return (body?.let { spec.bodyValue(it) } ?: spec).exchange()
    }

    private fun create(owner: String, config: String = """{"kind":"record"}""", requestId: String = UUID.randomUUID().toString()): String {
        val result = write(
            "POST",
            "$SCOPE/$owner/view",
            """{"definitionId":"orders","title":"View","config":$config}""",
            requestId = requestId,
        ).expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!
        return result.get("aggregateId").stringValue()
    }

    private fun query(path: String, body: String, appId: String? = APP): WebTestClient.ResponseSpec {
        val spec = client.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
        appId?.let { spec.header(ViewStoreService.APP_ID_HEADER, it) }
        return spec.bodyValue(body).exchange()
    }

    private fun single(owner: String, id: String, appId: String? = APP): WebTestClient.ResponseSpec =
        query("$SCOPE/$owner/view/snapshot/single", singleQuery { filter { id(id) } }.toJsonString(), appId)


    private fun send(method: String, path: String, headers: Map<String, String> = emptyMap(), body: String? = null): WebTestClient.ResponseSpec {
        val spec = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(raw(path))
            .header(CommandComponent.Header.WAIT_STAGE, "SNAPSHOT")
            .header(CommandComponent.Header.REQUEST_ID, UUID.randomUUID().toString())
            .header(ViewStoreService.APP_ID_HEADER, APP)
            .contentType(MediaType.APPLICATION_JSON)
        headers.forEach { (k, v) -> spec.header(k, v) }
        return (body?.let { spec.bodyValue(it) } ?: spec).exchange()
    }

    @Test
    fun `the host routes in any case, and so do the view store's rules`() {
        val id = create("alice")
        // Spring routes these to the view store's routes on this host: each rule must see them too.
        listOf("/view-store/tenant/t1/OWNER/%20", "/VIEW-STORE/tenant/%E3%80%80/owner/alice").forEach { scope ->
            send("PUT", "$scope/view/$id/rename", mapOf(CommandComponent.Header.OWNER_ID to "alice"), """{"title":"Taken"}""")
                .expectStatus().isBadRequest
                .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED)
        }
        single("alice", id).expectStatus().isOk
            .expectBody().jsonPath("$.version").isEqualTo(1).jsonPath("$.state.title").isEqualTo("View")
        val created = send(
            "POST",
            "/view-store/tenant/t1/OWNER/alice/view",
            mapOf(
                CommandComponent.Header.AGGREGATE_ID to "chosen-id",
                CommandComponent.Header.COMMAND_HEADER_X_PREFIX + ViewStoreService.APP_ID_MESSAGE_HEADER to OTHER_APP,
            ),
            """{"definitionId":"orders","title":"V","config":{"kind":"record"}}""",
        ).expectStatus().isOk.expectBody(JsonNode::class.java).returnResult().responseBody!!
        val createdId = created.get("aggregateId").stringValue()
        createdId.assert().isNotEqualTo("chosen-id")
        single("alice", createdId).expectStatus().isOk.expectBody().jsonPath("$.state.appId").isEqualTo(APP)
        listOf(
            "/view-store/tenant/t1/owner/alice/VIEW/$id/state",
            "/VIEW-STORE/tenant/t1/owner/alice/view/$id/snapshot",
        ).forEach { path ->
            send("GET", path).expectStatus().isNotFound
                .expectBody().jsonPath("$.errorCode").isEqualTo(ErrorCodes.NOT_FOUND)
        }
        send("POST", "/WOW/command/send", mapOf(
            CommandComponent.Header.COMMAND_TYPE to "me.ahoo.wow.viewstore.api.view.RenameView",
            CommandComponent.Header.AGGREGATE_ID to id,
            CommandComponent.Header.OWNER_ID to "alice",
        ), """{"title":"Taken"}""").expectStatus().isNotFound
        send("PUT", "/view-store/tenant/t1/OWNER/(shared)/VIEW/orders-open/rename", mapOf(CommandComponent.Header.AGGREGATE_VERSION to "1"), """{"title":"Mine"}""")
            .expectStatus().isForbidden
            .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY)
        // The case-insensitive routes still work.
        send("PUT", "/VIEW-STORE/tenant/t1/OWNER/alice/VIEW/$id/RENAME", mapOf(CommandComponent.Header.AGGREGATE_VERSION to "1"), """{"title":"Renamed"}""")
            .expectStatus().isOk
        single("alice", id).expectStatus().isOk.expectBody().jsonPath("$.state.title").isEqualTo("Renamed")
    }
}

/** A host that matches paths case-insensitively: Spring then rebuilds Wow's route patterns with that parser. */
@org.springframework.boot.test.context.TestConfiguration
class CaseInsensitivePaths : org.springframework.web.reactive.config.WebFluxConfigurer {
    override fun configurePathMatching(configurer: org.springframework.web.reactive.config.PathMatchConfigurer) {
        configurer.setUseCaseSensitiveMatch(false)
    }
}

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
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.tck.container.WowTestContainers
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.kotlin.core.publisher.toFlux
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * The view store embedded in a host service of another context, on MongoDB: the routes, the rules the aggregates
 * and the query policy keep, the replay, preferences and system views, end to end over HTTP.
 */
@SpringBootTest(
    classes = [ViewStoreHostApplication::class],
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
class ViewStoreMongoTest {
    companion object {
        private val DATABASE = "view_store_it_" + UUID.randomUUID().toString().replace("-", "").take(12)
        private const val SCOPE = "/view-store/tenant/t1/owner"
        private const val APP = "console"
        private const val OTHER_APP = "portal"
        private const val SHARED = "(shared)"

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

    private val client: WebTestClient by lazy {
        val port = applicationContext.environment.getRequiredProperty("local.server.port")
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    private fun write(
        method: String,
        uri: String,
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

    @Test
    fun `a view is read back only in its tenant, owner and application`() {
        val id = create("alice")
        single("alice", id).expectStatus().isOk
            .expectBody()
            .jsonPath("$.state.appId").isEqualTo(APP)
            .jsonPath("$.state.config.kind").isEqualTo("record")
            .jsonPath("$.state.audience").isEqualTo("personal")
            .jsonPath("$.version").isEqualTo(1)
        single("alice", id, OTHER_APP).expectStatus().isNotFound
        single("bob", id).expectStatus().isNotFound
        query("/view-store/tenant/t2/owner/alice/view/snapshot/single", singleQuery { filter { id(id) } }.toJsonString())
            .expectStatus().isNotFound
        single("alice", id, appId = null).expectStatus().isBadRequest
            .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_APP_REQUIRED)
        // Wow's owner-only and tenant-only queries, and every event-stream query, are closed routes.
        query("/view-store/owner/alice/view/snapshot/list", listQuery { }.toJsonString())
            .expectStatus().isNotFound
        query("/view-store/tenant/t1/view/snapshot/list", listQuery { }.toJsonString())
            .expectStatus().isNotFound
        query("/view-store/owner/alice/view/event/list", listQuery { }.toJsonString())
            .expectStatus().isNotFound
        query("$SCOPE/alice/view/event/list", listQuery { }.toJsonString())
            .expectStatus().isNotFound
    }

    @Test
    fun `Wow's state, tracing and maintenance routes are closed for the view store`() {
        val id = create("alice")
        listOf(
            "GET" to "$SCOPE/alice/view/$id/state",
            "GET" to "$SCOPE/alice/view/$id/state/1",
            "GET" to "$SCOPE/alice/view/$id/state/time/${System.currentTimeMillis()}",
            "GET" to "$SCOPE/alice/view/$id/snapshot",
            "GET" to "/view-store/tenant/t1/view/$id/state/tracing",
            "GET" to "/view-store/tenant/t1/view/$id/event/1/9",
            "PUT" to "/view-store/tenant/t1/view/$id/snapshot",
            "PUT" to "/view-store/view/snapshot/0/10",
            "POST" to "/view-store/view/state/0/10",
            "PUT" to "/view-store/tenant/t1/view/$id/1/compensate",
            "GET" to "/view-store/view/snapshot/schema",
        ).forEach { (method, uri) ->
            listOf(APP, OTHER_APP, null).forEach { appId ->
                val spec = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(uri)
                appId?.let { spec.header(ViewStoreService.APP_ID_HEADER, it) }
                spec.exchange().expectStatus().isNotFound
                    .expectBody().jsonPath("$.errorCode").isEqualTo(ErrorCodes.NOT_FOUND)
            }
        }
    }

    private fun facade(commandType: String, body: String, headers: Map<String, String> = emptyMap()): WebTestClient.ResponseSpec {
        val spec = client.post().uri("/wow/command/send")
            .header(CommandComponent.Header.COMMAND_TYPE, commandType)
            .header(CommandComponent.Header.WAIT_STAGE, "SNAPSHOT")
            .header(ViewStoreService.APP_ID_HEADER, APP)
            .contentType(MediaType.APPLICATION_JSON)
        headers.forEach { (name, value) -> spec.header(name, value) }
        return spec.bodyValue(body).exchange()
    }

    @Test
    fun `the command facade does not serve the view store's commands`() {
        val id = create("alice")
        facade(
            "me.ahoo.wow.viewstore.api.view.CreateView",
            """{"definitionId":"orders","title":"Squat","config":{"kind":"record"}}""",
            mapOf(CommandComponent.Header.AGGREGATE_ID to "orders-open", CommandComponent.Header.OWNER_ID to SHARED),
        ).expectStatus().isNotFound
        facade(
            "me.ahoo.wow.viewstore.api.preferences.SetViewPreferences",
            """{"definitionId":"orders","order":[]}""",
            mapOf(CommandComponent.Header.AGGREGATE_ID to "any", CommandComponent.Header.OWNER_ID to "mallory"),
        ).expectStatus().isNotFound
        facade(
            "me.ahoo.wow.viewstore.api.view.RenameView",
            """{"id":"$id","title":"Taken"}""",
            mapOf(CommandComponent.Header.AGGREGATE_ID to id),
        ).expectStatus().isNotFound
        facade(
            "any",
            """{"title":"Taken"}""",
            mapOf(
                CommandComponent.Header.COMMAND_AGGREGATE_CONTEXT to ViewStoreService.SERVICE_NAME,
                CommandComponent.Header.COMMAND_AGGREGATE_NAME to ViewStoreService.VIEW_AGGREGATE_NAME,
                CommandComponent.Header.AGGREGATE_ID to id,
            ),
        ).expectStatus().isNotFound
        single("alice", id).expectStatus().isOk.expectBody().jsonPath("$.version").isEqualTo(1)
    }

    @Test
    fun `the application comes from CoSec-App-Id, never from a command header`() {
        client.post().uri("$SCOPE/alice/view")
            .header(CommandComponent.Header.WAIT_STAGE, "SNAPSHOT")
            .header(CommandComponent.Header.COMMAND_HEADER_X_PREFIX + ViewStoreService.APP_ID_MESSAGE_HEADER, OTHER_APP)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"definitionId":"orders","title":"View","config":{"kind":"record"}}""")
            .exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_APP_REQUIRED)
        val id = create("alice")
        client.put().uri("$SCOPE/alice/view/$id/rename")
            .header(CommandComponent.Header.WAIT_STAGE, "SNAPSHOT")
            .header(CommandComponent.Header.AGGREGATE_VERSION, "1")
            .header(ViewStoreService.APP_ID_HEADER, OTHER_APP)
            .header(CommandComponent.Header.COMMAND_HEADER_X_PREFIX + ViewStoreService.APP_ID_MESSAGE_HEADER, APP)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"title":"Taken"}""")
            .exchange().expectStatus().isNotFound
    }

    @Test
    fun `writes carry the expected version and stay in their application`() {
        val id = create("alice")
        write("PUT", "$SCOPE/alice/view/$id/rename", """{"title":"Renamed"}""", appId = OTHER_APP, version = 1)
            .expectStatus().isNotFound
        write("PUT", "$SCOPE/bob/view/$id/rename", """{"title":"Renamed"}""", version = 1)
            .expectStatus().isForbidden
        write("PUT", "$SCOPE/alice/view/$id/save", """{"config":{"kind":"chart"}}""", version = 1)
            .expectStatus().isBadRequest
            .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
        write("PUT", "$SCOPE/alice/view/$id/rename", """{"title":"Renamed"}""", version = 1)
            .expectStatus().isOk
        write("PUT", "$SCOPE/alice/view/$id/rename", """{"title":"Stale"}""", version = 1)
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody().jsonPath("$.errorCode").isEqualTo(ErrorCodes.COMMAND_EXPECT_VERSION_CONFLICT)
        single("alice", id).expectStatus().isOk
            .expectBody().jsonPath("$.state.title").isEqualTo("Renamed").jsonPath("$.version").isEqualTo(2)
    }

    @Test
    fun `a client cannot pick the id of a new view`() {
        val result = client.post().uri("$SCOPE/alice/view")
            .header(ViewStoreService.APP_ID_HEADER, APP)
            .header(CommandComponent.Header.WAIT_STAGE, "SNAPSHOT")
            .header(CommandComponent.Header.AGGREGATE_ID, "chosen-id")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"definitionId":"orders","title":"View","config":{"kind":"record"}}""")
            .exchange().expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!
        result.get("aggregateId").stringValue().assert().isNotEqualTo("chosen-id")
    }

    private fun share(owner: String, id: String, version: Int? = 1, appId: String = APP): WebTestClient.ResponseSpec =
        write("PUT", "$SCOPE/$owner/view/$id/share", "{}", appId = appId, version = version)

    private fun claim(owner: String, id: String, version: Int? = 1, appId: String = APP): WebTestClient.ResponseSpec =
        write("PUT", "$SCOPE/$owner/view/$id/claim", null, appId = appId, version = version)

    private fun listed(owner: String, id: String): Boolean {
        val body = query("$SCOPE/$owner/view/snapshot/list", listQuery { filter { id(id) } }.toJsonString())
            .expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!
        return body.size() == 1
    }

    @Test
    fun `sharing moves the view to the shared owner and keeps its id`() {
        val id = create("alice")
        share("alice", id).expectStatus().isOk
        single(SHARED, id).expectStatus().isOk
            .expectBody().jsonPath("$.state.audience").isEqualTo("shared").jsonPath("$.ownerId").isEqualTo(SHARED)
        single("alice", id).expectStatus().isNotFound
    }

    /**
     * Claiming is sent to the claiming user's own path. That bob cannot claim through alice's path is the gateway's
     * to enforce (it lets a caller use only `owner/{their sub}`); the server trusts the path.
     */
    @Test
    fun `claiming moves a shared view to the owner of the claiming path`() {
        val id = create(SHARED)
        listed(SHARED, id).assert().isTrue()
        claim("alice", id).expectStatus().isOk
        listed(SHARED, id).assert().isFalse()
        listed("alice", id).assert().isTrue()
        single("alice", id).expectStatus().isOk
            .expectBody()
            .jsonPath("$.state.audience").isEqualTo("personal")
            .jsonPath("$.ownerId").isEqualTo("alice")
            .jsonPath("$.version").isEqualTo(2)
        // It is alice's now: bob claiming it through his own path is refused, as is sharing it from his path.
        claim("bob", id, version = 2).expectStatus().isForbidden
        share("bob", id, version = 2).expectStatus().isForbidden
        // Shared again from alice's path, bob can claim it through his.
        share("alice", id, version = 2).expectStatus().isOk
        claim("bob", id, version = 3).expectStatus().isOk
        listed("bob", id).assert().isTrue()
        listed("alice", id).assert().isFalse()
    }

    @Test
    fun `a claim for a reserved owner or another application is refused`() {
        val id = create(SHARED)
        claim(SHARED, id).expectStatus().isBadRequest
            .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
        claim("(0)", id).expectStatus().isBadRequest
        claim("alice", id, appId = OTHER_APP).expectStatus().isNotFound
        claim("alice", id, version = 3).expectStatus().isEqualTo(HttpStatus.CONFLICT)
        single(SHARED, id).expectStatus().isOk.expectBody().jsonPath("$.version").isEqualTo(1)
    }

    @Test
    fun `an audience the view already has is answered without a change`() {
        val shared = create(SHARED)
        share(SHARED, shared).expectStatus().isOk
            .expectBody()
            .jsonPath("$.errorCode").isEqualTo(ErrorCodes.SUCCEEDED)
            .jsonPath("$.aggregateId").isEqualTo(shared)
            .jsonPath("$.aggregateVersion").isEqualTo(1)
        share(SHARED, shared, version = null).expectStatus().isOk
        single(SHARED, shared).expectStatus().isOk.expectBody().jsonPath("$.version").isEqualTo(1)
        val personal = create("alice")
        claim("alice", personal).expectStatus().isOk
            .expectBody().jsonPath("$.aggregateVersion").isEqualTo(1)
        single("alice", personal).expectStatus().isOk.expectBody().jsonPath("$.version").isEqualTo(1)
        // Anything but a request that would succeed goes to the command and gets its answer.
        share(SHARED, shared, version = 3).expectStatus().isEqualTo(HttpStatus.CONFLICT)
        share(SHARED, shared, appId = OTHER_APP).expectStatus().isNotFound
        share("alice", shared).expectStatus().isForbidden
        claim("bob", personal).expectStatus().isForbidden
    }

    @Test
    fun `a list reads the kind without the rest of the config`() {
        val board = create(SHARED, """{"kind":"dashboard","panels":[{"id":"p1","instanceId":"v1"}],"tabs":[]}""")
        query(
            "$SCOPE/$SHARED/view/snapshot/list",
            listQuery {
                filter { id(board) }
                projection { include("aggregateId", "state.title", "state.config.kind") }
            }.toJsonString(),
        ).expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].state.title").isEqualTo("View")
            .jsonPath("$[0].state.config.kind").isEqualTo("dashboard")
            .jsonPath("$[0].state.config.panels").doesNotExist()
            .jsonPath("$[0].state.config.tabs").doesNotExist()
            .jsonPath("$[0].state.appId").doesNotExist()
        query(
            "$SCOPE/$SHARED/view/snapshot/list",
            listQuery { filter { "state.config.kind" eq "dashboard"; id(board) } }.toJsonString(),
        ).expectStatus().isOk.expectBody().jsonPath("$[0].aggregateId").isEqualTo(board)
    }

    /**
     * Every place a dashboard panel references a saved view (the engine's `DashboardPanel`): the view it shows, the
     * view 「在工作台中打开」 opens, and the view or dashboard a press opens.
     */
    private fun boardReferencing(view: String): List<String> = listOf(
        """{"kind":"dashboard","tabs":[],"panels":[{"id":"p1","kind":"view","instanceId":"$view"}]}""",
        """{"kind":"dashboard","tabs":[],"panels":[{"id":"p1","kind":"view","owned":{"definitionId":"orders","config":{"kind":"analysis"}},"opens":"$view"}]}""",
        """{"kind":"dashboard","tabs":[],"panels":[{"id":"p1","kind":"view","instanceId":"other","click":{"kind":"view","instanceId":"$view"}}]}""",
        """{"kind":"dashboard","tabs":[],"panels":[{"id":"p0","kind":"heading","content":"x"},{"id":"p1","kind":"view","instanceId":"other","click":{"kind":"dashboard","instanceId":"$view","values":{}}}]}""",
    )

    @Test
    fun `a view a shared dashboard references stays shared`() {
        val boardCount = boardReferencing("x").size
        (0 until boardCount).forEach { index ->
            val view = create(SHARED)
            val board = create(SHARED, boardReferencing(view)[index])
            claim("bob", view)
                .expectStatus().isBadRequest
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
                .jsonPath("$.bindingErrors[0].name").isEqualTo(board)
            // A record view of the same shape is no board, so it does not count.
            val elsewhere = create(SHARED)
            create(SHARED, boardReferencing(elsewhere)[index].replace("\"dashboard\",\"tabs\"", "\"record\",\"tabs\""))
            claim("bob", elsewhere).expectStatus().isOk
        }
        val view = create(SHARED)
        val board = create(SHARED, boardReferencing(view)[0])
        claim("bob", board).expectStatus().isOk
        single("bob", board).expectStatus().isOk
        // The board went personal, so it no longer keeps the view shared.
        claim("bob", view).expectStatus().isOk
        // A board of another application does not keep it shared either.
        val other = create(SHARED)
        write("POST", "$SCOPE/$SHARED/view", """{"definitionId":"orders","title":"B","config":${boardReferencing(other)[0]}}""", appId = OTHER_APP)
            .expectStatus().isOk
        claim("bob", other).expectStatus().isOk
    }

    @Test
    fun `a write is replayed by its request id within its tenant, owner and application`() {
        val createRequest = UUID.randomUUID().toString()
        val id = create("alice", requestId = createRequest)
        val renameRequest = UUID.randomUUID().toString()
        write("PUT", "$SCOPE/alice/view/$id/rename", """{"title":"Second"}""", version = 1, requestId = renameRequest)
            .expectStatus().isOk
        write("PUT", "$SCOPE/alice/view/$id/rename", """{"title":"Second"}""", version = 2, requestId = renameRequest)
            .expectBody().jsonPath("$.errorCode").isEqualTo(ErrorCodes.DUPLICATE_REQUEST_ID)
        val deleteRequest = UUID.randomUUID().toString()
        write("DELETE", "$SCOPE/alice/view/$id", "{}", version = 2, requestId = deleteRequest).expectStatus().isOk

        replay("alice", createRequest).expectStatus().isOk
            .expectBody().jsonPath("$.version").isEqualTo(1).jsonPath("$.state.title").isEqualTo("View")
        replay("alice", renameRequest).expectStatus().isOk
            .expectBody().jsonPath("$.version").isEqualTo(2).jsonPath("$.state.title").isEqualTo("Second")
        replay("alice", deleteRequest).expectStatus().isNoContent
        replay("alice", deleteRequest, OTHER_APP).expectStatus().isNotFound
        replay("alice", createRequest, OTHER_APP).expectStatus().isNotFound
        replay("bob", createRequest).expectStatus().isNotFound
        replay("alice", "unknown").expectStatus().isNotFound
    }

    private fun replay(owner: String, requestId: String, appId: String = APP): WebTestClient.ResponseSpec =
        client.get().uri("$SCOPE/$owner/view/requests/$requestId")
            .header(ViewStoreService.APP_ID_HEADER, appId)
            .exchange()

    @Test
    fun `preferences start at version 0 and are the owner's own`() {
        val uri = "$SCOPE/alice/definitions/prefs-orders/preferences"
        getPreferences(uri).expectBody().jsonPath("$.version").isEqualTo(0).jsonPath("$.order").isEmpty
        write("PUT", uri, """{"order":["b","a"],"defaultInstanceId":"b"}""", version = 0).expectStatus().isOk
        getPreferences(uri).expectBody()
            .jsonPath("$.version").isEqualTo(1)
            .jsonPath("$.order[0]").isEqualTo("b")
            .jsonPath("$.defaultInstanceId").isEqualTo("b")
        write("PUT", uri, """{"order":[]}""", version = 0).expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody().jsonPath("$.errorCode").isEqualTo(ErrorCodes.COMMAND_EXPECT_VERSION_CONFLICT)
        write("PUT", uri, """{"order":[]}""", version = 3).expectStatus().isEqualTo(HttpStatus.CONFLICT)
        write("PUT", uri, """{"order":["a"]}""", version = 1).expectStatus().isOk
        getPreferences("$SCOPE/bob/definitions/prefs-orders/preferences").expectBody().jsonPath("$.version").isEqualTo(0)
        getPreferences(uri, OTHER_APP).expectBody().jsonPath("$.version").isEqualTo(0)
    }

    private fun getPreferences(uri: String, appId: String = APP): WebTestClient.ResponseSpec =
        client.get().uri(uri).header(ViewStoreService.APP_ID_HEADER, appId).exchange().expectStatus().isOk

    @Test
    fun `system views are served under the shared owner and refuse writes`() {
        client.get().uri("$SCOPE/$SHARED/system-views?definitionId=orders")
            .header(ViewStoreService.APP_ID_HEADER, APP).exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$[0].id").isEqualTo("orders-open").jsonPath("$[0].revision").isNotEmpty
        client.get().uri("$SCOPE/alice/system-views?definitionId=orders")
            .header(ViewStoreService.APP_ID_HEADER, APP).exchange()
            .expectStatus().isNotFound
        write("PUT", "$SCOPE/$SHARED/view/orders-open/rename", """{"title":"Mine"}""", version = 1)
            .expectStatus().isForbidden
            .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY)
    }

    @Test
    fun `the host's own aggregates keep Wow's default request id index`() {
        create("alice")
        MongoClients.create(WowTestContainers.mongo.connectionString).use { mongo ->
            val database = mongo.getDatabase(DATABASE)
            fun indexes(collection: String): List<String> =
                database.getCollection(collection).listIndexes().toFlux().map { (it as Document).getString("name") }
                    .collectList().block()!!
            indexes("view_event_stream").assert().contains("aggregateId_1_requestId_1").doesNotContain("requestId_1")
            indexes("order_event_stream").assert().contains("aggregateId_1_requestId_1").doesNotContain("requestId_1")
        }
    }
}

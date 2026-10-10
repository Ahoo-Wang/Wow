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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.serialization.toJsonString
import me.ahoo.wow.serialization.toObjectNode
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.api.ViewStoreErrorCodes
import me.ahoo.wow.viewstore.api.view.CreateView
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import java.net.URI
import java.util.UUID

/**
 * The view store embedded in a host service of another context: the routes, the rules the aggregates and the query
 * policy keep, the replay, preferences and system views, end to end over HTTP. Each storage runs it as a subclass
 * ([ViewStoreMongoTest], [ViewStoreElasticsearchTest]).
 */
@ExtendWith(ConnectionDiagnostics::class)
abstract class ViewStoreHostSpec {
    companion object {
        const val SCOPE = "/view-store/tenant/t1/owner"
        const val APP = "console"
        const val OTHER_APP = "portal"
        const val SHARED = "(shared)"
    }

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    private val port: String by lazy { applicationContext.environment.getRequiredProperty("local.server.port") }

    private val client: WebTestClient by lazy { viewStoreTestClient(port) }

    /** [path] sent exactly as written, `;` parameters and `%xx` escapes included. */
    private fun raw(path: String): URI = URI.create("http://localhost:$port$path")

    protected fun write(
        method: String,
        uri: String,
        body: String?,
        appId: String? = APP,
        version: Int? = null,
        requestId: String = UUID.randomUUID().toString(),
        headers: Map<String, String> = emptyMap(),
    ): WebTestClient.ResponseSpec = write(method, raw(uri), body, appId, version, requestId, headers)

    protected fun write(
        method: String,
        uri: URI,
        body: String?,
        appId: String? = APP,
        version: Int? = null,
        requestId: String = UUID.randomUUID().toString(),
        headers: Map<String, String> = emptyMap(),
    ): WebTestClient.ResponseSpec {
        val spec = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(uri)
            .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
            .header(CommandHeaders.REQUEST_ID, requestId)
            .contentType(MediaType.APPLICATION_JSON)
        appId?.let { spec.header(ViewStoreService.APP_ID_HEADER, it) }
        headers.forEach { (name, value) -> spec.header(name, value) }
        version?.let { spec.header(CommandHeaders.AGGREGATE_VERSION, it.toString()) }
        return (body?.let { spec.bodyValue(it) } ?: spec).exchange()
    }

    protected fun create(owner: String, config: String = """{"kind":"record"}""", requestId: String = UUID.randomUUID().toString()): String {
        val result = write(
            "POST",
            "$SCOPE/$owner/view",
            """{"definitionId":"orders","title":"View","config":$config}""",
            requestId = requestId,
        ).expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!
        return result.get("aggregateId").stringValue()
    }

    protected fun query(path: String, body: String, appId: String? = APP): WebTestClient.ResponseSpec {
        val spec = client.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
        appId?.let { spec.header(ViewStoreService.APP_ID_HEADER, it) }
        return spec.bodyValue(body).exchange()
    }

    protected fun single(owner: String, id: String, appId: String? = APP): WebTestClient.ResponseSpec =
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

    private fun facade(
        commandType: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        path: String = "/wow/command/send",
    ): WebTestClient.ResponseSpec {
        val spec = client.post().uri(raw(path))
            .header(CommandHeaders.COMMAND_TYPE, commandType)
            .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
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
            mapOf(CommandHeaders.AGGREGATE_ID to "orders-open", CommandHeaders.OWNER_ID to SHARED),
        ).expectStatus().isNotFound
        facade(
            "me.ahoo.wow.viewstore.api.preferences.SetViewPreferences",
            """{"definitionId":"orders","order":[]}""",
            mapOf(CommandHeaders.AGGREGATE_ID to "any", CommandHeaders.OWNER_ID to "mallory"),
        ).expectStatus().isNotFound
        facade(
            "me.ahoo.wow.viewstore.api.view.RenameView",
            """{"id":"$id","title":"Taken"}""",
            mapOf(CommandHeaders.AGGREGATE_ID to id),
        ).expectStatus().isNotFound
        facade(
            "any",
            """{"title":"Taken"}""",
            mapOf(
                CommandHeaders.COMMAND_AGGREGATE_CONTEXT to ViewStoreService.SERVICE_NAME,
                CommandHeaders.COMMAND_AGGREGATE_NAME to ViewStoreService.VIEW_AGGREGATE_NAME,
                CommandHeaders.AGGREGATE_ID to id,
            ),
        ).expectStatus().isNotFound
        single("alice", id).expectStatus().isOk.expectBody().jsonPath("$.version").isEqualTo(1)
    }

    /**
     * Spring routes on decoded path segments without `;` parameters, so every check of the starter must see the path
     * the same way: none of these forms may reach a view store route while skipping one.
     */
    private val scopeForms = listOf(
        "/view-store;x=1/tenant/t1/owner",
        "/view%2Dstore/tenant/t1/owner",
        "/view-store/tenant/t1/%6Fwner",
        "/view-store/tenant;a=b/t1/owner",
        "/view-store/tenant/t1/owner;o=1",
    )

    @Test
    fun `the facade is refused on every form of its path`() {
        val id = create("alice")
        listOf("/wow;x/command/send", "/wow/command;x/send", "/wow/command/send;x", "/wow/command/%73end").forEach { path ->
            facade(
                "me.ahoo.wow.viewstore.api.view.RenameView",
                """{"title":"Taken"}""",
                mapOf(CommandHeaders.AGGREGATE_ID to id, CommandHeaders.OWNER_ID to "alice"),
                path,
            ).expectStatus().isNotFound
            facade(
                "me.ahoo.wow.viewstore.api.view.CreateView",
                """{"definitionId":"orders","title":"Squat","config":{"kind":"record"}}""",
                mapOf(CommandHeaders.AGGREGATE_ID to "orders-open", CommandHeaders.OWNER_ID to SHARED),
                path,
            ).expectStatus().isNotFound
        }
        single("alice", id).expectStatus().isOk.expectBody().jsonPath("$.version").isEqualTo(1)
        single(SHARED, "orders-open").expectStatus().isNotFound
    }

    @Test
    fun `every form of a view store path gets the id and the application rules`() {
        scopeForms.forEach { scope ->
            val chosen = "chosen-" + UUID.randomUUID()
            val result = client.post().uri(raw("$scope/alice/view"))
                .header(ViewStoreService.APP_ID_HEADER, APP)
                .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
                .header(CommandHeaders.AGGREGATE_ID, chosen)
                .header(CommandHeaders.COMMAND_HEADER_X_PREFIX + ViewStoreService.APP_ID_MESSAGE_HEADER, OTHER_APP)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"definitionId":"orders","title":"View","config":{"kind":"record"}}""")
                .exchange().expectStatus().isOk
                .expectBody(JsonNode::class.java).returnResult().responseBody!!
            val id = result.get("aggregateId").stringValue()
            id.assert().isNotEqualTo(chosen)
            single("alice", id).expectStatus().isOk.expectBody().jsonPath("$.state.appId").isEqualTo(APP)
            // Without CoSec-App-Id there is no application, whatever the command header says.
            client.post().uri(raw("$scope/alice/view"))
                .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
                .header(CommandHeaders.COMMAND_HEADER_X_PREFIX + ViewStoreService.APP_ID_MESSAGE_HEADER, APP)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"definitionId":"orders","title":"View","config":{"kind":"record"}}""")
                .exchange().expectStatus().isBadRequest
                .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_APP_REQUIRED)
            // Another application's caller cannot rename it by naming the view's application in a command header.
            client.put().uri(raw("$scope/alice/view/$id/rename"))
                .header(ViewStoreService.APP_ID_HEADER, OTHER_APP)
                .header(CommandHeaders.COMMAND_HEADER_X_PREFIX + ViewStoreService.APP_ID_MESSAGE_HEADER, APP)
                .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
                .header(CommandHeaders.AGGREGATE_VERSION, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"title":"Taken"}""")
                .exchange().expectStatus().isNotFound
            // A system view stays read-only, and Wow's closed routes stay closed.
            write("PUT", "$scope/$SHARED/view/orders-open/rename", """{"title":"Mine"}""", version = 1)
                .expectStatus().isForbidden
                .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY)
            client.get().uri(raw("$scope/alice/view/$id/state")).header(ViewStoreService.APP_ID_HEADER, APP)
                .exchange().expectStatus().isNotFound
        }
        write("PUT", "$SCOPE/$SHARED/view/orders%2Dopen/rename", """{"title":"Mine"}""", version = 1)
            .expectStatus().isForbidden
        write("PUT", "$SCOPE/$SHARED/view/orders-open;v=1/rename", """{"title":"Mine"}""", version = 1)
            .expectStatus().isForbidden
    }

    /**
     * Wow reads a blank tenant or owner path variable as missing and falls back to `Command-Tenant-Id` /
     * `Command-Owner-Id`, or with no header to no owner at all, which skips its owner check. Spring decodes these
     * segments into non-empty values that match `{tenantId}` / `{ownerId}`, so each must be refused before routing.
     */
    private val blankSegments = listOf("%20", "%09", "%E3%80%80", "%20;x=alice")

    private fun scoped(
        method: String,
        uri: String,
        body: String?,
        headers: Map<String, String>,
        version: Int? = null,
    ): WebTestClient.ResponseSpec {
        val spec = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(raw(uri))
            .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
            .header(ViewStoreService.APP_ID_HEADER, APP)
            .contentType(MediaType.APPLICATION_JSON)
        version?.let { spec.header(CommandHeaders.AGGREGATE_VERSION, it.toString()) }
        headers.forEach { (name, value) -> spec.header(name, value) }
        return (body?.let { spec.bodyValue(it) } ?: spec).exchange()
    }

    @Test
    fun `a blank tenant or owner in the path never falls back to the headers`() {
        val personal = create("alice")
        val shared = create(SHARED)
        val planted = "planted-" + UUID.randomUUID()
        val plantedTenant = "t-" + UUID.randomUUID()
        blankSegments.forEach { blank ->
            val scopes = listOf(
                "/view-store/tenant/t1/owner/$blank" to listOf(
                    emptyMap(),
                    mapOf(CommandHeaders.OWNER_ID to "alice"),
                    mapOf("command-owner-id" to planted),
                ),
                "/view-store/tenant/$blank/owner/alice" to listOf(
                    emptyMap(),
                    mapOf(CommandHeaders.TENANT_ID to "t1"),
                    mapOf("COMMAND-TENANT-ID" to plantedTenant),
                ),
            )
            scopes.forEach { (scope, headerSets) ->
                headerSets.forEach { headers ->
                    listOf(
                        scoped("PUT", "$scope/view/$personal/rename", """{"title":"Taken"}""", headers, version = 1),
                        scoped("DELETE", "$scope/view/$personal", "{}", headers, version = 1),
                        scoped("PUT", "$scope/view/$personal/share", "{}", headers, version = 1),
                        scoped("PUT", "$scope/view/$shared/claim", null, headers, version = 1),
                        scoped(
                            "POST",
                            "$scope/view",
                            """{"definitionId":"orders","title":"Planted","config":{"kind":"record"}}""",
                            headers,
                        ),
                        scoped("PUT", "$scope/definitions/orders/preferences", """{"order":["x"]}""", headers, version = 0),
                        scoped("GET", "$scope/definitions/orders/preferences", null, headers),
                        scoped("POST", "$scope/view/snapshot/list", listQuery { }.toJsonString(), headers),
                        scoped("GET", "$scope/view/requests/any", null, headers),
                    ).forEach { response ->
                        response.expectStatus().isBadRequest
                            .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED)
                    }
                }
            }
        }
        single("alice", personal).expectStatus().isOk
            .expectBody().jsonPath("$.version").isEqualTo(1).jsonPath("$.state.title").isEqualTo("View")
        single(SHARED, shared).expectStatus().isOk.expectBody().jsonPath("$.version").isEqualTo(1)
        listed(planted).assert().isZero()
        query("/view-store/tenant/$plantedTenant/owner/alice/view/snapshot/list", listQuery { }.toJsonString())
            .expectStatus().isOk.expectBody().jsonPath("$.length()").isEqualTo(0)
        getPreferences("$SCOPE/alice/definitions/orders/preferences").expectBody().jsonPath("$.version").isEqualTo(0)
    }

    /**
     * Characters that display as nothing: `owner/alice%E2%80%8B` would be an owner that reads as `alice`. Zero-width
     * space, byte order mark, soft hyphen, word joiner, Mongolian vowel separator, a private-use character.
     */
    private val invisibleSegments = listOf(
        "alice%E2%80%8B", "%EF%BB%BFalice", "al%C2%ADice", "alice%E2%81%A0", "alice%E1%A0%8E", "alice%EE%80%80",
    )

    @Test
    fun `a tenant or owner with an invisible character is refused`() {
        val personal = create("alice")
        invisibleSegments.forEach { segment ->
            val shared = create(SHARED)
            listOf(
                scoped("PUT", "/view-store/tenant/t1/owner/$segment/view/$shared/claim", null, emptyMap(), version = 1),
                scoped("PUT", "/view-store/tenant/t1/owner/$segment/view/$personal/rename", """{"title":"X"}""", emptyMap(), version = 1),
                scoped("PUT", "/view-store/tenant/t1$segment/owner/alice/view/$personal/rename", """{"title":"X"}""", emptyMap(), version = 1),
                scoped("POST", "/view-store/tenant/t1/owner/$segment/view/snapshot/list", listQuery { }.toJsonString(), emptyMap()),
            ).forEach { response ->
                response.expectStatus().isBadRequest
                    .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED)
            }
            single(SHARED, shared).expectStatus().isOk.expectBody().jsonPath("$.version").isEqualTo(1)
        }
        single("alice", personal).expectStatus().isOk.expectBody().jsonPath("$.version").isEqualTo(1)
    }

    /**
     * Invisible characters outside the format category: combining grapheme joiner, variation selectors (U+FE00,
     * U+FE0F, U+E0100, U+E01EF), Khmer inherent vowels, Hangul fillers (U+3164, U+FFA0, U+115F, U+1160), braille
     * blank.
     */
    private val otherInvisibleSegments = listOf(
        "alice%CD%8F", "alice%EF%B8%80", "alice%EF%B8%8F", "alice%F3%A0%84%80", "alice%F3%A0%87%AF",
        "alice%E1%9E%B4", "alice%E1%9E%B5", "%E3%85%A4", "alice%EF%BE%A0", "%E1%85%9F", "alice%E1%85%A0",
        "%E2%A0%80",
    )

    @Test
    fun `a tenant or owner with an invisible character of another category is refused`() {
        val personal = create("alice")
        val shared = create(SHARED)
        otherInvisibleSegments.forEach { segment ->
            val tenant = "t1${segment.removePrefix("alice")}"
            listOf(
                "/view-store/tenant/t1/owner/$segment",
                "/view-store/tenant/$tenant/owner/alice",
                "/view-store/tenant/$tenant/owner/$SHARED",
            ).forEach { scope ->
                listOf(
                    scoped("PUT", "$scope/view/$shared/claim", null, emptyMap(), version = 1),
                    scoped("PUT", "$scope/view/$personal/rename", """{"title":"X"}""", emptyMap(), version = 1),
                    scoped("POST", "$scope/view/snapshot/list", listQuery { }.toJsonString(), emptyMap()),
                ).forEach { response ->
                    response.expectStatus().isBadRequest
                        .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED)
                }
            }
        }
        single(SHARED, shared).expectStatus().isOk.expectBody().jsonPath("$.version").isEqualTo(1)
        single("alice", personal).expectStatus().isOk.expectBody().jsonPath("$.version").isEqualTo(1)
    }

    @Autowired
    private lateinit var commandGateway: CommandGateway

    /** A view of alice in `console` with the id [id], created in process (a route never takes a client's id). */
    private fun seed(id: String) {
        val command = CreateView(definitionId = "orders", title = "Seeded", config = """{"kind":"record"}""".toObjectNode())
            .toCommandMessage(
                aggregateId = id,
                tenantId = "t1",
                ownerId = "alice",
                header = DefaultHeader.empty().with(ViewStoreService.APP_ID_MESSAGE_HEADER, APP),
            )
        commandGateway.sendAndWaitForSnapshot(command).block()!!.succeeded.assert().isTrue()
    }

    /**
     * An open route wins over a closed one only in its own case. On this case-sensitive host `…/view/REQUESTS/state`
     * is Wow's closed `view/{id}/state` for a view with the id `REQUESTS`, although it matches the replay route
     * `view/requests/{requestId}` ignoring case.
     */
    @Test
    fun `a closed route is not opened by an open route in another case`() {
        seed("REQUESTS")
        seed("Requests")
        single("alice", "REQUESTS").expectStatus().isOk
        listOf("REQUESTS", "Requests").forEach { id ->
            listOf("state", "snapshot").forEach { read ->
                listOf(APP, null).forEach { appId ->
                    val spec = client.get().uri("$SCOPE/alice/view/$id/$read")
                    appId?.let { spec.header(ViewStoreService.APP_ID_HEADER, it) }
                    spec.exchange().expectStatus().isNotFound
                        .expectBody().jsonPath("$.errorCode").isEqualTo(ErrorCodes.NOT_FOUND)
                        .jsonPath("$.state").doesNotExist()
                }
            }
        }
    }

    @Test
    fun `a create names its application by CoSec-App-Id over a command header`() {
        val result = write(
            "POST",
            "$SCOPE/alice/view",
            """{"definitionId":"orders","title":"View","config":{"kind":"record"}}""",
            appId = null,
            headers = mapOf(
                CommandHeaders.COMMAND_HEADER_X_PREFIX + ViewStoreService.APP_ID_MESSAGE_HEADER to OTHER_APP,
                ViewStoreService.APP_ID_HEADER to APP,
            ),
        ).expectStatus().isOk.expectBody(JsonNode::class.java).returnResult().responseBody!!
        val id = result.get("aggregateId").stringValue()
        single("alice", id).expectStatus().isOk.expectBody().jsonPath("$.state.appId").isEqualTo(APP)
        single("alice", id, OTHER_APP).expectStatus().isNotFound
    }

    @Test
    fun `a caller's command headers never reach the command`() {
        val prefix = CommandHeaders.COMMAND_HEADER_X_PREFIX
        val result = scoped(
            "POST",
            "$SCOPE/alice/view",
            """{"definitionId":"orders","title":"View","config":{"kind":"record"}}""",
            mapOf(
                prefix + "command_operator" to "bob",
                prefix.lowercase() + "app_id" to OTHER_APP,
                prefix.uppercase() + "TENANT_ID" to "t9",
                prefix + "owner_id" to "bob",
            ),
        ).expectStatus().isOk.expectBody(JsonNode::class.java).returnResult().responseBody!!
        val id = result.get("aggregateId").stringValue()
        val snapshot = single("alice", id).expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!
        snapshot.get("operator").stringValue().assert().isNotEqualTo("bob")
        snapshot.get("firstOperator").stringValue().assert().isNotEqualTo("bob")
        snapshot.get("state").get("appId").stringValue().assert().isEqualTo(APP)
        snapshot.get("tenantId").stringValue().assert().isEqualTo("t1")
        snapshot.get("ownerId").stringValue().assert().isEqualTo("alice")
    }

    @Test
    fun `a caller's tenant and owner headers never replace the path's`() {
        val id = create("alice")
        scoped(
            "PUT",
            "$SCOPE/alice/view/$id/rename",
            """{"title":"Renamed"}""",
            mapOf(CommandHeaders.OWNER_ID to "bob", CommandHeaders.TENANT_ID to "t9"),
            version = 1,
        ).expectStatus().isOk
        single("alice", id).expectStatus().isOk.expectBody().jsonPath("$.state.title").isEqualTo("Renamed")
        // A claim is dispatched under the owner `(shared)`, so the caller's own owner header would contradict it (Wow
        // rejects a header contradicting the owner a route fixes): the starter drops it, and the claim passes.
        val shared = create(SHARED)
        scoped(
            "PUT",
            "$SCOPE/alice/view/$shared/claim",
            null,
            mapOf(CommandHeaders.OWNER_ID to "alice", CommandHeaders.TENANT_ID to "t1"),
            version = 1,
        ).expectStatus().isOk
        single("alice", shared).expectStatus().isOk.expectBody().jsonPath("$.ownerId").isEqualTo("alice")
    }

    private fun listed(owner: String): Int =
        query("$SCOPE/$owner/view/snapshot/list", listQuery { }.toJsonString())
            .expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!.size()

    /**
     * The replay reads a bounded number of candidates, so the writes of another owner with the same request id must
     * not push the path owner's write out of them: a `(shared)` writer who knows a claim's request id writes more
     * than the bound under `(shared)` with it.
     */
    @Test
    fun `other owners' writes with a request id never hide the path owner's`() {
        val requestId = UUID.randomUUID().toString()
        val claimed = create(SHARED)
        // Written before the claim, so a bound without the writer in the query reads only these.
        repeat(25) { create(SHARED, requestId = requestId) }
        repeat(25) { create("bob", requestId = requestId) }
        write("PUT", "$SCOPE/alice/view/$claimed/claim", null, version = 1, requestId = requestId).expectStatus().isOk
        replay("alice", requestId).expectStatus().isOk
            .expectBody().jsonPath("$.aggregateId").isEqualTo(claimed).jsonPath("$.ownerId").isEqualTo("alice")
        // The (shared) path finds its own creates, never alice's claim.
        replay(SHARED, requestId).expectStatus().isOk
            .expectBody().jsonPath("$.ownerId").isEqualTo(SHARED).jsonPath("$.version").isEqualTo(1)
        val own = create("carol", requestId = requestId)
        replay("carol", requestId).expectStatus().isOk.expectBody().jsonPath("$.aggregateId").isEqualTo(own)
    }

    @Test
    fun `a claim is replayed on the claiming owner's path, not on the shared one`() {
        val id = create(SHARED)
        val requestId = UUID.randomUUID().toString()
        write("PUT", "$SCOPE/alice/view/$id/claim", null, version = 1, requestId = requestId).expectStatus().isOk
        replay("alice", requestId).expectStatus().isOk
            .expectBody().jsonPath("$.ownerId").isEqualTo("alice").jsonPath("$.version").isEqualTo(2)
        replay(SHARED, requestId).expectStatus().isNotFound
        replay("bob", requestId).expectStatus().isNotFound
        // A claim for an owner with a blank in it is refused.
        write("PUT", "$SCOPE/alice%20/view/${create(SHARED)}/claim", null, version = 1).expectStatus().isBadRequest
    }

    @Test
    fun `the application comes from CoSec-App-Id, never from a command header`() {
        client.post().uri("$SCOPE/alice/view")
            .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
            .header(CommandHeaders.COMMAND_HEADER_X_PREFIX + ViewStoreService.APP_ID_MESSAGE_HEADER, OTHER_APP)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"definitionId":"orders","title":"View","config":{"kind":"record"}}""")
            .exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_APP_REQUIRED)
        val id = create("alice")
        client.put().uri("$SCOPE/alice/view/$id/rename")
            .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
            .header(CommandHeaders.AGGREGATE_VERSION, "1")
            .header(ViewStoreService.APP_ID_HEADER, OTHER_APP)
            .header(CommandHeaders.COMMAND_HEADER_X_PREFIX + ViewStoreService.APP_ID_MESSAGE_HEADER, APP)
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
            .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
            .header(CommandHeaders.AGGREGATE_ID, "chosen-id")
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
        client.get().uri(raw("$SCOPE/$owner/view/requests/$requestId"))
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

    private val systemScope = "/view-store/tenant/${ViewStoreService.SYSTEM_TENANT_ID}/owner/${ViewStoreService.SYSTEM_OWNER_ID}"

    private fun createSystemView(definitionId: String, title: String, tenantScope: String = systemScope): String =
        write("POST", "$tenantScope/view", """{"definitionId":"$definitionId","title":"$title","config":{"kind":"record"}}""")
            .expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!.get("aggregateId").stringValue()

    private fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

    private fun systemViews(tenantId: String, query: String = "", appId: String = APP): JsonNode =
        client.get().uri("/view-store/tenant/$tenantId/owner/$SHARED/system-views$query")
            .header(ViewStoreService.APP_ID_HEADER, appId).exchange()
            .expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!

    private fun systemView(tenantId: String, id: String, appId: String = APP): WebTestClient.ResponseSpec =
        client.get().uri("/view-store/tenant/$tenantId/owner/$SHARED/system-views/$id")
            .header(ViewStoreService.APP_ID_HEADER, appId).exchange()

    @Test
    fun `stored system views are global - every tenant reads them beside the configured ones`() {
        val definitionId = "global-" + UUID.randomUUID()
        val id = createSystemView(definitionId, "Everyone's")
        listOf("t1", "t2").forEach { tenantId ->
            val all = systemViews(tenantId)
            all.items().map { it.get("id").stringValue() }.assert().contains("orders-open", id)
            all.items().first { it.get("id").stringValue() == "orders-open" }.let {
                it.get("source").stringValue().assert().isEqualTo("configured")
                it.get("version").isNull.assert().isTrue()
            }
            val views = systemViews(tenantId, "?definitionId=$definitionId")
            views.size().assert().isEqualTo(1)
            views[0].let {
                it.get("id").stringValue().assert().isEqualTo(id)
                it.get("source").stringValue().assert().isEqualTo("stored")
                it.get("version").intValue().assert().isEqualTo(1)
                it.get("scope").stringValue().assert().isEqualTo("system")
                it.get("kind").stringValue().assert().isEqualTo("record")
                it.get("title").stringValue().assert().isEqualTo("Everyone's")
                it.get("revision").stringValue().length.assert().isEqualTo(16)
            }
        }
        systemView("t1", id).expectStatus().isOk
            .expectBody().jsonPath("$.source").isEqualTo("stored").jsonPath("$.version").isEqualTo(1)
        // Per application.
        systemView("t1", id, OTHER_APP).expectStatus().isNotFound
        systemViews("t1", "?definitionId=$definitionId", OTHER_APP).size().assert().isEqualTo(0)
        // The user's own lists never hold it.
        query("$SCOPE/$SHARED/view/snapshot/list", listQuery { limit(1000) }.toJsonString())
            .expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!
            .items().map { it.get("aggregateId").stringValue() }.assert().doesNotContain(id)

        // Written through the view routes, at its version, on the global path.
        write("PUT", "$systemScope/view/$id/rename", """{"title":"Renamed"}""", version = 1).expectStatus().isOk
        val renamed = systemView("t2", id).expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!
        renamed.get("title").stringValue().assert().isEqualTo("Renamed")
        renamed.get("version").intValue().assert().isEqualTo(2)
        write("PUT", "$systemScope/view/$id/save", """{"config":{"kind":"record","pageSize":50}}""", version = 1)
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
        write("PUT", "$systemScope/view/$id/save", """{"config":{"kind":"record","pageSize":50}}""", version = 2)
            .expectStatus().isOk
        systemView("t1", id).expectBody().jsonPath("$.config.pageSize").isEqualTo(50).jsonPath("$.version").isEqualTo(3)

        // It never moves audience, and no other owner path writes it.
        write("PUT", "$systemScope/view/$id/share", "{}", version = 3)
            .expectStatus().isForbidden
            .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.SYSTEM_VIEW_READ_ONLY)
        write("PUT", "/view-store/tenant/${ViewStoreService.SYSTEM_TENANT_ID}/owner/bob/view/$id/claim", null, version = 3)
            .expectStatus().isForbidden
        write("PUT", "/view-store/tenant/${ViewStoreService.SYSTEM_TENANT_ID}/owner/$SHARED/view/$id/rename", """{"title":"Mine"}""", version = 3)
            .expectStatus().isForbidden
        // In another tenant it is not that view (the storage answers it as a tenant mismatch or a missing one).
        write("PUT", "$SCOPE/$SHARED/view/$id/rename", """{"title":"Mine"}""", version = 3)
            .expectStatus().is4xxClientError

        // Unpublished: deleted, also while a shared dashboard shows it (as a shared view).
        val board = create(SHARED, boardReferencing(id)[0])
        write("DELETE", "$systemScope/view/$id", "{}", version = 3).expectStatus().isOk
        systemView("t1", id).expectStatus().isNotFound
        single(SHARED, board).expectStatus().isOk
    }

    @Test
    fun `a system view is created under the global tenant only, on its path spelled exactly`() {
        // Refused in every other tenant, Wow's default tenant `(0)` included.
        listOf("$SCOPE/(system)/view", "/view-store/tenant/(0)/owner/(system)/view").forEach { path ->
            write("POST", path, """{"definitionId":"orders","title":"Tenant's","config":{"kind":"record"}}""")
                .expectStatus().isBadRequest
                .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_INVALID)
        }
        listOf(
            "/view-store/tenant/(platform)/owner/%28system%29/view",
            "/view-store/tenant/%28platform%29/owner/(system)/view",
            "/view-store/tenant/(platform)/owner/(system);x=1/view",
            "/view-store/TENANT/(platform)/owner/(system)/view",
            "/view-store/tenant/(PLATFORM)/owner/(system)/view",
            "/view-store/tenant/%28Platform%29/owner/(System)/view",
        ).forEach { path ->
            write("POST", path, """{"definitionId":"orders","title":"Odd","config":{"kind":"record"}}""")
                .expectStatus().isBadRequest
                .expectBody().jsonPath("$.errorCode").isEqualTo(ViewStoreErrorCodes.VIEW_SCOPE_REQUIRED)
        }
    }

    /**
     * A stored view wins over a configured one with the same id, on reads and on writes. The server generates every
     * id, so the clash is made in process.
     */
    @Test
    fun `a stored system view wins over a configured one with its id`() {
        val app = "clash-" + UUID.randomUUID().toString().take(8)
        val command = CreateView(definitionId = "orders", title = "Stored", config = """{"kind":"record"}""".toObjectNode())
            .toCommandMessage(
                aggregateId = "orders-open",
                tenantId = ViewStoreService.SYSTEM_TENANT_ID,
                ownerId = ViewStoreService.SYSTEM_OWNER_ID,
                header = DefaultHeader.empty().with(ViewStoreService.APP_ID_MESSAGE_HEADER, app),
            )
        commandGateway.sendAndWaitForSnapshot(command).block()!!.succeeded.assert().isTrue()
        val views = systemViews("t1", "?definitionId=orders", app)
        views.items().map { it.get("id").stringValue() + "=" + it.get("title").stringValue() }
            .assert().containsExactly("orders-open=Stored")
        systemView("t1", "orders-open", app).expectBody().jsonPath("$.source").isEqualTo("stored")
        write("PUT", "$systemScope/view/orders-open/rename", """{"title":"Stored again"}""", appId = app, version = 1)
            .expectStatus().isOk
        // Another application still reads the configured one, and its write under another owner stays refused.
        systemView("t1", "orders-open").expectBody().jsonPath("$.source").isEqualTo("configured")
        write("PUT", "$SCOPE/$SHARED/view/orders-open/rename", """{"title":"Mine"}""", appId = app, version = 1)
            .expectStatus().isForbidden
    }
}

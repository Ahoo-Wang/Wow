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

package me.ahoo.wow.viewstore.server.system

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.RowQueries
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.viewstore.api.ViewStoreContext
import me.ahoo.wow.viewstore.api.ViewStoreContract
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.node.ObjectNode

/** The configured system views: checked at startup, matched by tenant and application, revised by content. */
class SystemViewsTest {
    private fun entry(
        id: String = "all",
        title: String = "All",
        config: String = """{"kind":"record"}""",
        tenantId: String = "",
        appId: String = "",
        definitionId: String = "orders",
    ) = SystemViewProperties(tenantId, appId, id, definitionId, title, config)

    @Test
    fun `an entry should be for its tenant and application, or every one when blank`() {
        val views = ConfiguredSystemViews(
            listOf(
                entry("all"),
                entry("t1", tenantId = "t1"),
                entry("a1", appId = "a1"),
                entry("both", "B", "{\"kind\":\"record\"}", "t1", "a1")
            ),
        )
        views.systemViews("t1", "a1").map { it.id }.assert().containsExactly("all", "t1", "a1", "both")
        views.systemViews("t2", "a2").map { it.id }.assert().containsExactly("all")
    }

    @Test
    fun `a revision should change exactly when the content does`() {
        val one = SystemViews.of("v", "orders", " All ", mapOf("kind" to "record"))
        one.title.assert().isEqualTo("All")
        SystemViews.of("other-id", "orders", "All", mapOf("kind" to "record")).revision.assert().isEqualTo(one.revision)
        SystemViews.of("v", "orders", "All", mapOf("kind" to "analysis")).revision.assert().isNotEqualTo(one.revision)
    }

    @Test
    fun `an entry a saved view could not be should stop the service`() {
        listOf(
            entry(id = " "),
            entry(id = "system:all"),
            entry(title = " "),
            entry(definitionId = " "),
            entry(config = """{"kind":"chart"}"""),
            entry(config = "[1]"),
        ).forEach { bad ->
            assertThrownBy<IllegalArgumentException> { ConfiguredSystemViews(listOf(bad)) }
        }
        assertThrownBy<IllegalArgumentException> { ConfiguredSystemViews(listOf(entry(), entry(title = "Again"))) }
        ConfiguredSystemViews(listOf(entry(), entry(appId = "a1"))).systemViews("t", "a1").assert().hasSize(2)
    }

    @Test
    fun `a request without accepted credentials should be answered 401`() {
        val views = StoredSystemViews(ConfiguredSystemViews(emptyList())) { null }
        val routes = SystemViewRoutes(views, CallerResolver { null }).routes()
        WebTestClient.bindToRouterFunction(routes).build().get().uri(SystemViewRoutes.SYSTEM_VIEWS)
            .header(ViewStoreContext.APP_ID_HEADER, "app")
            .exchange()
            .expectStatus().isUnauthorized
            .expectBody(String::class.java).returnResult().responseBody.assert()
            .contains("\"code\":\"wow.auth.unauthenticated\"")
    }

    private fun stored(id: String, title: String) = WowJson.mapper.createObjectNode().apply {
        put("aggregateId", id)
        put("version", 1)
        putObject("state").apply {
            put("definitionId", "orders")
            put("title", title)
            putObject("config").put("kind", "record")
        }
    }

    private fun storedViews(records: () -> Flow<ObjectNode>): StoredSystemViews {
        val rows = mockk<RowQueries<ObjectNode>> { every { list(any()) } answers { records() } }
        val api = mockk<QueryApi> {
            every {
                trusted().snapshots(ViewStoreContract.View.TYPE).dynamic(ObjectNode::class.java)
            } returns rows
        }
        return StoredSystemViews(ConfiguredSystemViews(emptyList())) { api }
    }

    private fun views(records: () -> Flow<ObjectNode>): WebTestClient {
        val views = storedViews(records)
        // The application the resolver authenticated, from the header as the view store's own resolver takes it.
        val callers = CallerResolver { request ->
            val app = request.headers().firstHeader(ViewStoreContext.APP_ID_HEADER)
            Caller.of(tenantId = "t1", attributes = app?.let { mapOf(Caller.APP to listOf(it)) }.orEmpty())
        }
        return WebTestClient.bindToRouterFunction(SystemViewRoutes(views, callers).routes()).build()
    }

    @Test
    fun `a stored view that no longer passes the checks should be left out, not fail the list (R2_3-7)`() {
        val client = views { flowOf(stored("kept", "Kept"), stored("system:x", "Broken"), stored("blank", " ")) }
        val listed = client.get().uri(SystemViewRoutes.SYSTEM_VIEWS)
            .header(ViewStoreContext.APP_ID_HEADER, "app")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        WowJson.mapper.readTree(listed).toList().map { it["id"].asString() }.assert().containsExactly("kept")
    }

    @Test
    fun `a store that is unavailable should be a problem the client retries after a while (R2_3-8)`() {
        val unavailable = views { flow { throw StorageUnavailableException("The snapshots are unavailable.") } }
        unavailable.get().uri(SystemViewRoutes.SYSTEM_VIEWS)
            .header(ViewStoreContext.APP_ID_HEADER, "app")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().valueEquals("Retry-After", "1")
            .expectHeader().contentType("application/problem+json")
            .expectBody(String::class.java).returnResult().responseBody!!
            .let { WowJson.mapper.readTree(it)["code"].asString() }.assert().isEqualTo("wow.storage.unavailable")
        val broken = views { flow { error("A bug.") } }
        broken.get().uri("${SystemViewRoutes.SYSTEM_VIEWS}/kept")
            .header(ViewStoreContext.APP_ID_HEADER, "app")
            .exchange()
            .expectStatus().isEqualTo(500)
            .expectHeader().contentType("application/problem+json")
    }

    @Test
    fun `a stored view failing the checks should be logged by its id, or without one, and left out (R2_3-7)`() {
        val nameless = stored("nameless", "Nameless").apply { remove("aggregateId") }
        val broken = stored("system:x", "Broken")
        val views = storedViews { flowOf(stored("kept", "Kept"), broken, nameless) }
        val logger = LoggerFactory.getLogger(StoredSystemViews::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val listed = try {
            runBlocking { views.systemViews("t1", "app") }
        } finally {
            logger.detachAppender(appender)
        }
        listed.map { it.id }.assert().containsExactly("kept")
        val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        warnings.assert().hasSize(2)
        warnings[0].assert().contains("[system:x] of app is not a valid system view")
        warnings[1].assert().contains("[null] of app is not a valid system view")
    }

    @Test
    fun `a cancelled request should stay cancelled, not be answered as a problem (R2_3-8)`() {
        val cancelled = views { flow { throw CancellationException("The client went away.") } }
        cancelled.get().uri(SystemViewRoutes.SYSTEM_VIEWS)
            .header(ViewStoreContext.APP_ID_HEADER, "app")
            .exchange()
            .expectStatus().isEqualTo(500)
            .expectHeader().doesNotExist("Content-Type")
            .expectBody().isEmpty()
    }
}

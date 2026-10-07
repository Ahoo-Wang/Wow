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

import io.netty.channel.Channel
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.viewstore.ViewStoreService
import me.ahoo.wow.viewstore.domain.view.SharedBoardReferences
import me.ahoo.wow.viewstore.starter.system.StoredSystemViewSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import reactor.netty.Connection
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import tools.jackson.databind.JsonNode
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.Duration
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The server boots with its own configuration and serves the view store under `/view-store`. The middleware it runs
 * on (MongoDB, Kafka, Redis) is swapped for in-memory stores and buses and a fixed machine id, so the test needs none
 * of it; [ViewStoreServerConfigurationTest] pins the middleware of the configurations themselves.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "wow.eventsourcing.store.storage=in_memory",
        "wow.eventsourcing.snapshot.storage=in_memory",
        "wow.mongo.enabled=false",
        "wow.kafka.enabled=false",
        "wow.command.bus.type=in_memory",
        "wow.event.bus.type=in_memory",
        "wow.eventsourcing.state.bus.type=in_memory",
        "cosid.machine.distributor.type=manual",
        "cosid.machine.distributor.manual.machine-id=1",
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

        /** Records the server's connections for the stall report. */
        @Bean
        fun recordServerConnections(): NettyServerCustomizer = NettyServerCustomizer { server ->
            server.doOnConnection { SERVER_CHANNELS.add(it.channel()) }
        }
    }

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    private val client: WebTestClient by lazy {
        val port = applicationContext.environment.getRequiredProperty("local.server.port")
        // The default connector (`compress(true)`), but every request opens its own connection, which is recorded for
        // the stall report. On a reused (keep-alive) connection Reactor Netty 1.3.7's server can stop reading
        // (reactor/reactor-netty#4361, fixed in 1.3.8): when a request arrives before the previous response has
        // finished (the create command's result completes on a dispatch worker), it is queued; once that queued
        // request is answered synchronously (the preferences read), the drain returns without requesting another
        // read, and the next request on the connection, `/v3/api-docs`, is read only when the client gives up and
        // closes it (on Linux epoll). The view store integration client does the same (#3962).
        val connector = ReactorClientHttpConnector(
            HttpClient.create(ConnectionProvider.newConnection()).compress(true)
                .doOnConnected { CLIENT_CHANNELS.add(it.channel()) }
        )
        WebTestClient.bindToServer(connector)
            .baseUrl("http://localhost:$port")
            .codecs { it.defaultCodecs().maxInMemorySize(OPENAPI_BUFFER_BYTES) }
            .build()
    }

    /**
     * The first `/v3/api-docs` builds the whole document: springdoc scans the routes and every command, query and
     * response schema is generated, on the request. That takes about 0.2 s on a warm machine but several seconds on a
     * busy CI runner, past WebTestClient's default 5 s response timeout; later requests are served from springdoc's
     * cache. Only this request gets the longer budget.
     */
    private val openApiClient: WebTestClient by lazy {
        client.mutate().responseTimeout(OPENAPI_RESPONSE_TIMEOUT).build()
    }

    @Test
    fun `serves the generated and the custom routes in its OpenAPI`() {
        // Diagnostic for a CI-only stall of this request: report the connections and threads if it has not answered in
        // time. Written to the process's stderr, which Gradle does not capture, so the report reaches the CI log.
        val dump = Thread {
            try {
                Thread.sleep(STALL_DUMP_AFTER.toMillis())
                PrintStream(FileOutputStream(FileDescriptor.err), true).print(stallReport())
            } catch (_: InterruptedException) {
                // Answered in time.
            }
        }.apply {
            isDaemon = true
            start()
        }
        val paths = try {
            openApiClient.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk
                .expectBody(JsonNode::class.java).returnResult().responseBody!!
                .get("paths").propertyNames().toList()
        } finally {
            dump.interrupt()
        }
        val scope = "/view-store/tenant/{tenantId}/owner/{ownerId}"
        paths.assert().contains(
            "$scope/view",
            "$scope/view/{id}/save",
            "$scope/view/{id}/rename",
            "$scope/view/{id}/share",
            "$scope/view/{id}/claim",
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
            .jsonPath("$[0].source").isEqualTo("configured")
    }

    /**
     * Snapshots in memory have no query backend: stored system views are off (one warning at startup), and the
     * configured ones are still served, the list and one by id.
     */
    @Test
    fun `without a snapshot query backend the configured system views are served alone`() {
        applicationContext.getBean(StoredSystemViewSource::class.java).assert().isSameAs(StoredSystemViewSource.NONE)
        client.get().uri("/view-store/tenant/t1/owner/(shared)/system-views/orders-open")
            .header(ViewStoreService.APP_ID_HEADER, "console")
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.source").isEqualTo("configured")
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

    private fun claim(owner: String, id: String): WebTestClient.ResponseSpec =
        client.put().uri("/view-store/tenant/t1/owner/$owner/view/$id/claim")
            .header(ViewStoreService.APP_ID_HEADER, "console")
            .header(CommandComponent.Header.WAIT_STAGE, "SNAPSHOT")
            .header(CommandComponent.Header.AGGREGATE_VERSION, "1")
            .exchange()

    /** The owner is the path's: the gateway lets a caller use only their own `owner/{ownerId}`. */
    @Test
    fun `a view claimed on a personal path belongs to that owner`() {
        val id = createShared()
        claim("bob", id)
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
}

private const val OPENAPI_BUFFER_BYTES = 16 * 1024 * 1024
private val OPENAPI_RESPONSE_TIMEOUT: Duration = Duration.ofMinutes(1)

private val STALL_DUMP_AFTER: Duration = Duration.ofSeconds(20)

private val SERVER_CHANNELS: MutableSet<Channel> = CopyOnWriteArraySet()
private val CLIENT_CHANNELS: MutableSet<Channel> = CopyOnWriteArraySet()

/**
 * The state that tells the candidate causes apart: whether the server channel still reads (`autoRead`, the pending
 * responses and pipelined requests of reactor-netty's `HttpTrafficHandler`), which request each side is on, and every
 * thread.
 */
private fun stallReport(): String = buildString {
    append("==== /v3/api-docs has not answered in ").append(STALL_DUMP_AFTER).append(" ====\n")
    append("-- server connections --\n")
    SERVER_CHANNELS.forEach { append(describe(it)) }
    append("-- client connections --\n")
    CLIENT_CHANNELS.forEach { append(describe(it)) }
    append("-- threads --\n")
    append(threadDump())
    append("==== end of stall report ====\n")
}

private fun describe(channel: Channel): String = buildString {
    append(channel).append(" active=").append(channel.isActive).append(" autoRead=")
        .append(channel.config().isAutoRead).append(" loop=").append(field(channel.eventLoop(), "thread")).append('\n')
    channel.pipeline().toMap().values.filter { it.javaClass.simpleName == "HttpTrafficHandler" }.forEach { handler ->
        append("    HttpTrafficHandler")
        listOf("pendingResponses", "persistentConnection", "pipelined", "overflow", "read", "finalizingResponse")
            .forEach { append(' ').append(it).append('=').append(field(handler, it)) }
        append('\n')
    }
    append("    operations=").append(Connection.from(channel)).append('\n')
}

private fun field(target: Any, name: String): Any? = runCatching {
    generateSequence<Class<*>>(target.javaClass) { it.superclass }
        .firstNotNullOf { type -> type.declaredFields.firstOrNull { it.name == name } }
        .apply { isAccessible = true }
        .get(target)
}.getOrElse { "<$it>" }

private fun threadDump(): String =
    java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(true, true).joinToString(
        separator = "",
    ) { info ->
        buildString {
            append('"').append(info.threadName).append("\" ").append(info.threadState)
            info.lockName?.let { append(" on ").append(it) }
            info.lockOwnerName?.let { append(" owned by \"").append(it).append('"') }
            append('\n')
            info.stackTrace.take(STACK_DEPTH).forEach { append("    at ").append(it).append('\n') }
            append('\n')
        }
    }

private const val STACK_DEPTH = 40

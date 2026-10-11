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

package me.ahoo.wow.analytics.server

import com.clickhouse.client.api.Client
import com.mongodb.reactivestreams.client.MongoClients
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.token.TokenConverter
import me.ahoo.test.asserts.assert
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.tck.container.WowTestContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.utility.DockerImageName
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The analytics service end to end (analytics design R5b), on MongoDB and ClickHouse: an admin publishes a dataset
 * through the registry, and each analyst reads the rows of the tenant CoSec authenticated and of the ABAC tags their
 * principal carries, whatever tenant the request declares; an analyst without tags reads nothing.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "wow.kafka.enabled=false",
        "wow.command.bus.type=in_memory",
        "wow.event.bus.type=in_memory",
        "wow.eventsourcing.state.bus.type=in_memory",
        "cosid.machine.distributor.type=manual",
        "cosid.machine.distributor.manual.machine-id=1",
        "cosec.jwt.secret=analytics-integration-secret-of-sufficient-length",
        "wow.dataset-registry.refresh-interval=500ms",
    ],
)
class AnalyticsServerTest {
    companion object {
        private const val TABLES = "analytics_e2e"
        private val DATABASE = "analytics_it_" + UUID.randomUUID().toString().replace("-", "").take(12)
        private val clickhouse: ClickHouseContainer by lazy {
            ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.8.14.39-alpine")).also {
                it.start()
                Client.Builder().addEndpoint(it.httpUrl).setUsername(it.username).setPassword(it.password).build()
                    .use { client ->
                        fun execute(sql: String) = client.query(sql).get(1, TimeUnit.MINUTES).close()
                        execute("CREATE DATABASE IF NOT EXISTS $TABLES")
                        execute(
                            "CREATE TABLE $TABLES.orders (id String, tenant_id String, region String, " +
                                "amount Decimal(18, 2)) ENGINE = MergeTree ORDER BY id",
                        )
                        execute(
                            "INSERT INTO $TABLES.orders VALUES ('o1', 't1', 'east', 10), ('o2', 't1', 'west', 20), " +
                                "('o3', 't2', 'east', 30), ('o4', 't1', 'east', 5)",
                        )
                    }
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.mongodb.uri") { WowTestContainers.mongo.connectionString + "/" + DATABASE }
            registry.add("spring.mongodb.database") { DATABASE }
            registry.add("wow.query.clickhouse.endpoints") { clickhouse.httpUrl }
            registry.add("wow.query.clickhouse.username") { clickhouse.username }
            registry.add("wow.query.clickhouse.password") { clickhouse.password }
            registry.add("wow.query.clickhouse.database") { TABLES }
        }

        @JvmStatic
        @AfterAll
        fun cleanUp() {
            MongoClients.create(WowTestContainers.mongo.connectionString).use {
                Mono.from(it.getDatabase(DATABASE).drop()).block()
            }
            clickhouse.stop()
        }
    }

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var tokenConverter: TokenConverter

    private val client: WebTestClient by lazy {
        val port = applicationContext.environment.getRequiredProperty("local.server.port")
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").responseTimeout(Duration.ofSeconds(30)).build()
    }

    private fun token(tenant: String, attributes: Map<String, Any> = emptyMap(), vararg roles: String): String =
        tokenConverter.toToken(
            SimpleTenantPrincipal(
                SimplePrincipal("user-$tenant", roles = roles.toSet(), attributes = attributes),
                SimpleTenant(tenant),
            ),
        ).accessToken

    private val admin by lazy { token("t1", mapOf("abac.region" to "*"), AnalyticsProperties.DEFAULT_ADMIN_ROLE) }

    private fun command(method: HttpMethod, path: String, body: String) =
        client.method(method).uri("/dataset-registry/dataset/analytics.orders/$path")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $admin")
            .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
            .header(CommandHeaders.REQUEST_ID, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()

    private fun publish() {
        command(HttpMethod.POST, "draft", """{"table":"orders"}""").expectStatus().isOk
        command(
            HttpMethod.PUT,
            "revise",
            """
            {"document": {
              "title": "Orders", "identity": "id",
              "scope": { "tenant": "tenant_id", "tags": { "region": "region" } },
              "storage": { "table": "$TABLES.orders" },
              "fields": {
                "id": { "types": ["STRING"], "nullable": false },
                "tenant_id": { "types": ["STRING"], "nullable": false },
                "region": { "types": ["STRING"], "nullable": false },
                "amount": { "types": ["DECIMAL"], "nullable": false }
              }
            }}
            """.trimIndent(),
        ).expectStatus().isOk
        command(
            HttpMethod.PUT,
            "sensitivity",
            """{"fields":{"id":{},"tenant_id":{},"region":{},"amount":{}}}""",
        ).expectStatus().isOk
        command(HttpMethod.POST, "publish", "{}").expectStatus().isOk
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (!published() && System.nanoTime() < deadline) Thread.sleep(200)
        published().assert().isTrue()
    }

    private fun published(): Boolean =
        client.get().uri("/dataset").header(HttpHeaders.AUTHORIZATION, "Bearer $admin").exchange()
            .expectStatus().isOk.expectBody(JsonNode::class.java).returnResult().responseBody!!
            .values().any { it.get("contextName").asString() == "analytics" && it.get("name").asString() == "orders" }

    private fun count(token: String, path: String = "/dataset/analytics/orders/count"): WebTestClient.ResponseSpec =
        client.post().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"op":"MATCH_ALL"}""").exchange()

    @Test
    fun `an analyst reads the rows of their tenant and tags, and nothing without tags`() {
        publish()
        val east = mapOf("abac.region" to "east")
        count(token("t1", east)).expectStatus().isOk.expectBody().json("2")
        // The tenant is the one CoSec authenticated, not the one the path declares.
        count(token("t1", east), "/tenant/t2/dataset/analytics/orders/count").expectStatus().isOk
            .expectBody().json("2")
        count(token("t2", east)).expectStatus().isOk.expectBody().json("1")
        count(token("t1", mapOf("abac.region" to "west,east"))).expectStatus().isOk.expectBody().json("3")
        count(token("t1")).expectStatus().isForbidden
        count(token("t1", east).dropLast(2) + "xx").expectStatus().isUnauthorized
    }

    @Test
    fun `an analyst reaches their own views and the tenant's shared ones, not another's`() {
        val analyst = token("t1", mapOf("abac.region" to "east"))
        fun list(path: String) = client.post().uri("/view-store/tenant/$path/view/snapshot/list")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $analyst")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"condition":{"operator":"ALL"}}""").exchange()
            .returnResult(String::class.java).status.value()
        list("t1/owner/user-t1").assert().isNotIn(401, 403)
        list("t1/owner/(shared)").assert().isNotIn(401, 403)
        list("t1/owner/someone-else").assert().isEqualTo(403)
        list("t2/owner/user-t1").assert().isEqualTo(403)
    }
}

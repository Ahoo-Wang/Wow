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

package me.ahoo.wow.datasetregistry.starter

import com.clickhouse.client.api.Client
import com.mongodb.reactivestreams.client.MongoClients
import me.ahoo.test.asserts.assert
import me.ahoo.wow.rest.CommandHeaders
import me.ahoo.wow.tck.container.WowTestContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
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
 * The whole registry in one host (dataset platform design R1): a dataset drafted from a ClickHouse table, reviewed and
 * published through the registry's command routes, is served on the dataset routes once the projection reads it, and
 * stops being served when retired. Events and snapshots are on MongoDB.
 */
@SpringBootTest(
    classes = [DatasetRegistryHostApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.application.name=analytics-service",
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
        "wow.query.clickhouse.enabled=true",
        "wow.dataset-registry.refresh-interval=500ms",
    ],
)
class DatasetRegistryHostTest {
    companion object {
        private const val TABLES = "registry_e2e"
        private val DATABASE = "registry_it_" + UUID.randomUUID().toString().replace("-", "").take(12)
        private val clickhouse: ClickHouseContainer by lazy {
            ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.8.14.39-alpine")).also {
                it.start()
                Client.Builder().addEndpoint(it.httpUrl).setUsername(it.username).setPassword(it.password).build()
                    .use { client ->
                        fun execute(sql: String) = client.query(sql).get(1, TimeUnit.MINUTES).close()
                        execute("CREATE DATABASE IF NOT EXISTS $TABLES")
                        execute(
                            "CREATE TABLE $TABLES.orders (id String, tenant_id String, city String, " +
                                "amount Decimal(18, 2)) ENGINE = MergeTree ORDER BY id",
                        )
                        execute(
                            "INSERT INTO $TABLES.orders VALUES ('o1', 't1', 'Hangzhou', 10), " +
                                "('o2', 't1', 'Beijing', 20), ('o3', 't2', 'Hangzhou', 30)",
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

    private val client: WebTestClient by lazy {
        val port = applicationContext.environment.getRequiredProperty("local.server.port")
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").responseTimeout(Duration.ofSeconds(30)).build()
    }

    private fun command(method: String, path: String, body: String): WebTestClient.ResponseSpec =
        client.method(org.springframework.http.HttpMethod.valueOf(method)).uri("/dataset-registry/dataset/$path")
            .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
            .header(CommandHeaders.REQUEST_ID, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()

    private fun directory(): List<String> =
        client.get().uri("/dataset").exchange().expectStatus().isOk
            .expectBody(JsonNode::class.java).returnResult().responseBody!!
            .values().map { it.get("contextName").asString() + "." + it.get("name").asString() }

    private fun awaitDirectory(expected: Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (("analytics.orders" in directory()) != expected && System.nanoTime() < deadline) Thread.sleep(200)
        ("analytics.orders" in directory()).assert().isEqualTo(expected)
    }

    @Test
    fun `a dataset drafted, reviewed and published is served, and stops being served when retired`() {
        command("POST", "analytics.orders/draft", """{"table":"orders"}""").expectStatus().isOk
        command("POST", "analytics.orders/publish", "{}").expectStatus().is4xxClientError
        command(
            "PUT",
            "analytics.orders/sensitivity",
            """{"fields":{"id":{},"tenant_id":{},"city":{"level":"display","keepPrefix":1}}}""",
        ).expectStatus().isOk
        command("POST", "analytics.orders/publish", "{}").expectStatus().isOk

        awaitDirectory(true)
        client.post().uri("/tenant/t1/dataset/analytics/orders/count").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"op":"match-all"}""").exchange()
            .expectStatus().isOk.expectBody().json("2")
        client.post().uri("/tenant/t1/dataset/analytics/orders/list").contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue("""{"filter":{"op":"eq","field":"id","value":"o1"}}""").exchange()
            .expectStatus().isOk.expectBody().jsonPath("\$[0].city").isEqualTo("H*******")

        command("POST", "analytics.orders/retire", "{}").expectStatus().isOk
        awaitDirectory(false)
        client.get().uri("/dataset/analytics/orders/schema").exchange().expectStatus().isNotFound
    }
}

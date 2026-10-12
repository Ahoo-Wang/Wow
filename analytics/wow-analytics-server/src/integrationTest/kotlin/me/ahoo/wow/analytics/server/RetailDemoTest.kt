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
import org.assertj.core.data.Offset
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The demo of `analytics/demo` end to end: the retailer's wide table its `retail.sql` makes, published through the
 * registry with its declaration and its sensitivity review exactly as the demo's script does, and the questions an
 * analyst asks of it — the top cities of each province, each month against the same month a year before, each
 * channel's share — answered through the service as ClickHouse itself answers them in SQL. The demo's files are
 * read where they are, so a change that breaks the demo breaks this test.
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
        "cosec.jwt.secret=analytics-retail-demo-secret-of-sufficient-length",
        "wow.dataset-registry.refresh-interval=500ms",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetailDemoTest {
    companion object {
        private val DEMO = File("../demo")
        private val DATABASE = "analytics_demo_" + UUID.randomUUID().toString().replace("-", "").take(12)
        private val clickhouse: ClickHouseContainer by lazy {
            ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.8.14.39-alpine")).also {
                it.start()
                sql(it) { execute -> statements(File(DEMO, "clickhouse/retail.sql").readText()).forEach(execute) }
            }
        }

        /** The statements of a SQL script: its comments dropped, split where a line ends a statement. */
        private fun statements(script: String): List<String> =
            script.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                .split(Regex(";\\s*(\\n|$)")).map(String::trim).filter(String::isNotEmpty)

        private fun <T> sql(container: ClickHouseContainer, block: ((String) -> Unit) -> T): T =
            Client.Builder().addEndpoint(container.httpUrl).setUsername(container.username)
                .setPassword(container.password).build().use { client ->
                    block { statement -> client.query(statement).get(1, TimeUnit.MINUTES).close() }
                }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.mongodb.uri") { WowTestContainers.mongo.connectionString + "/" + DATABASE }
            registry.add("spring.mongodb.database") { DATABASE }
            registry.add("wow.query.clickhouse.endpoints") { clickhouse.httpUrl }
            registry.add("wow.query.clickhouse.username") { clickhouse.username }
            registry.add("wow.query.clickhouse.password") { clickhouse.password }
            registry.add("wow.query.clickhouse.database") { "retail" }
        }
    }

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var tokenConverter: TokenConverter

    private val client: WebTestClient by lazy {
        val port = applicationContext.environment.getRequiredProperty("local.server.port")
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").responseTimeout(Duration.ofSeconds(60)).build()
    }

    private fun token(regions: String, vararg roles: String): String = tokenConverter.toToken(
        SimpleTenantPrincipal(
            SimplePrincipal("user-$regions", roles = roles.toSet(), attributes = mapOf("abac.region" to regions)),
            SimpleTenant("demo"),
        ),
    ).accessToken

    private val admin by lazy { token("*", AnalyticsProperties.DEFAULT_ADMIN_ROLE) }
    private val everywhere by lazy { token("east,north,south,west") }

    private fun command(method: HttpMethod, action: String, body: String) {
        client.method(method).uri("/dataset-registry/dataset/retail.order_lines/$action")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $admin")
            .header(CommandHeaders.WAIT_STAGE, "SNAPSHOT")
            .header(CommandHeaders.REQUEST_ID, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk
    }

    /** What the demo's script does: draft from the table, revise, review, publish, and wait for the directory. */
    @BeforeAll
    fun publish() {
        command(HttpMethod.POST, "draft", """{"table":"retail.order_lines"}""")
        command(
            HttpMethod.PUT,
            "revise",
            """{"document": ${File(DEMO, "dataset/retail.order_lines.json").readText()}}""",
        )
        command(HttpMethod.PUT, "sensitivity", File(DEMO, "dataset/retail.order_lines.sensitivity.json").readText())
        command(HttpMethod.POST, "publish", "{}")
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (!published() && System.nanoTime() < deadline) Thread.sleep(200)
        published().assert().isTrue()
    }

    @AfterAll
    fun cleanUp() {
        MongoClients.create(WowTestContainers.mongo.connectionString).use {
            Mono.from(it.getDatabase(DATABASE).drop()).block()
        }
        clickhouse.stop()
    }

    private fun published(): Boolean =
        client.get().uri("/dataset").header(HttpHeaders.AUTHORIZATION, "Bearer $admin").exchange()
            .expectStatus().isOk.expectBody(JsonNode::class.java).returnResult().responseBody!!
            .values().any { it.get("contextName").asString() == "retail" && it.get("name").asString() == "order_lines" }

    private fun aggregate(query: String, token: String = everywhere): List<JsonNode> =
        client.post().uri("/dataset/retail/order_lines/aggregation")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).bodyValue(query).exchange()
            .expectStatus().isOk.expectBody(JsonNode::class.java).returnResult().responseBody!!.values().toList()

    /** Rows of a SQL query, each a tab-separated line, as ClickHouse answers it. */
    private fun select(query: String): List<List<String>> =
        Client.Builder().addEndpoint(clickhouse.httpUrl).setUsername(clickhouse.username)
            .setPassword(clickhouse.password).build().use { client ->
                client.query("$query FORMAT TabSeparated").get(1, TimeUnit.MINUTES).use { response ->
                    response.inputStream.bufferedReader().readLines().filter(String::isNotEmpty)
                        .map { it.split('\t') }
                }
            }

    private fun JsonNode.money(alias: String): BigDecimal = get(alias).decimalValue().setScale(2, RoundingMode.HALF_UP)

    private fun money(value: String): BigDecimal = BigDecimal(value).setScale(2, RoundingMode.HALF_UP)

    private val gmv =
        """{"type":"NUMERIC","alias":"gmv","function":"SUM","expression":{"type":"FIELD","field":"gmv"}}"""

    @Test
    fun `the top two cities of each province, by a rank Wow computes before the having`() {
        val rows = aggregate(
            """
            {"groupBy": [{"type":"TERMS","field":"province","alias":"province"},
                         {"type":"TERMS","field":"city","alias":"city"}],
             "metrics": [$gmv,
                         {"type":"DERIVED","alias":"gmv__rank",
                          "expression":{"type":"RANK","metric":"gmv","within":["province"]}}],
             "having": {"type":"CONDITION","metric":"gmv__rank","operator":"LTE","value":2},
             "sort": [{"field":"province","direction":"ASC"},{"field":"gmv","direction":"DESC"}],
             "limit": 100}
            """.trimIndent(),
        )
        val expected = select(
            """
            SELECT province, city, sum(gmv) AS total FROM retail.order_lines
            GROUP BY province, city
            QUALIFY dense_rank() OVER (PARTITION BY province ORDER BY total DESC) <= 2
            ORDER BY province, total DESC
            """.trimIndent(),
        )
        rows.map { listOf(it.get("province").asString(), it.get("city").asString(), it.money("gmv")) }.assert()
            .isEqualTo(expected.map { listOf(it[0], it[1], money(it[2])) })
        // 广东 has four cities and keeps two; 北京 has one and keeps it.
        rows.count { it.get("province").asString() == "广东" }.assert().isEqualTo(2)
        rows.count { it.get("province").asString() == "北京" }.assert().isEqualTo(1)
    }

    @Test
    fun `each month against the same month a year before`() {
        val rows = aggregate(
            """
            {"groupBy": [{"type":"DATE_HISTOGRAM","field":"order_date","alias":"month","unit":"MONTH"}],
             "metrics": [$gmv,
                         {"type":"DERIVED","alias":"gmv__previousYear",
                          "expression":{"type":"PREVIOUS","metric":"gmv","along":"month","period":"YEAR"}}],
             "sort": [{"field":"month","direction":"ASC"}],
             "limit": 100}
            """.trimIndent(),
        )
        val byMonth = select(
            "SELECT toStartOfMonth(order_date) AS month, sum(gmv) FROM retail.order_lines " +
                "GROUP BY month ORDER BY month",
        )
        rows.size.assert().isEqualTo(byMonth.size)
        // Two years and a part: the first twelve months have no year before, the rest do, and each is the value
        // twelve rows up.
        rows.take(12).forEach { it.get("gmv__previousYear").isNull.assert().isTrue() }
        rows.drop(12).forEachIndexed { index, row ->
            row.money("gmv__previousYear").assert().isEqualTo(money(byMonth[index][1]))
            row.money("gmv").assert().isEqualTo(money(byMonth[index + 12][1]))
        }
        // The table grows about 15% a year: a whole month a year on is 10% to 20% up.
        val month = rows[rows.size - 2]
        val growth = month.money("gmv").divide(month.money("gmv__previousYear"), 4, RoundingMode.HALF_UP)
        growth.assert().isBetween(BigDecimal("1.10"), BigDecimal("1.20"))
    }

    @Test
    fun `each channel's share of the whole`() {
        val rows = aggregate(
            """
            {"groupBy": [{"type":"TERMS","field":"channel","alias":"channel"}],
             "metrics": [$gmv,
                         {"type":"DERIVED","alias":"share",
                          "expression":{"type":"BINARY","operator":"DIVIDE",
                                        "left":{"type":"METRIC_REF","metric":"gmv"},
                                        "right":{"type":"TOTAL","metric":"gmv"}}}],
             "sort": [{"field":"gmv","direction":"DESC"}]}
            """.trimIndent(),
        )
        val expected = select(
            """
            SELECT channel, toFloat64(sum(gmv)) / (SELECT toFloat64(sum(gmv)) FROM retail.order_lines) AS share
            FROM retail.order_lines GROUP BY channel ORDER BY share DESC
            """.trimIndent(),
        )
        rows.map { it.get("channel").asString() }.assert().isEqualTo(expected.map { it[0] })
        rows.zip(expected).forEach { (row, sql) ->
            row.get("share").doubleValue().assert().isCloseTo(sql[1].toDouble(), Offset.offset(1e-6))
        }
        rows.sumOf { it.get("share").doubleValue() }.assert().isCloseTo(1.0, Offset.offset(1e-6))
    }

    @Test
    fun `an analyst of the east reads the east alone`() {
        val count = """{"metrics":[{"type":"COUNT","alias":"lines"}],""" +
            """"groupBy":[{"type":"TERMS","field":"region","alias":"region"}]}"""
        val east = aggregate(count, token("east"))
        east.map { it.get("region").asString() }.assert().containsExactly("east")
        east.single().get("lines").longValue().assert()
            .isEqualTo(select("SELECT count() FROM retail.order_lines WHERE region = 'east'").single()[0].toLong())
        aggregate(count).map { it.get("region").asString() }.assert()
            .containsExactlyInAnyOrder("east", "north", "south", "west")
    }

    @Test
    fun `a member's phone reads masked`() {
        val rows = client.post().uri("/dataset/retail/order_lines/list")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $everywhere")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"filter":{"op":"MATCH_ALL"},"limit":5}""")
            .exchange()
            .expectStatus().isOk.expectBody(JsonNode::class.java).returnResult().responseBody!!.values().toList()
        rows.size.assert().isEqualTo(5)
        rows.forEach { it.get("member_phone").asString().assert().matches("^1\\d{2}\\*+\\d{4}$") }
    }
}

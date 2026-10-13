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

package me.ahoo.wow.example.eshop.e2e

import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.eshop.catalog.server.CatalogServer
import me.ahoo.wow.example.eshop.catalog.server.PostgresDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID

/**
 * The catalog service on PostgreSQL (work package E.1): the storefront's queries run on the PostgreSQL snapshot
 * backend, which builds the indexes the product's state declares with `@QueryIndex`. The same queries run on the
 * memory backend in `:eshop-catalog-server` (`CatalogQueryContract`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogPostgresTest {
    private val postgres = PostgreSQLContainer("postgres:17-alpine").also { it.start() }
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val context = SpringApplicationBuilder(CatalogServer::class.java)
        .profiles(PostgresDataSource.PROFILE)
        .run(
            "--spring.main.banner-mode=off",
            "--spring.application.name=eshop-catalog-service",
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--wow.service=eshop-catalog-service",
            "--wow.backend=postgres",
            "--wow.log.partitions=4",
            "--wow.internal.host=127.0.0.1",
            "--wow.internal.port=0",
            // The test's class path holds the basket's domain too (EshopSharedJvmTest).
            "--wow.contexts.include=eshop-catalog",
        )
    private val port = context.environment.getRequiredProperty("local.server.port", Int::class.java)

    @AfterAll
    fun close() {
        try {
            context.close()
            http.shutdownNow()
        } finally {
            postgres.stop()
        }
    }

    @Test
    fun `the published products of a category should be paged cheapest first`() {
        val category = create("/eshop-catalog/category/create-category", """{"name":"Mugs ${UUID.randomUUID()}"}""")
        val brand = create("/eshop-catalog/brand/create-brand", """{"name":"Wow ${UUID.randomUUID()}"}""")
        val byPrice = listOf("12.00", "5.00", "8.50", "3.25", "9.99").associateWith { price ->
            create(
                "/eshop-catalog/product/create-product",
                """{"name":"Mug at $price","price":$price,"brandId":"$brand","categoryId":"$category"}""",
            ).also { post("/eshop-catalog/product/$it/publish-product", "{}").assertOk() }
        }.toSortedMap(compareBy { it.toBigDecimal() }).values.toList()
        // A draft of the category stays off the storefront.
        create("/eshop-catalog/product/create-product", """{"name":"Draft","price":1,"categoryId":"$category"}""")
        val published = """{"op":"AND","operands":[
            {"op":"EQ","field":"state.categoryId","value":"$category"},
            {"op":"EQ","field":"state.status","value":"PUBLISHED"}]}"""
        val pages = (1..3).map { index ->
            post(
                PRODUCTS,
                """{"kind":"paged","filter":$published,"sort":[{"field":"state.price","direction":"ASC"}],
                    "pagination":{"index":$index,"size":2}}""",
            ).assertOk().tree
        }
        pages.map { it["total"].asLong() }.assert().containsOnly(5L)
        pages.flatMap { page -> page["list"].values().map { it["aggregateId"].asString() } }
            .assert().containsExactlyElementsOf(byPrice)
    }

    @Test
    fun `the snapshot table should carry the storefront's indexes`() {
        // The backend prefixes each index with its table and shortens a long name with a hash, so the definitions,
        // not the Manifest's index names, say which keys each index leads with.
        val definitions = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { db ->
            db.prepareStatement("SELECT indexdef FROM pg_indexes WHERE tablename = ? AND indexname LIKE ?").use {
                it.setString(1, "wow_snapshot__eshop_catalog__product")
                it.setString(2, "%ix_product_state%")
                it.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
            }
        }
        definitions.assert().hasSize(4)
        definitions.map { definition -> KEYS.filter { it in definition } }.assert().containsExactlyInAnyOrder(
            listOf("categoryId", "status", "price"),
            listOf("brandId", "status", "price"),
            listOf("status", "price"),
            listOf("price"),
        )
    }

    private fun create(path: String, body: String): String = post(path, body).assertOk().tree["ref"]["id"].asString()

    private fun post(path: String, body: String): Answer {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(TIMEOUT)
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.body())
    }

    private fun Answer.assertOk(): Answer = also { status.assert().describedAs(body).isEqualTo(OK) }

    private val Answer.tree: JsonNode get() = json.readTree(body)

    private companion object {
        const val OK = 200
        const val PRODUCTS = "/eshop-catalog/product/snapshot/query"
        val KEYS = listOf("categoryId", "brandId", "status", "price")
        val TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

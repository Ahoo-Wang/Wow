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

// Outside the server's package, so that the server's component scan finds none of these classes in its other tests.
package me.ahoo.wow.example.eshop.catalog.springdoc

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import me.ahoo.test.asserts.assert
import me.ahoo.wow.example.eshop.catalog.server.CatalogNode
import me.ahoo.wow.example.eshop.catalog.server.startCatalog
import me.ahoo.wow.openapi.OpenApiSecurityScheme
import me.ahoo.wow.spring.boot.DevelopmentCallerResolver
import me.ahoo.wow.spring.boot.WowOpenApiSecurity
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.time.Duration

/**
 * The springdoc bridge on the catalog service (decision D233, roadmap P.19): `/v3/api-docs` lists the catalog's Wow
 * routes beside an application controller and is an OpenAPI 3.1 document, the `wow` group lists the Wow routes alone,
 * Swagger UI opens with both and sends the caller's header, and a controller that takes a Wow route fails the startup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogSpringdocTest {
    private lateinit var catalog: CatalogNode

    @BeforeAll
    fun start() {
        // The memory backend serves its query models, the events among them, only when asked to (review R3.4-1).
        catalog = startCatalog(sources = listOf(StorefrontController::class.java, CatalogOpenApi::class.java),
        )
    }

    @AfterAll
    fun stop() {
        catalog.close()
    }

    @Test
    fun `the default document should list the catalog's routes and the application's controller`() {
        val document = catalog.get(API_DOCS).assertOk().json
        val paths = pathsOf(document)
        paths.assert().contains(
            "/storefront/{brand}/featured",
            "/eshop-catalog/product/{id}/create-product",
            "/eshop-catalog/product/events/query",
            "/wow/openapi",
        )
        paths.filter { it.startsWith("/wow/internal") }.assert().isEmpty()
        document["info"]["title"].asString().assert().isEqualTo("eshop-catalog-service")
    }

    @Test
    fun `the default document should be an OpenAPI 3_1 document`() {
        val text = catalog.get(API_DOCS).assertOk().json.toString()
        val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(requireNotNull(javaClass.getResourceAsStream(OAS_SCHEMA)) { "$OAS_SCHEMA is missing." })
        val errors = schema.validate(text, InputFormat.JSON)
        errors.assert().describedAs(errors.joinToString("\n")).isEmpty()
    }

    @Test
    fun `the wow group should list the Wow routes alone`() {
        val group = catalog.get("$API_DOCS/wow").assertOk().json
        pathsOf(group).assert().isEqualTo(pathsOf(catalog.get("/wow/openapi").assertOk().json))
        pathsOf(group).assert().doesNotContain("/storefront/{brand}/featured")
    }

    @Test
    fun `the document should declare the caller's header, so that Try it out sends it`() {
        val document = catalog.get(API_DOCS).assertOk().json
        document["components"]["securitySchemes"]["user"]["name"].asString().assert().isEqualTo(
            DevelopmentCallerResolver.USER
        )
        document["security"][0].has("user").assert().isTrue()
    }

    @Test
    fun `Swagger UI should open on both documents`() {
        val client = WebTestClient.bindToServer().baseUrl(
            catalog.baseUrl
        ).responseTimeout(Duration.ofSeconds(30)).build()
        client.get().uri("/swagger-ui.html").exchange().expectStatus().is3xxRedirection
        client.get().uri("/webjars/swagger-ui/index.html").exchange()
            .expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
        val config = catalog.get("$API_DOCS/swagger-config").assertOk().json
        config["urls"].toList().map { it["url"].asString() }.assert().containsExactly(API_DOCS, "$API_DOCS/wow")
        config["urls.primaryName"].asString().assert().isEqualTo("default")
    }

    @Test
    fun `a controller that takes a Wow route should fail the startup`() {
        val failure = assertThrows<Exception> { startCatalog(sources = listOf(ConflictingController::class.java)) }
        generateSequence<Throwable>(failure) { it.cause }.map { it.message.orEmpty() }.joinToString("\n")
            .assert().contains("POST /eshop-catalog/product/{id}/publish-product is a route of both")
    }

    private fun pathsOf(document: JsonNode): List<String> = document["paths"].propertyNames().toList()

    private companion object {
        const val API_DOCS = "/v3/api-docs"

        /** The OpenAPI Initiative's schema of an OpenAPI 3.1 document (https://spec.openapis.org/oas/3.1/schema/2022-10-07, Apache 2.0). */
        const val OAS_SCHEMA = "/openapi/oas-3.1-schema-2022-10-07.json"
    }
}

/** An application controller of the catalog's own, beside its Wow routes. */
@RestController
class StorefrontController {
    @GetMapping("/storefront/{brand}/featured")
    fun featured(@PathVariable brand: String): List<String> = listOf(brand)
}

/** A controller that takes a Wow route of the catalog, its path variable named otherwise. */
@RestController
class ConflictingController {
    @PostMapping("/eshop-catalog/product/{productId}/publish-product")
    fun publish(@PathVariable productId: String): String = productId
}

/** How the catalog's callers present themselves in development (DevelopmentCallerResolver): the `Wow-User` header. */
@Configuration(proxyBeanMethods = false)
class CatalogOpenApi {
    @Bean
    fun catalogOpenApiSecurity(): WowOpenApiSecurity = WowOpenApiSecurity(
        mapOf(
            "user" to OpenApiSecurityScheme.header(
                DevelopmentCallerResolver.USER,
                "The user: a buyer with Wow-Owner, or an employee with Wow-Roles.",
            )
        ),
    )
}

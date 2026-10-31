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

package me.ahoo.wow.spring.boot

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import me.ahoo.test.asserts.assert
import me.ahoo.wow.openapi.OpenApiSecurityScheme
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springdoc.core.models.GroupedOpenApi
import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl
import org.springdoc.core.properties.SwaggerUiConfigProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

/**
 * The springdoc bridge (decision D233): the Wow routes in springdoc's default document beside the application's own
 * controllers, the `wow` group with the Wow routes alone, and a conflict that fails the startup.
 */
class HostSpringdocTest {
    @Test
    fun `the default document should list the application's controllers and the Wow routes`() {
        springdocHost().use { host ->
            val document = host.get(API_DOCS).body!!
            document["openapi"].asString().assert().isEqualTo("3.1.0")
            val paths = document["paths"].propertyNames().toList()
            paths.assert().contains("/accounts/{owner}/summary", "/bank/account/{id}/deposit", "/wow/openapi")
            paths.filter { it.startsWith("/wow/internal") }.assert().isEmpty()
            document["paths"]["/bank/account/{id}/deposit"]["post"]["operationId"].asString()
                .assert().isEqualTo("bank.account.Deposit")
            document["components"]["schemas"].has("wow.CommandResult").assert().isTrue()
            document["components"]["parameters"].has("Wow-Request-Id").assert().isTrue()
            document["tags"].toList().map { it["name"].asString() }.assert().contains("bank.account", "wow")
            // Without an application OpenAPI bean, the document is the service's.
            document["info"]["title"].asString().assert().isEqualTo("bank-service")
            // The anonymous caller: no security scheme.
            document.has("security").assert().isFalse()
        }
    }

    @Test
    fun `the wow group should list the Wow routes alone, those GET wow openapi lists`() {
        springdocHost().use { host ->
            val group = host.get("$API_DOCS/${SpringdocAutoConfiguration.DEFAULT_GROUP}").body!!
            val served = host.get("/wow/openapi").body!!
            pathsOf(group).assert().isEqualTo(pathsOf(served))
            pathsOf(group).assert().doesNotContain("/accounts/{owner}/summary")
        }
    }

    @Test
    fun `the group should be the one the configuration names`() {
        springdocHost("wow.http.openapi.springdoc.group" to "bank-routes").use { host ->
            host.get("$API_DOCS/bank-routes").status.assert().isEqualTo(200)
            host.context.getBean(GroupedOpenApi::class.java).group.assert().isEqualTo("bank-routes")
        }
    }

    @Test
    fun `the application's security schemes should be the document's global security`() {
        springdocHost(sources = listOf(SecuredBank::class.java)).use { host ->
            val document = host.get(API_DOCS).body!!
            document["components"]["securitySchemes"]["user"]["in"].asString().assert().isEqualTo("header")
            document["security"][0].has("user").assert().isTrue()
            val group = host.get("$API_DOCS/${SpringdocAutoConfiguration.DEFAULT_GROUP}").body!!
            group["security"][0].has("user").assert().isTrue()
            // The application's OpenAPI bean keeps its info.
            document["info"]["title"].asString().assert().isEqualTo("Bank")
        }
    }

    @Test
    fun `the development callers' headers should be one security requirement, and the document declare no dialect`() {
        springdocHost("spring.profiles.active" to "dev").use { host ->
            val springdoc = listOf(
                host.get(API_DOCS).body!!,
                host.get("$API_DOCS/${SpringdocAutoConfiguration.DEFAULT_GROUP}").body!!,
            )
            springdoc.forEach { document ->
                // The parts of one identity, sent together, not five alternatives (UI-65).
                document["security"].size().assert().isEqualTo(1)
                document["security"][0].propertyNames().toList().assert().containsExactlyInAnyOrder(
                    "Wow-User",
                    "Wow-Owner",
                    "Wow-Roles",
                    "Wow-Tenant",
                    "Wow-App",
                )
            }
            (springdoc + host.get("/wow/openapi").body!!).forEach { document ->
                // OpenAPI 3.1's default dialect, the one Swagger UI takes without a warning (UI-63).
                document.has("jsonSchemaDialect").assert().isFalse()
            }
        }
    }

    @Test
    fun `a route both define should fail the startup`() {
        val failure = assertThrows<Exception> { springdocHost(sources = listOf(ConflictingController::class.java)) }
        generateSequence<Throwable>(failure) { it.cause }.map { it.message.orEmpty() }.joinToString("\n")
            .assert().contains("POST /bank/account/{id}/deposit is a route of both")
    }

    @Test
    fun `a component both define differently should fail the startup`() {
        val failure = assertThrows<Exception> { springdocHost(sources = listOf(ConflictingComponent::class.java)) }
        generateSequence<Throwable>(failure) { it.cause }.map { it.message.orEmpty() }.joinToString("\n")
            .assert().contains("the component parameters/Wow-Request-Id is defined differently")
    }

    @Test
    fun `the bridge should be off when the configuration turns it off`() {
        startHost(sources = listOf(HostTestApp::class.java, AccountController::class.java)).use { host ->
            host.context.containsBean("wowOpenApiCustomizer").assert().isFalse()
            host.context.getBeansOfType(GroupedOpenApi::class.java).assert().isEmpty()
            pathsOf(host.get(API_DOCS).body!!).assert().containsExactly("/accounts/{owner}/summary")
        }
    }

    @Test
    fun `Swagger UI should list the default document first, unless the application lists its own`() {
        val processor = SpringdocAutoConfiguration.wowSwaggerUiDefaultDocument(MockEnvironment())
        val ui = SwaggerUiConfigProperties()
        processor.postProcessBeforeInitialization(ui, "ui")
        ui.urls.single().url.assert().isEqualTo(API_DOCS)
        ui.urlsPrimaryName.assert().isEqualTo(SpringdocAutoConfiguration.DEFAULT_DOCUMENT)
        val own = SwaggerUiConfigProperties().apply { urls = linkedSetOf(SwaggerUrl("mine", "/mine", "Mine")) }
        processor.postProcessBeforeInitialization(own, "ui")
        own.urls.single().name.assert().isEqualTo("mine")
        val off = SwaggerUiConfigProperties()
        SpringdocAutoConfiguration.wowSwaggerUiDefaultDocument(
            MockEnvironment().withProperty("springdoc.enable-default-api-docs", "false"),
        ).postProcessBeforeInitialization(off, "ui")
        off.urls.isNullOrEmpty().assert().isTrue()
        processor.postProcessBeforeInitialization("other", "other").assert().isEqualTo("other")
    }

    private fun springdocHost(
        vararg properties: Pair<String, Any>,
        sources: List<Class<*>> = emptyList(),
    ): Host = startHost(
        "wow.http.openapi.springdoc.enabled" to true,
        *properties,
        sources = listOf(HostTestApp::class.java, AccountController::class.java) + sources,
    )

    private fun pathsOf(document: JsonNode): List<String> = document["paths"].propertyNames().toList()

    private companion object {
        const val API_DOCS = "/v3/api-docs"
    }
}

/** An application controller beside the Wow routes. */
@RestController
class AccountController {
    @GetMapping("/accounts/{owner}/summary")
    fun summary(@PathVariable owner: String): Map<String, String> = mapOf("owner" to owner)
}

/** An application controller that takes a Wow route, its path variable named otherwise. */
@RestController
class ConflictingController {
    @PostMapping("/bank/account/{accountId}/deposit")
    fun deposit(@PathVariable accountId: String): String = accountId
}

/**
 * An application document that defines a Wow component name otherwise: a parameter, which springdoc keeps unreferenced
 * (it drops the unreferenced schemas before the customizers run).
 */
@Configuration(proxyBeanMethods = false)
class ConflictingComponent {
    @Bean
    fun bankOpenApi(): OpenAPI = OpenAPI().components(
        Components().addParameters(
            "Wow-Request-Id",
            Parameter().name("X-Request-Id").`in`("header").schema(StringSchema())
        ),
    )
}

/** An application that authenticates its callers by a header of its own, and names its document. */
@Configuration(proxyBeanMethods = false)
class SecuredBank {
    @Bean
    fun bankOpenApiSecurity(): WowOpenApiSecurity =
        WowOpenApiSecurity(mapOf("user" to OpenApiSecurityScheme.header("X-User")))

    @Bean
    fun bankOpenApi(): OpenAPI = OpenAPI().info(Info().title("Bank").version("1"))
}

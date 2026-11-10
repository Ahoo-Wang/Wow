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

package me.ahoo.wow.openapi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.openapi.OpenApiFixtures.MANIFEST
import org.junit.jupiter.api.Test

class ManifestOpenApiTest {
    private val document = ManifestOpenApi.write(MANIFEST)

    @Test
    fun `the document should be a well-formed OpenAPI 3_1 document`() {
        OpenApiChecks.check(document)
        document["openapi"].assert().isEqualTo("3.1.0")
        // OpenAPI 3.1's default dialect extends the schemas' 2020-12, and Swagger UI takes no other (UI-63).
        document.containsKey("jsonSchemaDialect").assert().isFalse()
        document.containsKey("servers").assert().isFalse()
    }

    @Test
    fun `info should name the service, or the options' title and no version for a fragment`() {
        info(document).assert().isEqualTo(mapOf("title" to "shop-service", "version" to "1.2.0"))
        val fragment = ManifestOpenApi.write(MANIFEST.copyWith(service = null), OpenApiOptions(title = "Shop"))
        info(fragment).assert().isEqualTo(mapOf("title" to "Shop", "version" to ManifestOpenApi.UNVERSIONED))
        ManifestOpenApi.write(MANIFEST.copyWith(service = ServiceInfo("shop")))
            .let(::info)["version"].assert().isEqualTo(ManifestOpenApi.UNVERSIONED)
    }

    @Test
    fun `tags should be one per aggregate, described by it or by its context, then the framework's`() {
        @Suppress("UNCHECKED_CAST")
        val tags = document["tags"] as List<Map<String, Any?>>
        tags.map { it["name"] }.assert()
            .containsExactly("shop.cart", "shop.order", ManifestOpenApi.FRAMEWORK_TAG)
        tags[0]["description"].assert().isEqualTo("The shop")
        tags[1]["description"].assert().isEqualTo("An order")
    }

    @Test
    fun `a lifecycle body and a command description the Manifest holds should be the document's`() {
        val body = mapOf(
            "type" to "object",
            "properties" to mapOf("spaceId" to mapOf("type" to "string", "minLength" to 1)),
            "required" to listOf("spaceId"),
        )
        val described = MANIFEST.copyWith(
            contexts = MANIFEST.contexts.map { context ->
                context.copyWith(
                    aggregates = context.aggregates.map { aggregate ->
                        aggregate.copyWith(
                            commands = aggregate.commands.map {
                                if (it.name == "PutItem") {
                                    it.copyWith(description = "Puts an item into the cart.")
                                } else {
                                    it
                                }
                            },
                        )
                    },
                )
            },
            defs = MANIFEST.defs + ("wow.lifecycle.TransferSpace" to body),
        )
        val written = ManifestOpenApi.write(described)
        OpenApiChecks.check(written)
        @Suppress("UNCHECKED_CAST")
        val schemas = (written["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>
        schemas["wow.lifecycle.TransferSpace"].assert().isEqualTo(body)
        val transfers = schemas.keys.filter { it.startsWith("wow.lifecycle.TransferSpace") }
        transfers.assert().containsExactly("wow.lifecycle.TransferSpace")
        val delete = schemas["wow.lifecycle.DeleteAggregate"]
        delete.assert().isEqualTo(WowSchemas.LIFECYCLE["wow.lifecycle.DeleteAggregate"])
        @Suppress("UNCHECKED_CAST")
        val put = ((written["paths"] as Map<String, Any?>)["/shop/cart/{id}/put-item"] as Map<String, Any?>)["post"]
            as Map<String, Any?>
        put["description"].toString().assert().startsWith("Puts an item into the cart. Sends `shop.cart.PutItem`.")
    }

    @Test
    fun `the security schemes should be the document's components and its global security`() {
        document.containsKey("security").assert().isFalse()
        @Suppress("UNCHECKED_CAST")
        (document["components"] as Map<String, Any?>).containsKey("securitySchemes").assert().isFalse()
        val secured = ManifestOpenApi.write(
            MANIFEST,
            OpenApiOptions().withSecuritySchemes(
                linkedMapOf(
                    "bearer" to OpenApiSecurityScheme.bearer("JWT", "CoSec's access token."),
                    "user" to OpenApiSecurityScheme.header("X-User"),
                ),
            ),
        )
        OpenApiChecks.check(secured)
        // One requirement naming every scheme: a caller presents them together (UI-65).
        secured["security"].assert().isEqualTo(
            listOf(mapOf("bearer" to emptyList<String>(), "user" to emptyList()))
        )
        @Suppress("UNCHECKED_CAST")
        (secured["components"] as Map<String, Any?>)["securitySchemes"].assert().isEqualTo(
            mapOf(
                "bearer" to mapOf(
                    "type" to "http",
                    "description" to "CoSec's access token.",
                    "scheme" to "bearer",
                    "bearerFormat" to "JWT",
                ),
                "user" to mapOf("type" to "apiKey", "name" to "X-User", "in" to "header"),
            ),
        )
    }

    @Test
    fun `the checks should catch a schema that is not JSON Schema and a dangling reference`() {
        val broken = ManifestOpenApi.write(
            MANIFEST.copyWith(
                defs = MANIFEST.defs + ("sample.Item" to mapOf("type" to 5, "items" to mapOf("\$ref" to "#/\$defs/x"))),
            ),
        )
        OpenApiChecks.problems(broken).assert().anyMatch { it.startsWith("schema sample.Item:") }
            .anyMatch { it == "\$ref #/components/schemas/x resolves to nothing" }
    }

    private fun info(document: Map<String, Any?>): Map<String, Any?> =
        (document["info"] as Map<*, *>).filterKeys { it != "description" }.mapKeys { it.key.toString() }
}

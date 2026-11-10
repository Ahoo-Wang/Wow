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
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.ServiceRouteManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A service route's answers: its body (200), no body (204), or either, when its response is optional; and its
 * refusals, the statuses of the codes it lists, beside the framework's route answers in a route's words.
 */
class ServiceRouteOpenApiTest {
    private fun route(
        name: String,
        response: Map<String, Any?>?,
        responseOptional: Boolean = false,
        errors: List<String> = emptyList(),
    ) = ServiceRouteManifest(
        name = name,
        route = RouteManifest("GET", "/shop/$name"),
        response = response,
        responseOptional = responseOptional,
        errors = errors,
    )

    private val item = Manifest.refSchema("shop.Item")

    private val document = ManifestOpenApi.write(
        Manifest(
            service = ServiceInfo("shop"),
            contexts = listOf(
                ContextManifest(
                    "shop",
                    errors = listOf(
                        ErrorManifest("shop.limit-out-of-range", 400, "A page holds 1 to 50 items."),
                        ErrorManifest("shop.closed", 409, "The shop is closed."),
                        ErrorManifest("shop.cart.checked-out", 409, "The cart is checked out."),
                        // An @ErrorCode that declares no status: 422.
                        ErrorManifest("shop.out-of-stock", 422, "The item is out of stock."),
                    ),
                    routes = listOf(
                        route("find", item, responseOptional = true, errors = listOf("shop.limit-out-of-range")),
                        route("get", item),
                        route("ping", null),
                        route("open", item, errors = listOf("shop.closed")),
                        route("reserve", item, errors = listOf("shop.out-of-stock")),
                        // A framework code among a route's own.
                        route("lookup", item, errors = listOf("wow.aggregate.not-found", "shop.closed")),
                    ),
                ),
            ),
            defs = mapOf("shop.Item" to mapOf("type" to "object")),
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun responses(name: String, of: Map<String, Any?> = document): Map<String, Map<String, Any?>> {
        val paths = of["paths"] as Map<String, Map<String, Map<String, Any?>>>
        return paths.getValue("/shop/$name").getValue("get")["responses"] as Map<String, Map<String, Any?>>
    }

    private fun answers(name: String): Set<String> = responses(name).keys.filter { it.startsWith("2") }.toSet()

    /** The component response each refusal status of the route [name] refers to, by status. */
    private fun refusals(name: String): Map<String, String> =
        responses(name).filterKeys { !it.startsWith("2") }.mapValues { (_, it) -> it.getValue("\$ref").toString() }

    @Suppress("UNCHECKED_CAST")
    private fun component(ref: String): Map<String, Any?> {
        val components = document["components"] as Map<String, Map<String, Map<String, Any?>>>
        return components.getValue("responses").getValue(ref.substringAfterLast('/'))
    }

    @Test
    fun `a route whose response is optional should answer its body or none`() {
        answers("find").assert().containsExactlyInAnyOrder("200", "204")
        answers("get").assert().containsExactly("200")
        answers("ping").assert().containsExactly("204")
    }

    @Test
    fun `a route should list the statuses of its own codes, beside the framework's route answers`() {
        val shared = "#/components/responses/wow.route"
        refusals("find").assert().containsExactlyEntriesOf(
            linkedMapOf(
                "400" to "#/components/responses/shop.route.find.400",
                "401" to "$shared.401",
                "403" to "$shared.403",
            ),
        )
        component(refusals("find").getValue("400"))["x-wow-error-codes"].assert()
            .isEqualTo(listOf("wow.route.malformed", "shop.limit-out-of-range"))
        refusals("open").assert().containsExactlyEntriesOf(
            linkedMapOf(
                "400" to "$shared.400",
                "401" to "$shared.401",
                "403" to "$shared.403",
                "409" to "#/components/responses/shop.route.open.409",
            ),
        )
        component(refusals("open").getValue("409"))["x-wow-error-codes"].assert().isEqualTo(listOf("shop.closed"))
        refusals("get").values.assert().allMatch { it.startsWith("$shared.") }
        // A code of the default status keeps 422, the route's own, with that code alone.
        refusals("reserve")["422"].assert().isEqualTo("#/components/responses/shop.route.reserve.422")
        component(refusals("reserve").getValue("422")).let {
            it["x-wow-error-codes"].assert().isEqualTo(listOf("shop.out-of-stock"))
            it["description"].toString().assert().doesNotContain("command")
        }
    }

    @Test
    fun `a framework code a route lists should be listed at its status`() {
        val lookup = refusals("lookup")
        lookup.keys.assert().containsExactly("400", "401", "403", "404", "409")
        component(lookup.getValue("404"))["x-wow-error-codes"].assert().isEqualTo(listOf("wow.aggregate.not-found"))
        lookup.getValue("409").assert().isEqualTo("#/components/responses/shop.route.lookup.409")
    }

    /** A Manifest of the context `shop` with no codes of its own, whose one route lists [errors]. */
    private fun bare(vararg errors: String) = Manifest(
        service = ServiceInfo("shop"),
        contexts = listOf(ContextManifest("shop", routes = listOf(route("hold", null, errors = errors.toList())))),
    )

    @Test
    fun `a route of a context with no codes should list the framework's it lists, at the host's status`() {
        val configured = ManifestOpenApi.write(
            bare("wow.aggregate.not-found"),
            OpenApiOptions(errorStatuses = mapOf("wow.aggregate.not-found" to 410)),
        )
        responses("hold", configured).keys.assert().containsExactly("204", "400", "401", "403", "410")
    }

    @Test
    fun `a code neither table declares should fail the document, naming the route and the code`() {
        listOf("shop.undeclared", "wow.undeclared").forEach { code ->
            assertThrows<IllegalStateException> { ManifestOpenApi.write(bare(code)) }.message.assert()
                .isEqualTo(
                    "Service route shop.hold refuses with $code, which neither its context nor the framework declares."
                )
        }
    }

    @Test
    fun `a route's refusal should be told in a route's words`() {
        component("#/components/responses/wow.route.403")["description"].toString().assert()
            .contains("may not call this route").doesNotContain("command")
    }
}

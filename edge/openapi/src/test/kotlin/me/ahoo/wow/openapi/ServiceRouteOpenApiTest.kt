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
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.ServiceRouteManifest
import org.junit.jupiter.api.Test

/** A service route's answers: its body (200), no body (204), or either, when its response is optional. */
class ServiceRouteOpenApiTest {
    private fun route(name: String, response: Map<String, Any?>?, responseOptional: Boolean = false) =
        ServiceRouteManifest(
            name = name,
            route = RouteManifest("GET", "/shop/$name"),
            response = response,
            responseOptional = responseOptional,
        )

    private val item = Manifest.refSchema("shop.Item")

    private val document = ManifestOpenApi.write(
        Manifest(
            service = ServiceInfo("shop"),
            contexts = listOf(
                ContextManifest(
                    "shop",
                    routes = listOf(
                        route("find", item, responseOptional = true),
                        route("get", item),
                        route("ping", null)
                    ),
                ),
            ),
            defs = mapOf("shop.Item" to mapOf("type" to "object")),
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun answers(name: String): Set<String> {
        val paths = document["paths"] as Map<String, Map<String, Map<String, Any?>>>
        val responses = paths.getValue("/shop/$name").getValue("get")["responses"] as Map<String, Any?>
        return responses.keys.filter { it.startsWith("2") }.toSet()
    }

    @Test
    fun `a route whose response is optional should answer its body or none`() {
        answers("find").assert().containsExactlyInAnyOrder("200", "204")
        answers("get").assert().containsExactly("200")
        answers("ping").assert().containsExactly("204")
    }
}

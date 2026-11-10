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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/** A service route whose answer may be absent: `responseOptional`, written only when true. */
class ServiceRouteManifestTest {
    private val mapper = jacksonObjectMapper()

    private fun manifest(route: ServiceRouteManifest) = Manifest(
        contexts = listOf(ContextManifest("shop", routes = listOf(route)))
    )

    @Suppress("UNCHECKED_CAST")
    private fun written(manifest: Manifest): Map<String, Any?> =
        (
            (
                ManifestTree.write(
                    manifest
                )["contexts"] as List<Map<String, Any?>>
                ).single()["routes"] as List<Map<String, Any?>>
            )
            .single()

    @Test
    fun `an optional response should be written, read back, and left out when required`() {
        val route = ServiceRouteManifest(
            name = "find",
            route = RouteManifest("GET", "/shop/items/{id}"),
            response = Manifest.refSchema("shop.Item"),
            responseOptional = true,
        )
        val optional = manifest(route)
        written(optional)["responseOptional"].assert().isEqualTo(true)
        ManifestTree.read(mapper.readValue(mapper.writeValueAsString(ManifestTree.write(optional)), Map::class.java))
            .assert().isEqualTo(optional)
        val required = manifest(route.copyWith(responseOptional = false))
        written(required).keys.assert().doesNotContain("responseOptional")
        ManifestTree.read(ManifestTree.write(required)).assert().isEqualTo(required)
    }
}

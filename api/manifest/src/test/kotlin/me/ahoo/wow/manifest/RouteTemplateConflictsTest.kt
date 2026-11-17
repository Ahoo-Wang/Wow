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
import org.junit.jupiter.api.assertThrows

/**
 * Two routes whose templates differ only in their variables' names match the same requests (#6284): the edge would
 * mount both and serve every request with the first, so they conflict as two routes of one path do.
 */
class RouteTemplateConflictsTest {
    private fun route(name: String, path: String, method: String = "GET") =
        ServiceRouteManifest(name, RouteManifest(method, path))

    /** The domain's fragment, with [routes] as its context's service routes. */
    private fun domain(vararg routes: ServiceRouteManifest): ManifestFragment = Manifests.fragment().let { fragment ->
        val context = fragment.contexts.single().copyWith(routes = routes.toList())
        ManifestFragment("domain.json", fragment.copyWith(contexts = listOf(context)))
    }

    private fun conflicts(vararg fragments: ManifestFragment): List<String> =
        assertThrows<ManifestConflictException> { ManifestMerger.merge(fragments.toList()) }.conflicts

    @Test
    fun `routes of one fragment whose variables alone differ should conflict, naming both templates`() {
        conflicts(domain(route("byId", "/shop/lookup/{id}"), route("byKey", "/shop/lookup/{key}"))).assert()
            .containsExactly(
                "routes GET /shop/lookup/{id} and GET /shop/lookup/{key} match the same requests: declared by " +
                    "service route shop.byId (domain.json) as /shop/lookup/{id} and " +
                    "service route shop.byKey (domain.json) as /shop/lookup/{key}",
            )
    }

    @Test
    fun `routes of two fragments whose variables alone differ should conflict`() {
        val context = ContextManifest(name = "shop", routes = listOf(route("byKey", "/shop/lookup/{key}")))
        val server = ManifestFragment("server.json", Manifest(contexts = listOf(context)))
        conflicts(domain(route("byId", "/shop/lookup/{id}")), server).single().assert()
            .contains("service route shop.byKey (domain.json and server.json) as /shop/lookup/{key}")
    }

    @Test
    fun `a service route matching a command's route under other variable names should conflict`() {
        val command = Manifests.fragment().contexts.single().aggregates.single().commands.first().routes.first()
        val renamed = command.path.replace("{id}", "{cartId}")
        conflicts(domain(route("shadow", renamed, method = command.method))).filter { "match the same requests" in it }
            .assert().hasSize(1)
    }

    @Test
    fun `the same template twice should still conflict as before`() {
        conflicts(domain(route("byId", "/shop/lookup/{id}"), route("byKey", "/shop/lookup/{id}"))).assert()
            .containsExactly(
                "route GET /shop/lookup/{id} is declared by service route shop.byId (domain.json) and " +
                    "service route shop.byKey (domain.json)",
            )
    }

    @Test
    fun `other methods, other literal segments and a literal beside a variable should not conflict`() {
        val merged = ManifestMerger.merge(
            listOf(
                domain(
                    route("byId", "/shop/lookup/{id}"),
                    route("putById", "/shop/lookup/{key}", method = "POST"),
                    route("other", "/shop/other/{key}"),
                    route("latest", "/shop/lookup/latest"),
                    route("deeper", "/shop/lookup/{key}/notes"),
                ),
            ),
        )
        merged.contexts.single().routes.map { it.name }.assert()
            .containsExactlyInAnyOrder("byId", "putById", "other", "latest", "deeper")
    }
}

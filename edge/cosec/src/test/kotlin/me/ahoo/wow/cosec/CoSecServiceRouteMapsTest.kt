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

package me.ahoo.wow.cosec

import me.ahoo.cosec.document.route.Route
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.ManifestTree
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.json.JsonMapper
import java.io.File

/**
 * The route map [CoSecActions.routes] makes of each service of the repository, from its Manifest as the build merges it
 * (`manifest/baseline.json`, which the service's `check` holds to that Manifest). No route path ends with `/`: CoSec
 * 8.0.0-beta.8 refuses with 400 a request whose path maps to a route only with, or only without, a trailing `/`
 * (`PIP-35`), so such a route would refuse the path a client builds from the Manifest's. And CoSec loads it, as a host
 * that decides for itself does: from 8.0.0-beta.9 `[ ] ( ) | ! ^ $ \` are reserved in a route's path (`DOC-15`), and a
 * route map that holds one unescaped does not load.
 */
class CoSecServiceRouteMapsTest {
    @TestFactory
    fun `no route of a service's route map should end with a slash`(): List<DynamicTest> = routeMaps { _, routes ->
        routes.filter { it.path.endsWith('/') }.map { "${it.method} ${it.path}" }.assert().isEmpty()
    }

    @TestFactory
    fun `CoSec should load every service's route map`(): List<DynamicTest> = routeMaps { path, routes ->
        RouteMap.of(routes.map { RouteMap.Located(it, path) })
    }

    /** One test per service's Manifest baseline, of the route map [CoSecActions.routes] makes of it. */
    private fun routeMaps(check: (String, List<Route>) -> Unit): List<DynamicTest> {
        val root = File(requireNotNull(System.getProperty(ROOT)) { "System property $ROOT is not set." })
        val baselines = root.walkTopDown()
            .onEnter { it.name !in SKIPPED }
            .filter { it.isFile && it.name == "baseline.json" && it.parentFile.name == "manifest" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .toList()
        baselines.map { it.first }.assert()
            .contains("examples/eshop/ordering/ordering-server/manifest/baseline.json")
            .contains("platform/ops/ops-server/manifest/baseline.json")
        return baselines.map { (path, file) ->
            DynamicTest.dynamicTest(path) {
                val manifest = ManifestTree.read(json.readValue(file, Map::class.java), ManifestTree.REFUSE)
                val routes = CoSecActions.routes(manifest).routes
                routes.assert().isNotEmpty()
                check(path, routes)
            }
        }
    }

    private companion object {
        const val ROOT = "wow-test.repository.root"
        val SKIPPED = setOf("build", "node_modules", ".git", ".gradle", ".claude")
        val json: JsonMapper = JsonMapper.builder().build()
    }
}

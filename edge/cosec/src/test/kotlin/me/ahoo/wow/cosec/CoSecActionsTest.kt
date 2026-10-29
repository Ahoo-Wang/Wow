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

import me.ahoo.cosec.runtime.route.RouteMap
import me.ahoo.cosec.runtime.route.RouteMapCodec
import me.ahoo.cosec.runtime.route.RouteResolution
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.ServiceRouteManifest
import org.junit.jupiter.api.Test

/** [CoSecActions]: one action name for a command's HTTP route and for the command in the process (D367). */
class CoSecActionsTest {
    private val manifest = Manifest(
        service = cartManifest().service,
        contexts = cartManifest().contexts.map { context ->
            ContextManifest(
                name = context.name,
                aggregates = context.aggregates,
                commands = context.commands,
                routes = listOf(ServiceRouteManifest("summary", RouteManifest("GET", "/shop/summaries/{day}"))),
            )
        },
    )
    private val routes = RouteMap.of(
        CoSecActions.routes(manifest).routes.map { RouteMap.Located(it, "cosec-routes.json") }
    )

    private fun resolve(method: String, path: String): String {
        val mapped = routes.resolve(method, path) as RouteResolution.Mapped
        val resource = mapped.resource.let { if (it.id.isEmpty()) it.type else "${it.type}/${it.id}" }
        return "${mapped.action.namespace}:${mapped.action.verb} $resource"
    }

    @Test
    fun `a command route is the command's action on its aggregate`() {
        resolve("POST", "/shop/cart/c-1/add-item").assert().isEqualTo("shop:AddItem cart/c-1")
        resolve("POST", "/shop/cart/c-1/open-cart").assert().isEqualTo("shop:OpenCart cart/c-1")
    }

    @Test
    fun `a route that names no aggregate is on the aggregate type alone`() {
        resolve("POST", "/shop/cart/open-cart").assert().isEqualTo("shop:OpenCart cart")
    }

    @Test
    fun `the system commands' routes are their actions on the aggregates they run on`() {
        resolve("DELETE", "/shop/cart/c-1").assert().isEqualTo("shop:wow.lifecycle.DeleteAggregate cart/c-1")
        resolve("POST", "/shop/cart/wow.snapshot.rebuild").assert().isEqualTo("shop:wow.snapshot.Rebuild snapshot/cart")
        resolve("POST", "/shop/wow.schedule/report/enable").assert().isEqualTo("shop:wow.schedule.Enable schedule/report")
    }

    @Test
    fun `a service route is its action on the route`() {
        resolve("GET", "/shop/summaries/mon").assert().isEqualTo("shop:summary route/summary")
    }

    @Test
    fun `the queries are not mapped, CoSec reads them on their path`() {
        routes.resolve("GET", "/shop/cart/c-1/state").assert().isSameAs(RouteResolution.Unmapped)
    }

    @Test
    fun `the route map is written as CoSec reads it, named after the service`() {
        val document = CoSecActions.routes(manifest)
        document.service.assert().isEqualTo("shop-service")
        val read = RouteMapCodec.decode(RouteMapCodec.encode(document), "cosec-routes.json")
        read.routes.map { "${it.method} ${it.path} ${it.action} ${it.resource}" }.assert()
            .isEqualTo(document.routes.map { "${it.method} ${it.path} ${it.action} ${it.resource}" })
        CoSecActions.routes(Manifest()).service.assert().isEqualTo("wow")
    }
}

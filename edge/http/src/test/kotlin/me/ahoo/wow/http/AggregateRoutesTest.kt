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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.copyWith
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.util.pattern.PathPattern

/** The routes of each aggregate, found by `/{context}/{aggregate}` (roadmap §6 performance item 11). */
class AggregateRoutesTest {
    /** The cart's Manifest with a copy of the cart named [name] beside it, its routes under its own path. */
    private fun withCopy(name: String, extra: RouteManifest? = null): Manifest {
        val base = cartManifest()
        val context = base.contexts.single()
        val cart = context.aggregates.single()
        fun moved(route: RouteManifest) = route.copyWith(path = route.path.replace("/shop/cart/", "/shop/$name/"))
        val copy = cart.copyWith(
            name = name,
            commands = cart.commands.map { it.copyWith(routes = it.routes.map(::moved)) },
            queries = cart.queries.map { it.copyWith(routes = it.routes.map(::moved)) },
        )
        val first = extra?.let { route ->
            cart.copyWith(commands = cart.commands.mapIndexed { at, c -> if (at == 0) c.copyWith(routes = c.routes + route) else c })
        } ?: cart
        return base.copyWith(contexts = listOf(context.copyWith(aggregates = listOf(first, copy))))
    }

    /** The pattern of the route [routes] match [method] [path] with, or null when none does. */
    private fun matched(routes: RouterFunction<ServerResponse>, method: HttpMethod, path: String): String? {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build())
        val request = ServerRequest.create(exchange, HandlerStrategies.withDefaults().messageReaders())
        routes.route(request).block() ?: return null
        return (request.attributes()[RouterFunctions.MATCHING_PATTERN_ATTRIBUTE] as PathPattern?)?.patternString
    }

    @Test
    fun `each aggregate's request matches its own routes`() {
        val routes = HttpNode(manifest = withCopy("wishlist")).routes
        matched(routes, HttpMethod.POST, "/shop/wishlist/1/add-item").assert().isEqualTo("/shop/wishlist/{id}/add-item")
        matched(routes, HttpMethod.POST, "/shop/cart/1/add-item").assert().isEqualTo("/shop/cart/{id}/add-item")
        matched(routes, HttpMethod.GET, "/shop/wishlist/1/state").assert().isEqualTo("/shop/wishlist/{id}/state")
        matched(routes, HttpMethod.DELETE, "/shop/wishlist/1").assert().isEqualTo("/shop/wishlist/{id}")
    }

    @Test
    fun `a path of no aggregate falls through to the context's not-found route`() {
        val routes = HttpNode(manifest = withCopy("wishlist")).routes
        matched(routes, HttpMethod.POST, "/shop/nothing/1/add-item").assert().isEqualTo("/shop/**")
        // A path too short to name an aggregate, too.
        matched(routes, HttpMethod.POST, "/shop").assert().isEqualTo("/shop/**")
    }

    @Test
    fun `a route outside its aggregate's path keeps the routes one list`() {
        val routes = HttpNode(manifest = withCopy("wishlist", RouteManifest("POST", "/shop/custom/open"))).routes
        matched(routes, HttpMethod.POST, "/shop/custom/open").assert().isEqualTo("/shop/custom/open")
        matched(routes, HttpMethod.POST, "/shop/wishlist/1/add-item").assert().isEqualTo("/shop/wishlist/{id}/add-item")
    }
}

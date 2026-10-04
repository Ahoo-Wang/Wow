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

package me.ahoo.wow.benchmark.webflux

import me.ahoo.wow.naming.MaterializedNamedBoundedContext
import me.ahoo.wow.openapi.RouterSpecs
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import me.ahoo.wow.webflux.route.RouteHandlerFunctionRegistrar
import me.ahoo.wow.webflux.route.RouterFunctionBuilder
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.function.server.HandlerFunction
import org.springframework.web.reactive.function.server.HandlerStrategies
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono

/**
 * Request dispatch through the full [RouterFunctionBuilder] router built from the example domain's route catalog
 * (every aggregate, command and query route the starter would register), with no-op handlers so only route matching
 * is measured. The router is a first-match chain, so cost grows with the target's position in dispatch order:
 * - `first`: the first dispatch route;
 * - `last`: the last dispatch route that a request can reach (the worst case for a matching request);
 * - `miss`: a path no route matches (every predicate is evaluated, then 404).
 *
 * Each operation builds its exchange and [ServerRequest], as a server does: a request cannot be routed twice,
 * because a matching path predicate merges its pattern into the request attributes.
 *
 * Audit 9.3.0 D §F9 (design WP G2; the "prefix tree dispatch" decision waits on this).
 */
@State(Scope.Benchmark)
@Suppress("VarCouldBeVal") // JMH injects @Param fields via reflection, so they must be `var`.
open class RouterDispatchBenchmark {
    @Param("first", "last", "miss")
    lateinit var target: String

    private lateinit var routerFunction: RouterFunction<ServerResponse>
    private lateinit var targetRoute: RouteTarget

    @Setup(Level.Trial)
    fun setup() {
        val routerSpecs = RouterSpecs(MaterializedNamedBoundedContext(EXAMPLE_CONTEXT)).build()
        val dispatchRoutes = routerSpecs.toRouteCatalog().dispatchRoutes
        check(dispatchRoutes.size >= MIN_EXAMPLE_ROUTES) {
            "Example domain catalog has only ${dispatchRoutes.size} routes; metadata is missing from the JMH jar."
        }
        val handlers = HashMap<String, HandlerFunction<ServerResponse>>()
        val registrar = RouteHandlerFunctionRegistrar(
            dispatchRoutes.map { it.handlerKey }.distinct().map { handlerKey ->
                MarkerHandlerFunctionFactory(handlerKey, handlers)
            },
        )
        routerFunction = RouterFunctionBuilder(routerSpecs, registrar).build()

        targetRoute = when (target) {
            "first" -> reachableTarget(dispatchRoutes.indices, dispatchRoutes, handlers)
            "last" -> reachableTarget(dispatchRoutes.indices.reversed(), dispatchRoutes, handlers)
            "miss" -> RouteTarget(
                index = dispatchRoutes.size,
                method = HttpMethod.POST,
                path = "/no_such_resource/x1/no_such_action",
                accept = MediaType.APPLICATION_JSON,
                expectedHandler = null,
            )

            else -> error("Unsupported target: $target")
        }
        check(route(targetRoute.toServerRequest()) === targetRoute.expectedHandler) {
            "Route target [$target] did not reach its handler."
        }
        println(
            "RouterDispatchBenchmark: ${dispatchRoutes.size} dispatch routes; target [$target] is " +
                "${targetRoute.method} ${targetRoute.path} at dispatch index ${targetRoute.index}.",
        )
    }

    @Benchmark
    fun dispatch(blackhole: Blackhole) {
        blackhole.consume(route(targetRoute.toServerRequest()))
    }

    /**
     * The request construction [dispatch] includes; route matching cost is `dispatch - buildRequestOnly`.
     */
    @Benchmark
    fun buildRequestOnly(blackhole: Blackhole) {
        blackhole.consume(targetRoute.toServerRequest())
    }

    private fun route(serverRequest: ServerRequest): HandlerFunction<ServerResponse>? =
        routerFunction.route(serverRequest).block()

    /**
     * The first route in [order] whose concrete request is dispatched to that route's own handler (an earlier,
     * overlapping template can capture a request meant for a later one).
     */
    private fun reachableTarget(
        order: IntProgression,
        dispatchRoutes: List<HttpRouteContract>,
        handlers: Map<String, HandlerFunction<ServerResponse>>,
    ): RouteTarget {
        for (index in order) {
            val contract = dispatchRoutes[index]
            val candidate = RouteTarget(
                index = index,
                method = HttpMethod.valueOf(contract.method),
                path = contract.path.replace(PATH_VARIABLE, "x1"),
                accept = MediaType.parseMediaTypes(contract.accept).firstOrNull()?.takeUnless { it.isWildcardType }
                    ?: MediaType.APPLICATION_JSON,
                expectedHandler = checkNotNull(handlers[contract.routeId]),
            )
            if (route(candidate.toServerRequest()) === candidate.expectedHandler) {
                return candidate
            }
        }
        error("No reachable route for target [$target].")
    }

    private class RouteTarget(
        val index: Int,
        val method: HttpMethod,
        val path: String,
        val accept: MediaType,
        val expectedHandler: HandlerFunction<ServerResponse>?,
    ) {
        fun toServerRequest(): ServerRequest {
            val httpRequest = MockServerHttpRequest.method(method, path)
                .header(HttpHeaders.ACCEPT, accept.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .build()
            return ServerRequest.create(MockServerWebExchange.from(httpRequest), MESSAGE_READERS)
        }
    }

    private class MarkerHandlerFunctionFactory(
        override val handlerKey: String,
        private val handlers: MutableMap<String, HandlerFunction<ServerResponse>>,
    ) : HttpRouteHandlerFunctionFactory {
        override fun create(
            contract: HttpRouteContract,
            metadata: HttpRouteHandlerMetadata,
        ): HandlerFunction<ServerResponse> {
            val handler = HandlerFunction<ServerResponse> { OK }
            handlers[contract.routeId] = handler
            return handler
        }
    }

    private companion object {
        const val EXAMPLE_CONTEXT = "example-service"
        const val MIN_EXAMPLE_ROUTES = 100
        val PATH_VARIABLE = Regex("\\{[^}]+}")
        val MESSAGE_READERS = HandlerStrategies.withDefaults().messageReaders()
        val OK: Mono<ServerResponse> = ServerResponse.ok().build()
    }
}

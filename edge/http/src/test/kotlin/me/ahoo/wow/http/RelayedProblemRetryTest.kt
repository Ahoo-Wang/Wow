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
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.route.RouteCall
import me.ahoo.wow.http.problem.GlobalErrors
import me.ahoo.wow.http.route.ServiceRoutes
import me.ahoo.wow.kernel.route.RouteRequest
import me.ahoo.wow.kernel.route.ServiceRouteHandler
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.ServiceRouteManifest
import me.ahoo.wow.manifest.copyWith
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.HttpHandlerConnector
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A problem a service route relays as it was thrown (D359): what a retry may do and its `Retry-After` are the
 * failure's, as an application route answers the same failure, not what the local catalog would say of its code.
 */
class RelayedProblemRetryTest {
    /** The `retry` member and `Retry-After` of the service route's answer, and of an application route's. */
    private fun answers(failure: WowProblemException): Pair<Pair<String, String?>, Pair<String, String?>> {
        val relayed = node(failure).get("/shop/relayed")
        relayed.status.assert().isEqualTo(failure.status)
        relayed.body!!["code"].asString().assert().isEqualTo(failure.code)
        val application = ordinary(failure)
        return (relayed.body!!["retry"].asString() to relayed.headers.getFirst("Retry-After")) to
            (application.body!!["retry"].asString() to application.headers.getFirst("Retry-After"))
    }

    @Test
    fun `a relayed problem should keep its own Retry-After over the catalog's`() {
        val (relayed, application) =
            answers(WowProblemException("wow.command.busy", 429, Retry.SAME_REQUEST, 30.seconds, "Busy upstream."))
        relayed.assert().isEqualTo("same-request" to "30")
        application.assert().isEqualTo(relayed)
    }

    @Test
    fun `a relayed problem that may not be retried should say so, without Retry-After`() {
        val (relayed, application) =
            answers(WowProblemException("wow.node.closing", 503, Retry.NONE, null, "Do not repeat this route."))
        relayed.assert().isEqualTo("none" to null)
        application.assert().isEqualTo(relayed)
    }

    @Test
    fun `a relayed problem of a code the catalog does not know should keep what a retry may do`() {
        val (relayed, application) =
            answers(WowProblemException("partner.temporary-busy", 429, Retry.NEW_REQUEST, 1500.milliseconds, "Later."))
        // Retry-After in whole seconds, rounded up.
        relayed.assert().isEqualTo("new-request" to "2")
        application.assert().isEqualTo(relayed)
    }

    private fun ordinary(failure: WowProblemException): Answer {
        val routes = coRouter { GET("/relayed") { throw failure } }
        val http = WebHttpHandlerBuilder.webHandler(RouterFunctions.toWebHandler(routes))
            .exceptionHandler(GlobalErrors(cartManifest())).build()
        return WebTestClient.bindToServer(HttpHandlerConnector(http)).build().get().uri("/relayed").answer()
    }

    private fun node(failure: WowProblemException): HttpNode {
        val base = cartManifest()
        val manifest = base.copyWith(
            contexts = base.contexts.map {
                it.copyWith(
                    routes = listOf(ServiceRouteManifest("relayed", RouteManifest("GET", "/shop/relayed")))
                )
            }
        )
        val handler = object : ServiceRouteHandler {
            override val context = CONTEXT
            override val name = "relayed"
            override suspend fun handle(call: RouteCall, request: RouteRequest): Any = throw failure
        }
        return HttpNode(manifest = manifest, serviceRoutes = ServiceRoutes(listOf(handler)))
    }
}

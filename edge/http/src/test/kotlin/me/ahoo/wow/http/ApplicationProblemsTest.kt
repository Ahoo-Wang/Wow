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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.route.RouteCall
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.route.ServiceRoutes
import me.ahoo.wow.kernel.route.RouteRequest
import me.ahoo.wow.kernel.route.ServiceRouteHandler
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.ServiceRouteManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryGateway
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import kotlin.time.Duration.Companion.seconds

/**
 * #6510: on Wow's public routes, a failure the framework knows is answered as the application's own routes answer it
 * (`GlobalErrors`), not as a 500 that says nothing: a [WowProblemException] the application's code throws (its caller
 * resolver, a query's backend) by its code, status and retry, and a command a service route's handler sent that was
 * rejected by its result.
 */
class ApplicationProblemsTest {
    /** The application's resolver, which asks an identity provider that is down when the request says so. */
    private val callers = CallerResolver { request ->
        if (request.headers().firstHeader(IDP) != null) throw idpDown()
        headerCallers.resolve(request)
    }

    private fun idpDown() =
        WowProblemException("shop.idp.unavailable", 503, Retry.NEW_REQUEST, 2.seconds, "The identity provider is down.")

    private fun rejected() = CommandRejectedException(
        CommandResult(
            commandId = "c",
            requestId = "r",
            correlationId = "c",
            ref = cartType.ref(TENANT, "c-1"),
            hop = Hop.committed(CONTEXT),
            function = FunctionInfo(FunctionKind.COMMAND, CONTEXT, "cart", "Checkout"),
            status = CommandResult.Status.REJECTED,
            error = ErrorInfo(CART_EMPTY, "The cart is empty."),
            signalTime = 1,
        ),
    )

    private val manifest: Manifest = cartManifest().let { manifest ->
        val context = manifest.contexts.single()
        manifest.copyWith(
            contexts = listOf(
                context.copyWith(
                    routes = listOf(
                        ServiceRouteManifest(name = "checkout", route = RouteManifest("POST", "/$CONTEXT/checkouts")),
                    ),
                ),
            ),
        )
    }

    private val checkout = object : ServiceRouteHandler {
        override val context: String = CONTEXT
        override val name: String = "checkout"
        override suspend fun handle(call: RouteCall, request: RouteRequest): Any? = throw rejected()
    }

    private val node = HttpNode(
        manifest = manifest,
        callers = callers,
        serviceRoutes = ServiceRoutes(listOf(checkout)),
    )

    private fun Answer.assertIdpDown() {
        status.assert().isEqualTo(503)
        contentType.assert().isEqualTo("application/problem+json")
        body!!["code"].asString().assert().isEqualTo("shop.idp.unavailable")
        body["detail"].asString().assert().isEqualTo("The identity provider is down.")
        body["retry"].asString().assert().isEqualTo("new-request")
        headers.getFirst("Retry-After").assert().isEqualTo("2")
    }

    @Test
    fun `an application's Wow problem on a state read, a command and a document route is answered by its code`() {
        node.get("/$CONTEXT/cart/c-1/state", headers = mapOf(IDP to "down")).assertIdpDown()
        node.post("/$CONTEXT/cart/c-1/open-cart", headers = mapOf(IDP to "down")).assertIdpDown()
        node.get("/wow/manifest", headers = mapOf(IDP to "down")).assertIdpDown()
    }

    @Test
    fun `a command a service route's handler sent and was rejected is answered as its result`() {
        val answer = node.post("/$CONTEXT/checkouts")
        answer.status.assert().isEqualTo(409)
        answer.body!!["code"].asString().assert().isEqualTo(CART_EMPTY)
        answer.body["result"]["ref"]["id"].asString().assert().isEqualTo("c-1")
    }

    @Test
    fun `a query whose backend fails with a Wow problem is answered by its code, streamed or not`() {
        val failing = QueryNode(snapshots = { Relaying(it) })
        val answer = failing.query("""{"kind":"list","filter":{"op":"match-all"}}""")
        answer.status.assert().isEqualTo(502)
        answer.body!!["code"].asString().assert().isEqualTo("shop.catalog.unreachable")
        val streamed = failing.stream("""{"kind":"list","filter":{"op":"match-all"},"limit":10}""")
        streamed.events.map { it.event }.assert().containsExactly("row", "error")
        streamed.events.last().data["code"].asString().assert().isEqualTo("shop.catalog.unreachable")
        streamed.events.last().data["status"].asInt().assert().isEqualTo(502)
    }

    /** A backend that relays another service's answer after its first record. */
    private class Relaying(private val delegate: QueryGateway) : QueryGateway by delegate {
        override suspend fun resumes(query: ListQuery): Boolean = false

        override fun list(query: ListQuery, caller: QueryCaller): Flow<ObjectNode> = flow {
            emit(json.createObjectNode().put("aggregateId", "c-1"))
            throw unreachable()
        }

        override suspend fun paged(query: PagedQuery, caller: QueryCaller): PagedList<ObjectNode> = throw unreachable()

        private fun unreachable() = WowProblemException(
            "shop.catalog.unreachable",
            502,
            Retry.SAME_REQUEST,
            null,
            "The catalog is unreachable.",
        )
    }

    private companion object {
        const val IDP = "X-Idp"
    }
}

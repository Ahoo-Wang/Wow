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
import me.ahoo.wow.api.problem.ProblemMapper
import me.ahoo.wow.api.problem.RetryAdvice
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.route.RouteCall
import me.ahoo.wow.api.route.RouteRefusal
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.route.ServiceRoutes
import me.ahoo.wow.kernel.route.RouteRequest
import me.ahoo.wow.kernel.route.ServiceRouteHandler
import me.ahoo.wow.manifest.ErrorManifest
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
 * #6510: the application's [ProblemMapper]s answer its own failures on Wow's public routes (a service route's handler,
 * a state read, a command route, a query, streamed or not), and never what the framework answers itself or a
 * command's failure, which is the command's result.
 */
class ProblemMapperRoutesTest {
    /** A failure of the application's own: its catalog service did not answer. */
    class UpstreamException(message: String) : RuntimeException(message)

    private val upstream = ProblemMapper { failure ->
        (failure as? UpstreamException)?.let {
            WowProblemException(UPSTREAM, 502, RetryAdvice.SAME_REQUEST, 3.seconds, "The catalog did not answer.")
        }
    }

    /** A mapper that claims every failure it is asked about. */
    private val everything = ProblemMapper { WowProblemException(CLAIMED, 418, RetryAdvice.NONE, null, "Claimed.") }

    /** The application's resolver: down, wrapped, when the request says so. */
    private val callers = CallerResolver { request ->
        when (request.headers().firstHeader(IDP)) {
            "upstream" -> throw IllegalStateException("Resolving failed.", UpstreamException("The IdP did not answer."))
            "problem" -> throw WowProblemException(IDP_DOWN, 503, RetryAdvice.NEW_REQUEST, null, "The IdP is down.")
            else -> headerCallers.resolve(request)
        }
    }

    private val manifest: Manifest = cartManifest().let { manifest ->
        val context = manifest.contexts.single()
        manifest.copyWith(
            contexts = listOf(
                context.copyWith(
                    errors = context.errors + ErrorManifest(UPSTREAM, 502, "The catalog is unreachable."),
                    routes = listOf(
                        ServiceRouteManifest(name = "price", route = RouteManifest("GET", "/$CONTEXT/prices/{sku}")),
                    ),
                ),
            ),
        )
    }

    private val price = object : ServiceRouteHandler {
        override val context: String = CONTEXT
        override val name: String = "price"
        override suspend fun handle(call: RouteCall, request: RouteRequest): Any? =
            when (request.path("sku", String::class)) {
                "gone" -> throw RouteRefusal(UPSTREAM, "Refused by the route.")
                else -> throw UpstreamException("The catalog did not answer.")
            }
    }

    private fun node(vararg mappers: ProblemMapper) = HttpNode(
        manifest = manifest,
        callers = callers,
        serviceRoutes = ServiceRoutes(listOf(price)),
        mappers = mappers.toList(),
    )

    @Test
    fun `a service route's handler failing with the application's own exception is answered as its mapper maps it`() {
        val answer = node(upstream).get("/$CONTEXT/prices/sku-1")
        answer.status.assert().isEqualTo(502)
        answer.contentType.assert().isEqualTo("application/problem+json")
        answer.body!!["code"].asString().assert().isEqualTo(UPSTREAM)
        // The title of a code its context lists, the route's context.
        answer.body["title"].asString().assert().isEqualTo("The catalog is unreachable.")
        answer.body["detail"].asString().assert().isEqualTo("The catalog did not answer.")
        answer.body["retry"].asString().assert().isEqualTo("same-request")
        answer.headers.getFirst("Retry-After").assert().isEqualTo("3")
    }

    @Test
    fun `a failure caused by the application's own on a state read or a command is mapped by its cause`() {
        val node = node(upstream)
        val headers = mapOf(IDP to "upstream")
        node.get("/$CONTEXT/cart/c-1/state", headers = headers).body!!["code"].asString().assert().isEqualTo(UPSTREAM)
        val command = node.post("/$CONTEXT/cart/c-1/open-cart", headers = headers)
        command.status.assert().isEqualTo(502)
        command.body!!["code"].asString().assert().isEqualTo(UPSTREAM)
        // Without a mapper, the same failure is a 500 that says nothing of its cause.
        val unmapped = node().get("/$CONTEXT/cart/c-1/state", headers = headers)
        unmapped.status.assert().isEqualTo(500)
        unmapped.body!!.has("code").assert().isFalse()
        unmapped.body.toString().assert().doesNotContain("IdP")
    }

    @Test
    fun `a query failing with the application's own exception is mapped, streamed or not`() {
        val failing = QueryNode(snapshots = { Failing(it) }, mappers = listOf(upstream))
        val answer = failing.query("""{"kind":"list","filter":{"op":"match-all"}}""")
        answer.status.assert().isEqualTo(502)
        answer.body!!["code"].asString().assert().isEqualTo(UPSTREAM)
        val streamed = failing.stream("""{"kind":"list","filter":{"op":"match-all"},"limit":10}""")
        streamed.events.map { it.event }.assert().containsExactly("row", "error")
        streamed.events.last().data["code"].asString().assert().isEqualTo(UPSTREAM)
        // Without a mapper, the query's own answer to a failure nothing knows.
        QueryNode(snapshots = { Failing(it) }).query("""{"kind":"list","filter":{"op":"match-all"}}""")
            .assertQueryProblem(500, FrameworkCodes.QUERY_FAILED)
    }

    @Test
    fun `a decide function's failure stays the command's result, whatever the mappers`() {
        val node = node(everything)
        node.post("/$CONTEXT/cart/c-1/open-cart").status.assert().isEqualTo(200)
        val failed = node.post("/$CONTEXT/cart/c-1/checkout", """{"note":"throw"}""")
        failed.status.assert().isEqualTo(500)
        failed.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.COMMAND_FAILED)
        failed.body["result"]["status"].asString().assert().isEqualTo("failed")
    }

    @Test
    fun `what the framework answers itself is never a mapper's`() {
        val node = node(everything)
        // No credentials: authentication's refusal.
        node.get("/$CONTEXT/cart/c-1/state", user = null).body!!["code"].asString().assert()
            .isEqualTo(FrameworkCodes.AUTH_UNAUTHENTICATED)
        // A Wow problem, by its code.
        node.get("/$CONTEXT/cart/c-1/state", headers = mapOf(IDP to "problem")).body!!["code"].asString().assert()
            .isEqualTo(IDP_DOWN)
        // A route's refusal, by its code.
        node.get("/$CONTEXT/prices/gone").body!!["detail"].asString().assert().isEqualTo("Refused by the route.")
        // A store that is down.
        node.unavailable = true
        val down = node.get("/$CONTEXT/cart/c-1/state")
        down.status.assert().isEqualTo(503)
        down.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.STORAGE_UNAVAILABLE)
    }

    /** A backend whose list fails with the application's own exception after its first record. */
    private class Failing(private val delegate: QueryGateway) : QueryGateway by delegate {
        override suspend fun resumes(query: ListQuery): Boolean = false

        override fun list(query: ListQuery, caller: QueryCaller): Flow<ObjectNode> = flow {
            emit(json.createObjectNode().put("aggregateId", "c-1"))
            throw UpstreamException("The catalog did not answer.")
        }

        override suspend fun paged(query: PagedQuery, caller: QueryCaller): PagedList<ObjectNode> =
            throw UpstreamException("The catalog did not answer.")
    }

    private companion object {
        const val IDP = "X-Idp"
        const val UPSTREAM = "$CONTEXT.catalog.unreachable"
        const val CLAIMED = "$CONTEXT.claimed"
        const val IDP_DOWN = "$CONTEXT.idp.down"
    }
}

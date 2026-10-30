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

import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.problem.Retry
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.route.RouteCall
import me.ahoo.wow.api.route.RouteRefusal
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.route.ServiceRoutes
import me.ahoo.wow.kernel.route.MalformedRouteRequestException
import me.ahoo.wow.kernel.route.RouteRequest
import me.ahoo.wow.kernel.route.ServiceRouteHandler
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.RouteParameterManifest
import me.ahoo.wow.manifest.ServiceRouteManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.RouteAction
import me.ahoo.wow.runtime.admission.ServiceRouteAuthorizer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.reflect.typeOf

/**
 * Decision D359: a context's service routes are mounted from the Manifest, each call admitted as a query's (the
 * caller authenticated, another scope only for a caller that acts across scopes, the authorizers asked) before its
 * handler runs, its answer JSON or `204`, a refusal the problem of its code and a parameter that does not read
 * `400 wow.route.malformed` naming it.
 */
class ServiceRoutesTest {
    data class Note(val text: String)

    enum class Level { LOW, HIGH }

    /** The commands a relaying handler sends; it is only named here. */
    private val client = object : CommandClient {
        override fun of(caller: Caller): CommandClient = this
        override suspend fun send(request: CommandRequest<*>): CommandResult = error("not sent")
        override suspend fun wait(request: CommandRequest<*>): CommandResult = error("not sent")
        override fun stream(request: CommandRequest<*>): Flow<CommandResult> = error("not sent")
        override fun toString(): String = "the node's commands"
    }

    /** The callers a reading handler runs its queries as. */
    private val readers = mutableListOf<Caller>()
    private val queryApi = object : QueryApi {
        override fun of(caller: Caller): Queries = mockk<Queries>(relaxed = true).also { readers += caller }
    }

    private val manifest: Manifest = cartManifest().let { manifest ->
        val context = manifest.contexts.single()
        manifest.copyWith(
            contexts = listOf(
                context.copyWith(
                    errors = context.errors + ErrorManifest(CLOSED, 409, "The day is closed."),
                    routes = listOf(
                        ServiceRouteManifest(
                            name = "summary",
                            route = RouteManifest("GET", "/$CONTEXT/summaries/{day}"),
                            parameters = listOf(
                                RouteParameterManifest(
                                    "day",
                                    RouteParameterManifest.Location.PATH,
                                    mapOf("type" to "string")
                                ),
                                RouteParameterManifest(
                                    "limit",
                                    RouteParameterManifest.Location.QUERY,
                                    mapOf("type" to "integer"),
                                    required = true,
                                ),
                            ),
                            response = Manifest.refSchema("Summary"),
                            errors = listOf(CLOSED),
                        ),
                        ServiceRouteManifest(name = "kinds", route = RouteManifest("GET", "/$CONTEXT/kinds/{n}")),
                        ServiceRouteManifest(name = "relay", route = RouteManifest("POST", "/$CONTEXT/relay")),
                        ServiceRouteManifest(name = "read", route = RouteManifest("GET", "/$CONTEXT/read")),
                        ServiceRouteManifest(name = "peer", route = RouteManifest("POST", "/$CONTEXT/peer/{answer}")),
                        ServiceRouteManifest(
                            name = "addNote",
                            route = RouteManifest("POST", "/$CONTEXT/notes"),
                            request = Manifest.refSchema("Note"),
                        ),
                    ),
                ),
            ),
        )
    }

    /** What the compiler generates for the two routes, by hand. */
    private val notes = mutableListOf<String>()
    private val handlers = listOf(
        handler("summary") { call, request ->
            val day = request.path("day", String::class)
            val limit = request.query("limit", Int::class)
                ?: throw MalformedRouteRequestException("limit", "The query parameter limit is required.")
            when (day) {
                "closed" -> throw RouteRefusal(CLOSED, "The day is closed.", mapOf("day" to day))
                "odd" -> throw RouteRefusal("shop.summary.odd", "Not listed.")
                else -> mapOf("day" to day, "limit" to limit, "tenant" to call.tenantId, "owner" to call.scope.ownerId)
            }
        },
        handler("kinds") { _, request ->
            mapOf(
                "n" to request.path("n", Long::class),
                "d" to request.query("d", Double::class),
                "b" to request.query("b", Boolean::class),
                "level" to request.query("level", Level::class),
            )
        },
        handler("relay") { call, _ -> call.commands.of(call.caller).toString() },
        handler("read") { call, _ -> call.queries.let { "read" } },
        handler("peer") { _, request ->
            when (request.path("answer", String::class)) {
                "gone" -> throw WowProblemException("crm.member.gone", 410, Retry.NONE, null, "Gone.")
                else -> throw RouteRefusal(FrameworkErrors.COMMAND_UNHANDLED, "Not offered.")
            }
        },
        handler("addNote") { _, request ->
            notes += request.body<Note>(typeOf<Note>()).text
            Unit
        },
    )

    private fun handler(name: String, handle: suspend (RouteCall, RouteRequest) -> Any?) = object : ServiceRouteHandler {
        override val context: String = CONTEXT
        override val name: String = name
        override suspend fun handle(call: RouteCall, request: RouteRequest): Any? = handle(call, request)
    }

    private val denyBob = ServiceRouteAuthorizer { caller, action: RouteAction ->
        if (caller.subject == "bob") {
            Authorization.Denied.of(
                "Bob may not read ${action.path}."
            )
        } else {
            Authorization.Allowed
        }
    }

    private val node = HttpNode(
        manifest = manifest,
        serviceRoutes = ServiceRoutes(handlers, listOf(denyBob), client, queryApi),
    )

    @Test
    fun `a route answers its handler's JSON, as the caller in its tenant and scope`() {
        val answer = node.get("/$CONTEXT/summaries/mon?limit=3")
        answer.status.assert().isEqualTo(200)
        answer.body!!["day"].asString().assert().isEqualTo("mon")
        answer.body["limit"].asInt().assert().isEqualTo(3)
        answer.body["tenant"].asString().assert().isEqualTo(TENANT)
        answer.body["owner"].asString().assert().isEqualTo("alice")
    }

    @Test
    fun `a parameter or a body that does not read is malformed, naming it`() {
        val missing = node.get("/$CONTEXT/summaries/mon")
        missing.status.assert().isEqualTo(400)
        missing.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.ROUTE_MALFORMED)
        missing.body["violations"][0]["path"].asString().assert().isEqualTo("limit")
        val notANumber = node.get("/$CONTEXT/summaries/mon?limit=many")
        notANumber.body!!["violations"][0]["path"].asString().assert().isEqualTo("limit")
        val badBody = node.post("/$CONTEXT/notes", """{"text":""")
        badBody.status.assert().isEqualTo(400)
        badBody.body!!["violations"][0]["path"].asString().assert().isEqualTo("body")
    }

    @Test
    fun `a refusal is the problem of its code, 422 when the context lists none`() {
        val closed = node.get("/$CONTEXT/summaries/closed?limit=1")
        closed.status.assert().isEqualTo(409)
        closed.body!!["code"].asString().assert().isEqualTo(CLOSED)
        closed.body["day"].asString().assert().isEqualTo("closed")
        val odd = node.get("/$CONTEXT/summaries/odd?limit=1")
        odd.status.assert().isEqualTo(422)
        odd.body!!["code"].asString().assert().isEqualTo("shop.summary.odd")
    }

    @Test
    fun `a route with a body takes it and answers no content for Unit`() {
        node.post("/$CONTEXT/notes", """{"text":"hello"}""").status.assert().isEqualTo(204)
        notes.assert().containsExactly("hello")
    }

    @Test
    fun `a call is admitted before its handler runs`() {
        node.get("/$CONTEXT/summaries/mon?limit=1", user = null).status.assert().isEqualTo(401)
        node.get("/$CONTEXT/summaries/mon?limit=1", user = "bob").body!!["code"].asString().assert()
            .isEqualTo(FrameworkErrors.AUTH_FORBIDDEN)
        node.get("/$CONTEXT/summaries/mon?limit=1", headers = mapOf(WowHeaders.SCOPE to "owner=\"bob\""))
            .status.assert().isEqualTo(403)
        node.get("/$CONTEXT/summaries/mon?limit=1", headers = mapOf(WowHeaders.CALL to "x")).status.assert()
            .isEqualTo(403)
    }

    @Test
    fun `a route without a handler, or a handler of no route, stops the node`() {
        assertThrows<IllegalStateException> {
            HttpNode(
                manifest = manifest,
                serviceRoutes = ServiceRoutes(handlers.take(1))
            )
        }
            .message.assert().contains("service route $CONTEXT.addNote has no handler")
        assertThrows<IllegalStateException> { HttpNode(serviceRoutes = ServiceRoutes(handlers)) }
            .message.assert().contains("the handler of $CONTEXT.summary is no route of the Manifest")
    }

    @Test
    fun `each scalar a parameter may be reads as its type`() {
        val answer = node.get("/$CONTEXT/kinds/42?d=1.5&b=true&level=HIGH")
        answer.status.assert().isEqualTo(200)
        answer.body!!["n"].asLong().assert().isEqualTo(42L)
        answer.body["d"].asDouble().assert().isEqualTo(1.5)
        answer.body["b"].asBoolean().assert().isTrue()
        answer.body["level"].asString().assert().isEqualTo("HIGH")
        node.get("/$CONTEXT/kinds/42?level=MIDDLE").body!!["violations"][0]["path"].asString().assert()
            .isEqualTo("level")
    }

    @Test
    fun `a caller that acts across scopes calls in the scope it names, and relays as itself`() {
        val answer = node.get(
            "/$CONTEXT/summaries/mon?limit=1",
            headers = mapOf("X-Admin" to "true", WowHeaders.SCOPE to "tenant=\"other\", owner=\"bob\""),
        )
        answer.body!!["tenant"].asString().assert().isEqualTo("other")
        answer.body["owner"].asString().assert().isEqualTo("bob")
        node.post("/$CONTEXT/relay", null).body.toString().assert().contains("the node's commands")
        // Without a command client, a handler that sends one fails.
        HttpNode(manifest = manifest, serviceRoutes = ServiceRoutes(handlers)).post("/$CONTEXT/relay", null)
            .status.assert().isEqualTo(500)
    }

    @Test
    fun `a handler should read as the caller it acts for, in the scope it names`() {
        node.get(
            "/$CONTEXT/read",
            headers = mapOf("X-Admin" to "true", WowHeaders.SCOPE to "tenant=\"other\", owner=\"bob\""),
        ).status.assert().isEqualTo(200)
        readers.single().tenantId.assert().isEqualTo("other")
        readers.single().scope.ownerId.assert().isEqualTo("bob")
        // Without a query API, a handler that reads fails.
        HttpNode(manifest = manifest, serviceRoutes = ServiceRoutes(handlers, commands = client)).get("/$CONTEXT/read")
            .status.assert().isEqualTo(500)
    }

    @Test
    fun `another service's answer passes through, and a framework code takes the framework's status`() {
        val gone = node.post("/$CONTEXT/peer/gone", null)
        gone.status.assert().isEqualTo(410)
        gone.body!!["code"].asString().assert().isEqualTo("crm.member.gone")
        gone.body["detail"].asString().assert().isEqualTo("Gone.")
        val unhandled = node.post("/$CONTEXT/peer/other", null)
        unhandled.status.assert().isEqualTo(400)
        unhandled.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.COMMAND_UNHANDLED)
    }

    @Test
    fun `a route with two handlers stops the node`() {
        assertThrows<IllegalStateException> {
            HttpNode(manifest = manifest, serviceRoutes = ServiceRoutes(handlers + handlers.first()))
        }.message.assert().contains("service route $CONTEXT.summary has two handlers")
    }

    private companion object {
        const val CLOSED = "shop.summary.closed"
    }
}

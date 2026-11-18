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

@file:OptIn(ExperimentalCoSecApi::class) // A pipeline without Spring, as wow-cosec's tests need one.

package me.ahoo.wow.cosec

import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.SecurityScope
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.ProblemEncoder
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.callRoutes
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.wowRoutes
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.wait.Origin
import org.springframework.http.HttpStatusCode
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import me.ahoo.wow.api.decide.Decision as WowDecision
const val CONTEXT = "shop"

data class OpenCart(val note: String = "")
data class AddItem(val sku: String)

class Cart {
    var items = 0
}

data class CartOpened(val note: String)
data class ItemAdded(val sku: String)

val cartModel = aggregate<Cart>(CONTEXT, "cart") {
    initial { Cart() }
    decide<OpenCart>(Creation.REQUIRED) { _, body -> WowDecision.events(CartOpened(body.note)) }
    decide<AddItem> { _, body -> WowDecision.events(ItemAdded(body.sku)) }
    evolve<CartOpened> { }
    evolve<ItemAdded> { items++ }
}

/** The Manifest of [cartModel] as the compiler writes it: its commands, `DeleteAggregate`, its rebuild, and jobs. */
fun cartManifest(): Manifest {
    fun command(name: String, creation: Creation) = CommandManifest(
        name = name,
        schema = Manifest.schemaRef(name),
        creation = creation,
        routes = CommandRoutes.of(CONTEXT, "cart", name, creation),
    )
    return Manifest(
        service = ServiceInfo("shop-service"),
        contexts = listOf(
            ContextManifest(
                name = CONTEXT,
                aggregates = listOf(
                    AggregateManifest(
                        name = "cart",
                        state = StateManifest(Manifest.schemaRef("Cart")),
                        commands = listOf(
                            command("AddItem", Creation.NONE),
                            command("OpenCart", Creation.REQUIRED),
                            SystemCommands.lifecycle(CONTEXT, "cart", SystemCommands.DELETE_AGGREGATE),
                            SystemCommands.rebuild(CONTEXT, "cart"),
                        ),
                    ),
                ),
                commands = SystemCommands.jobs(CONTEXT),
            ),
        ),
        errors = FrameworkErrors.ALL,
    )
}

/** What CoSec is asked: the action (`service:verb`) and the resource (`type/id`) of a request or of a check. */
class Asked(val action: String, val resource: String, val principal: String?, val app: String, val space: String) {
    override fun toString(): String = "$action $resource"
}

/**
 * CoSec's pipeline over a platform document that permits every request: the principal of the bearer token
 * `<id>;<tenant>;<roles>;<app>;<attributes>` (roles comma-separated, `role@space` in a space; the app the token is
 * for; attributes `name=value`, comma-separated; empty parts left out), the route map of [cartManifest], and its
 * [checker], which permits what [permits] says. [asked] records what the pipeline decided and what the checker was
 * asked, in that order.
 */
class CoSecPipeline(
    private val host: String? = null,
    var permits: (Asked) -> Boolean = { true },
) {
    val asked = CopyOnWriteArrayList<Asked>()

    private val documents = InMemorySnapshotDocumentStore().apply {
        put(SnapshotKey.Platform, SnapshotDocuments.read(PERMIT_ALL.toByteArray()))
    }

    private val recorder = object : DecisionListener {
        override val name: String = "asked"

        // Delivered before the outcome, so a test reads what the request was decided as once it is answered.
        override val delivery: Delivery = Delivery.REQUIRED

        override suspend fun onDecision(event: DecisionEvent) {
            val action = event.action ?: return
            val resource = checkNotNull(event.resource)
            asked += Asked(
                "${action.namespace}:${action.verb}",
                if (resource.id.isEmpty()) resource.type else "${resource.type}/${resource.id}",
                event.subject.id.takeIf { event.subject.authenticated },
                event.scope?.app?.value.orEmpty(),
                event.scope?.space?.value.orEmpty(),
            )
        }
    }

    val pipeline: Pipeline = Pipeline.builder({ credentials -> authenticate(credentials) }, documents)
        .routes(
            RouteMap.of(
                CoSecActions.routes(cartManifest()).routes.map { RouteMap.Located(it, "cosec-routes.json") }
            )
        )
        .listener(recorder)
        .build()
        .also { pipeline -> check(runBlocking { pipeline.awaitDocuments() }.isEmpty()) }

    /** CoSec's checker of this pipeline, asked as the authorizers ask it, deciding as [permits] says. */
    val checker: AuthorizationChecker = spyk(AuthorizationChecker(pipeline)).also { checker ->
        coEvery { checker.check(any<SecurityContext>(), any<String>(), any<String>()) } coAnswers {
            val security = firstArg<SecurityContext>()
            val asked = Asked(
                secondArg(),
                thirdArg(),
                security.principal.id.takeIf { security.principal.authenticated },
                security.scope.appId,
                security.scope.spaceId,
            )
            this@CoSecPipeline.asked += asked
            permits(asked) && callOriginal()
        }
    }

    /**
     * CoSec's WebFlux adapter as far as wow-cosec reads it, which only CoSec's starter builds: each request's outcome,
     * its problem written, or its security context put on the exchange and into the Reactor context under
     * [SecurityContext.KEY], where CoSec's filter puts it.
     */
    val filter: WebFilter = WebFilter { exchange, chain ->
        mono { pipeline.evaluate(factsOf(exchange.request)) }.flatMap { outcome -> proceed(exchange, chain, outcome) }
    }

    private fun proceed(exchange: ServerWebExchange, chain: WebFilterChain, outcome: Outcome): Mono<Void> {
        val response = exchange.response
        outcome.headers.forEach { (name, value) -> response.headers.set(name, value) }
        val problem = outcome.problem
        if (problem != null) {
            response.statusCode = HttpStatusCode.valueOf(outcome.status)
            return response.writeWith(Mono.just(response.bufferFactory().wrap(ProblemEncoder.encode(problem))))
        }
        val context = checkNotNull(outcome.security).securityContext
        exchange.attributes[SecurityContext.KEY] = context
        return chain.filter(exchange).contextWrite { it.put(SecurityContext.KEY, context) }
    }

    private fun factsOf(request: ServerHttpRequest) = HttpFacts(
        method = request.method.name(),
        rawPath = request.uri.rawPath,
        rawQuery = request.uri.rawQuery,
        // Each line as it came, a comma kept: CoSec reads a header's lines itself.
        headers = Headers.of(request.headers.headerNames().associateWith { request.headers.get(it).orEmpty() }),
        cookies = Cookies.of(request.cookies.mapValues { (_, cookies) -> cookies.map { it.value } }),
        remoteAddress = "10.0.0.1",
        host = HostScope(host?.let(::AppId)),
    )

    /** The security context the pipeline permits a `POST [path]` with [token] and [headers] in. */
    suspend fun security(
        token: String?,
        headers: Map<String, String> = emptyMap(),
        path: String = "/shop/cart/c-1/add-item",
    ): SecurityContext {
        val all = headers + (token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty())
        val outcome = pipeline.evaluate(
            HttpFacts(
                method = "POST",
                rawPath = path,
                rawQuery = null,
                headers = Headers.of(all.mapValues { listOf(it.value) }),
                cookies = Cookies.of(emptyMap()),
                remoteAddress = "10.0.0.1",
                host = HostScope(host?.let(::AppId)),
            )
        )
        asked.clear()
        return checkNotNull(outcome.security) { "The pipeline refused the request: ${outcome.status}" }
            .securityContext
    }

    private fun authenticate(credentials: Credentials): AuthnResult {
        val token = credentials.token ?: return AuthnResult.Anonymous
        val parts = token.removePrefix("Bearer ").split(';') + List(4) { "" }
        val tenant = parts[1].ifBlank { SecurityContext.DEFAULT_TENANT_ID }
        val attributes = parts[4].split(',').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return AuthnResult.Authenticated(
            AuthenticatedPrincipal(
                PrincipalId(parts[0]),
                TenantId(tenant),
                MembershipClaims.parse(parts[2].split(',').filter(String::isNotBlank), emptyList()),
                attributes,
                app = parts[3].ifBlank { null }?.let(::AppId),
            ),
            "test",
        )
    }

    private companion object {
        /** Every request and check permitted, for anyone, in any app and space. */
        const val PERMIT_ALL = """
            {
              "schema": 2,
              "revision": 1,
              "publishedAt": "2026-11-17T00:00:00Z",
              "policies": [{ "id": "all", "statements": [{ "name": "all", "effect": "allow", "action": "*" }] }],
              "bindings": [{ "id": "all", "subject": "*", "app": "*", "space": "*", "policy": "all" }]
            }
        """
    }
}

/** A security context whose principal [id] no credential proved: one no request of CoSec's pipeline gives a caller. */
fun unproven(id: String, tenant: String = "acme", roles: List<String> = emptyList()): SecurityContext =
    object : SecurityContext {
        override val tenantId = tenant
        override val principal = object : CoSecPrincipal {
            override val id = id
            override val authenticated = false
            override val attributes = emptyMap<String, Any>()
        }
        override val scope = object : SecurityScope {
            override val appId = ""
            override val spaceId = ""
            override val roles = roles
        }
        override val forwardedIdentity = null
    }

/** The caller `alice` of tenant `acme` in space `s1`, as the resolver makes it of `alice;acme;editor@s1`. */
fun editor(): Caller = Caller(
    tenantId = "acme",
    scope = Scope("alice", "s1"),
    subject = "alice",
    attributes = mapOf(CoSecCallerResolver.ROLES to listOf("editor")),
)

/** An in-memory node serving [cartModel] behind [wowRoutes], authenticated by [callers] behind CoSec's [cosec]. */
class CoSecNode(
    callers: CallerResolver,
    val cosec: CoSecPipeline = CoSecPipeline(),
    options: WowHttpOptions = WowHttpOptions(),
    transformers: List<CommandTransformer> = emptyList(),
    authorizer: CommandAuthorizer = CommandAuthorizer.ALLOW_ALL,
) {
    private val registry = WaitRegistry()
    private val coordinator = WaitCoordinator(
        Executor(Kernel(InMemoryEventLog(partitions = 2))),
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender("node-1", registry),
        Origin("node-1", "127.0.0.1:9090", "shop-service"),
        WaitOptions(maxTimeout = 30.seconds),
    )
    private val gateway = CommandGateway(
        ModelRegistry(listOf(cartModel)),
        mapOf(CONTEXT to coordinator),
        authorizer = authorizer,
        transformers = transformers,
    )

    /** The internal port's call routes (decision D193) of this node, their callers from [internalCallers]. */
    fun calls(internalCallers: CallerResolver): WebTestClient =
        WebTestClient.bindToRouterFunction(callRoutes(cartManifest(), gateway, internalCallers)).build()
    val client: WebTestClient = WebTestClient
        .bindToRouterFunction(wowRoutes(cartManifest(), gateway, callerResolver = callers, options = options))
        .webFilter<WebTestClient.RouterFunctionSpec>(cosec.filter)
        .build()
        .mutate()
        .responseTimeout(Duration.ofSeconds(10))
        .build()

    /** Sends `POST [path]` with [headers] and answers the status and the JSON body. */
    fun post(path: String, body: String = "{}", headers: Map<String, String> = emptyMap()): Pair<Int, JsonNode?> {
        val result = client.post().uri(path)
            .headers { http -> headers.forEach { (name, value) -> http.set(name, value) } }
            .header("Content-Type", "application/json")
            .bodyValue(body)
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
        return result.status.value() to result.responseBody?.takeIf { it.isNotBlank() }?.let(json::readTree)
    }
}

val json: JsonMapper = JsonMapper.builder().build()

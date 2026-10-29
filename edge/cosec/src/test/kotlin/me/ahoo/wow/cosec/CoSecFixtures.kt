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

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.route.RouteMap
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision as WowDecision
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
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.WebFilter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import me.ahoo.wow.manifest.Creation as ManifestCreation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

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
    fun command(name: String, creation: ManifestCreation) = CommandManifest(
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
                            command("AddItem", ManifestCreation.NONE),
                            command("OpenCart", ManifestCreation.REQUIRED),
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

/** What the pipeline's engine is asked: the action (`service:verb`) and the resource (`type/id`) of each request. */
class Asked(val action: String, val resource: String, val principal: String?, val app: String, val space: String) {
    override fun toString(): String = "$action $resource"
}

/**
 * CoSec's pipeline with a stand-in for its documents: the principal of the bearer token
 * `<id>;<tenant>;<roles>;<policies>` (roles comma-separated, `role@space` in a space; empty parts left out), the
 * route map of [cartManifest], and an engine that records what it is [asked] and permits what [permits] says.
 */
class CoSecPipeline(
    private val host: String? = null,
    var permits: (Asked) -> Boolean = { true },
) {
    val asked = CopyOnWriteArrayList<Asked>()

    private val engine = object : FlatEngine {
        override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel): FlatEvaluation {
            val authz = request.request
            val resource = authz.resource.let { if (it.id.isEmpty()) it.type else "${it.type}/${it.id}" }
            val asked = Asked(
                "${authz.action.namespace}:${authz.action.verb}",
                resource,
                authz.subject.id.takeIf { authz.subject.authenticated },
                request.scope.app.value,
                request.scope.space.value,
            )
            this@CoSecPipeline.asked += asked
            return FlatEvaluation(if (permits(asked)) PERMIT else Decision.Deny(null), null)
        }
    }

    val pipeline: Pipeline = Pipeline(
        PipelineComponents(
            authenticator = { credentials -> authenticate(credentials.token) },
            snapshots = SuspendingSnapshotSource(InstalledViews),
            engine = engine,
            idGenerator = { "request-1" },
            routes = RouteMap.of(
                CoSecActions.routes(cartManifest()).routes.map { RouteMap.Located(it, "cosec-routes.json") }
            ),
        )
    )

    val checker: AuthorizationChecker = AuthorizationChecker(pipeline)

    val filter: WebFilter = PipelineWebFilter(pipeline, host = HostScope(host?.let(::AppId)))

    /** The security context the pipeline permits a `POST [path]` with [token] and [headers] in, whatever [permits] says. */
    suspend fun security(
        token: String?,
        headers: Map<String, String> = emptyMap(),
        path: String = "/shop/cart/c-1/add-item",
    ): SecurityContext {
        val all = headers + (token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty())
        val permits = this.permits
        this.permits = { true }
        val outcome = try {
            pipeline.evaluate(
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
        } finally {
            this.permits = permits
        }
        asked.clear()
        return checkNotNull(outcome.security) { "The pipeline refused the request: ${outcome.status}" }
            .securityContext
    }

    private fun authenticate(token: String?): AuthnResult {
        token ?: return AuthnResult.Anonymous
        val parts = token.removePrefix("Bearer ").split(';') + List(4) { "" }
        val roles = parts[2].split(',').filter(String::isNotBlank).map {
            val role = it.substringBefore('@')
            val space = it.substringAfter('@', "")
            SpaceId(space) to RoleId(role)
        }
        val memberships = Memberships(
            Memberships.Source.CLAIMS,
            roles.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() },
            parts[3].split(',').filter(String::isNotBlank).map(::PolicyId),
        )
        val tenant = parts[1].ifBlank { Tenant.DEFAULT_TENANT_ID }
        val attributes = parts.getOrNull(4)?.split(',')?.filter { '=' in it }
            ?.associate { it.substringBefore('=') to it.substringAfter('=') }.orEmpty()
        return AuthnResult.Authenticated(
            AuthenticatedPrincipal(PrincipalId(parts[0]), TenantId(tenant), memberships, attributes),
            "test",
        )
    }

    private object InstalledViews : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = viewOf(key)

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(viewOf(key))

        private fun viewOf(key: ViewKey) = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )
    }

    private companion object {
        val PERMIT: Decision = Decision.Permit(
            StatementRef.Policy(PolicyId("test"), 0, "permit", BindingRef.Attached(PolicyId("test")))
        )
    }
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

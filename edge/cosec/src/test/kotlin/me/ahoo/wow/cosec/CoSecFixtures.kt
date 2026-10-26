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
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.authorization.RoleVerifyContext
import me.ahoo.cosec.authorization.RootVerifyContext
import me.ahoo.cosec.authorization.VerifyContext
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import me.ahoo.cosec.context.RequestSecurityContexts.setRequest
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.webflux.ReactiveRemoteIpResolver
import me.ahoo.cosec.webflux.ReactiveRequestParser
import me.ahoo.cosec.webflux.ServerWebExchanges.setSecurityContext
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
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
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import me.ahoo.wow.manifest.Creation as ManifestCreation

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
    decide<OpenCart>(Creation.REQUIRED) { _, body -> Decision.events(CartOpened(body.note)) }
    decide<AddItem> { _, body -> Decision.events(ItemAdded(body.sku)) }
    evolve<CartOpened> { }
    evolve<ItemAdded> { items++ }
}

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
                        ),
                    ),
                ),
            ),
        ),
        errors = FrameworkErrors.ALL,
    )
}

/** A security context as CoSec resolves it: the principal [id] in [tenant], with its roles, policies, attributes. */
fun securityContext(
    id: String,
    tenant: String = Tenant.DEFAULT_TENANT_ID,
    roles: Set<String> = emptySet(),
    policies: Set<String> = emptySet(),
    attributes: Map<String, Any> = emptyMap(),
): SecurityContext {
    val simpleTenant = SimpleTenant(tenant)
    val principal = SimpleTenantPrincipal(SimplePrincipal(id, policies, roles, attributes), simpleTenant)
    return SimpleSecurityContext(principal, simpleTenant)
}

/** A permission of an application's role that grants every action, as CoSec's app role permissions are. */
val appRolePermission: RoleVerifyContext = RoleVerifyContext(
    roleId = "editor",
    permission = PermissionData(id = "app.all", name = "all", action = AllActionMatcher.INSTANCE),
    result = VerifyResult.ALLOW,
)

/** [this] as CoSec's authorization leaves it: the [request] it authorized, allowed by [verified]. */
fun SecurityContext.authorized(request: Request, verified: VerifyContext): SecurityContext {
    setRequest(request)
    setVerifyContext(verified)
    return this
}

/** CoSec's request of [exchange], as its WebFilter parses it. */
fun coSecRequest(exchange: ServerWebExchange): Request = ReactiveRequestParser(ReactiveRemoteIpResolver).parse(exchange)

/**
 * Stands in for CoSec's WebFilter: puts the security context [contexts] gives the request's `X-User` (and `X-Roles`,
 * comma-separated) on the exchange, as CoSec does once it has authenticated and authorized a request: by a permission
 * of its application's roles ([appRolePermission]) when `X-Granted` is `app-role`, by the root otherwise. With
 * `X-Granted: injected` it stands in for CoSec's inject filter instead: the request is on the context, unauthorized.
 */
fun coSecFilter(tenant: String = "acme"): WebFilter = WebFilter { exchange, chain ->
    val headers = exchange.request.headers
    headers.getFirst("X-User")?.let { user ->
        val roles = headers.getFirst("X-Roles")?.split(',')?.toSet().orEmpty()
        val context = securityContext(user, tenant, roles)
        when (headers.getFirst("X-Granted")) {
            "injected" -> context.setRequest(coSecRequest(exchange))
            "app-role" -> context.authorized(coSecRequest(exchange), appRolePermission)
            else -> context.authorized(coSecRequest(exchange), RootVerifyContext)
        }
        exchange.setSecurityContext(context)
    }
    chain.filter(exchange)
}

/** An in-memory node serving [cartModel] behind [wowRoutes], authenticated by [callers] behind [coSecFilter]. */
class CoSecNode(
    callers: CallerResolver,
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
        WaitOptions(Origin("node-1", "127.0.0.1:9090", "shop-service"), maxTimeout = 30.seconds),
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
        .webFilter<WebTestClient.RouterFunctionSpec>(coSecFilter())
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

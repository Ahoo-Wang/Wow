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

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.read.KernelStateReads
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemorySnapshotStore
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.CommandRewriter
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.admission.TenantPolicy
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.wait.Origin
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import me.ahoo.wow.manifest.Creation as ManifestCreation

const val CONTEXT = "shop"
const val TENANT = "acme"

data class OpenCart(val note: String = "")
data class AddItem(val sku: String, val quantity: Int = 1)
data class Checkout(val note: String = "")

class Cart {
    var items = 0
}

data class CartOpened(val note: String)
data class ItemAdded(val sku: String, val quantity: Int)

/** The domain rejection the context's error table lists, with its own status. */
const val CART_EMPTY = "cart.empty"

/** A domain rejection no table lists: 422. */
const val CART_CLOSED = "cart.closed"

val cartModel = aggregate<Cart>(CONTEXT, "cart") {
    initial { Cart() }
    decide<OpenCart>(Creation.REQUIRED) { _, body ->
        putResult("opened", body.note)
        Decision.events(CartOpened(body.note))
    }
    decide<AddItem>(Creation.ALLOWED) { _, body -> Decision.events(ItemAdded(body.sku, body.quantity)) }
    decide<Checkout> { state, body ->
        when {
            body.note == "closed" -> Decision.reject(CART_CLOSED, "The cart is closed.")
            state.items == 0 -> Decision.reject(CART_EMPTY, "The cart is empty.")
            else -> Decision.NoOp
        }
    }
    evolve<CartOpened> { }
    evolve<ItemAdded> { items += it.quantity }
}

/** The Manifest of [cartModel] as the compiler writes it, with the lifecycle commands [lifecycle] lists. */
fun cartManifest(lifecycle: List<String> = Lifecycle.COMMANDS.keys.sorted()): Manifest {
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
                            command("AddItem", ManifestCreation.ALLOWED),
                            command("Checkout", ManifestCreation.NONE),
                            command("OpenCart", ManifestCreation.REQUIRED),
                        ),
                        lifecycle = lifecycle,
                    ),
                ),
                errors = listOf(ErrorManifest(CART_EMPTY, 409, "The cart has no item.")),
            ),
        ),
        errors = FrameworkErrors.ALL,
    )
}

/**
 * Callers from test headers: `X-User` is the owner, in the spaces of `X-Spaces` (comma-separated), the one `Wow-Space`
 * names; `X-Admin` may act across scopes. Without `X-User`, no caller.
 */
val headerCallers = CallerResolver { request ->
    val user = request.headers().firstHeader("X-User") ?: return@CallerResolver null
    val spaces = request.headers().firstHeader("X-Spaces")?.split(',').orEmpty()
    val space = request.headers().firstHeader(WowHeaders.SPACE)?.takeIf { it in spaces }
    Caller(
        tenantId = TENANT,
        scope = Scope(user, space),
        subject = user,
        crossScope = request.headers().firstHeader("X-Admin") != null,
    )
}

/** A projection that never signals: a wait for it times out. */
private val auditDirectory = object : SubscriptionDirectory {
    override fun kindOf(context: String, subscription: String): FunctionKind? =
        FunctionKind.PROJECT.takeIf { context == CONTEXT && subscription == "audit" }

    override suspend fun hasHandled(subscription: String, stream: EventStreamRecord): Boolean = false
}

/** A node in memory serving [cartModel] behind [wowRoutes]. */
class HttpNode(
    manifest: Manifest = cartManifest(),
    validators: List<CommandValidator> = emptyList(),
    rewriters: List<CommandRewriter> = emptyList(),
    callers: CallerResolver = headerCallers,
    options: WowHttpOptions = WowHttpOptions(),
    tenants: TenantPolicy = TenantPolicy.CALLERS,
    defaultWaitTimeout: kotlin.time.Duration = WaitPlan.DEFAULT_TIMEOUT,
    queries: (InMemoryEventLog) -> HttpQueries = { HttpQueries.NONE },
) {
    val log = InMemoryEventLog(partitions = 2)

    /** The commit times of the kernel: the system clock unless a test sets it. */
    var now: () -> Long = System::currentTimeMillis
    val kernel = Kernel(log, clock = { now() })
    val snapshots = InMemorySnapshotStore()
    private val registry = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop-service")
    private val coordinator = WaitCoordinator(
        Executor(kernel),
        registry,
        auditDirectory,
        LocalSignalSender(origin.node, registry),
        WaitOptions(origin, maxTimeout = 30.seconds, defaultTimeout = defaultWaitTimeout),
    )
    private val models = ModelRegistry(listOf(cartModel))
    val gateway = CommandGateway(
        models,
        mapOf(CONTEXT to coordinator),
        rewriters = rewriters,
        validators = validators,
        tenants = tenants,
    )
    val reads = KernelStateReads(models, mapOf(CONTEXT to KernelStateReads.ContextReads(kernel, snapshots)))
    val client: WebTestClient = WebTestClient
        .bindToRouterFunction(wowRoutes(manifest, gateway, reads, callers, options, queries(log)))
        .build()
        .mutate()
        .responseTimeout(Duration.ofSeconds(10))
        .build()
}

val json: JsonMapper = JsonMapper.builder().build()

/** The response: its status, its content type and its body as JSON (null when it has none). */
class Answer(val status: Int, val contentType: String?, val body: JsonNode?, val headers: HttpHeaders)

fun WebTestClient.RequestHeadersSpec<*>.answer(): Answer {
    val result = exchange().expectBody(String::class.java).returnResult()
    return Answer(
        status = result.status.value(),
        contentType = result.responseHeaders.contentType?.toString(),
        body = result.responseBody?.takeIf { it.isNotBlank() }?.let { json.readTree(it) },
        headers = result.responseHeaders,
    )
}

/** Sends `POST [path]` as [user] with [body] and the extra [headers]. */
fun HttpNode.post(
    path: String,
    body: String? = "{}",
    user: String? = "alice",
    headers: Map<String, String> = emptyMap(),
): Answer = client.post().uri(path).headers { http ->
    user?.let { http.set("X-User", it) }
    headers.forEach { (name, value) -> http.set(name, value) }
}.let { spec ->
    if (body == null) spec else spec.header("Content-Type", "application/json").bodyValue(body)
}.answer()

fun HttpNode.get(path: String, user: String? = "alice", headers: Map<String, String> = emptyMap()): Answer =
    client.get().uri(path).headers { http ->
        user?.let { http.set("X-User", it) }
        headers.forEach { (name, value) -> http.set(name, value) }
    }.answer()

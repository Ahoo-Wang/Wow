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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.route.ServiceRoutes
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.kernel.schedule.ScheduleJobModel
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.ErrorManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.JobManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.InFlightLimit
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.CommandTransformer
import me.ahoo.wow.runtime.admission.CommandValidator
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.snapshot.SnapshotRebuildModel
import me.ahoo.wow.runtime.state.KernelStateReads
import me.ahoo.wow.runtime.state.ReplayGate
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.tenant.TenantPolicy
import me.ahoo.wow.spi.wait.Origin
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import me.ahoo.wow.manifest.Creation as ManifestCreation
import me.ahoo.wow.manifest.FunctionKind as ManifestFunctionKind

const val CONTEXT = "shop"
const val TENANT = "acme"

data class OpenCart(val note: String = "")
data class AddItem(val sku: String, val quantity: Int = 1)
data class Checkout(val note: String = "")

/** A command mounted under the cart (decision D182): a transformer turns it into [OpenCart]. */
data class ImportCart(val legacyNote: String = "")

class Cart {
    var items = 0
    var note = ""
}

data class CartOpened(val note: String)
data class ItemAdded(val sku: String, val quantity: Int)

/** The domain rejection the context's error table lists, with its own status. */
const val CART_EMPTY = "cart.empty"

/** A domain rejection no table lists: 422. */
const val CART_CLOSED = "cart.closed"

val cartModel = cartModel(CopyOnWriteArrayList())

/** The cart model, recording the commands it decides into [commands], with [ImportCart] mounted when [mounted]. */
fun cartModel(commands: MutableList<Command<*>>, mounted: Boolean = false) = aggregate<Cart>(CONTEXT, "cart") {
    initial { Cart() }
    decide<OpenCart>(Creation.REQUIRED) { _, body ->
        commands += command
        putResult("opened", body.note)
        Decision.events(CartOpened(body.note))
    }
    decide<AddItem>(Creation.ALLOWED) { _, body ->
        commands += command
        Decision.events(ItemAdded(body.sku, body.quantity))
    }
    if (mounted) {
        mount<ImportCart>()
    }
    decide<Checkout> { state, body ->
        commands += command
        when {
            body.note == "closed" -> Decision.reject(CART_CLOSED, "The cart is closed.")
            body.note == "throw" -> throw UnsupportedOperationException("secret jdbc:postgresql://db-internal:5432")
            state.items == 0 -> Decision.reject(CART_EMPTY, "The cart is empty.")
            else -> Decision.NoOp
        }
    }
    evolve<CartOpened> { note = it.note }
    evolve<ItemAdded> { items += it.quantity }
}

/**
 * The Manifest of [cartModel] as the compiler writes it, with the lifecycle commands [lifecycle] lists, the snapshot
 * rebuild, [ImportCart] mounted when [mounted], and the commands [unaddressed] lists `@Unaddressed`: routed without an
 * id too, which a transformer finds the aggregate of.
 */
fun cartManifest(
    lifecycle: List<String> = Lifecycle.COMMANDS.keys.sorted(),
    mounted: Boolean = false,
    unaddressed: Set<String> = emptySet(),
): Manifest {
    fun command(name: String, creation: ManifestCreation) = CommandManifest(
        name = name,
        schema = Manifest.schemaRef(name),
        creation = creation,
        routes = CommandRoutes.of(CONTEXT, "cart", name, creation, name in unaddressed),
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
                        ) + listOfNotNull(
                            CommandManifest(
                                name = "ImportCart",
                                schema = Manifest.schemaRef("ImportCart"),
                                routes = CommandRoutes.of(CONTEXT, "cart", "ImportCart", ManifestCreation.NONE, true),
                                mounted = true,
                                transformsTo = listOf("$CONTEXT.cart.OpenCart"),
                            ).takeIf { mounted },
                        ) + lifecycle.map { SystemCommands.lifecycle(CONTEXT, "cart", it) } +
                            SystemCommands.rebuild(CONTEXT, "cart"),
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
        // A tenant-level caller (decision D189) has no owner: it reads and writes every aggregate of its tenant.
        scope = Scope(user.takeUnless { request.headers().firstHeader("X-Tenant-Level") != null }, space),
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

/**
 * A node in memory serving [cartModel] behind [wowRoutes], which records the commands it decides in [commands], with
 * [ImportCart] mounted when [mounted].
 */
class HttpNode(
    manifest: Manifest? = null,
    validators: List<CommandValidator> = emptyList(),
    transformers: List<CommandTransformer> = emptyList(),
    mounted: Boolean = false,
    callers: CallerResolver = headerCallers,
    options: WowHttpOptions = WowHttpOptions(),
    tenants: TenantPolicy = TenantPolicy.CALLERS,
    defaultWaitTimeout: kotlin.time.Duration = WaitPlan.DEFAULT_TIMEOUT,
    ids: IdGenerators = IdGenerators.DEFAULT,
    /** The jobs of the context (decision D198): with any, the node has the job aggregate and its routes. */
    jobs: List<ScheduleJob> = emptyList(),
    /** Whether the node keeps the cart's snapshots and so has the rebuild aggregate (decision D172). */
    rebuilds: Boolean = false,
    /** The node's bound on its commands in flight (decision D228 ②). */
    val inFlight: InFlightLimit = InFlightLimit(),
    /** How the state reads replay the log (review R4-Q, Q-1): at once, unless a test gates them. */
    replayGate: ReplayGate = ReplayGate.OPEN,
    /** The most streams a state read replays. */
    maxReplay: Int = KernelStateReads.DEFAULT_MAX_REPLAY,
    /** How long a state read of a minimum version waits for it (review R4-Q, Q-13). */
    minVersionWait: kotlin.time.Duration = KernelStateReads.DEFAULT_MIN_VERSION_WAIT,
    /** The node's service routes: their handlers and authorizers. */
    serviceRoutes: ServiceRoutes = ServiceRoutes.NONE,
    queries: (EventLog) -> HttpQueries = { HttpQueries.NONE },
) {
    val log = InMemoryEventLog(partitions = 2)

    /** While set, every append waits for it: the commands stay in flight, as on a store that slowed down. */
    @Volatile
    var appendGate: CompletableDeferred<Unit>? = null

    /** While set, every read of [log] by the kernel, the state reads and the queries fails as a store that is down. */
    @Volatile
    var unavailable: Boolean = false

    /** The streams read from [log] by [EventLog.read], by the kernel, the state reads and the queries. */
    val streamsRead = AtomicInteger()

    /** [log] as the node reads it: unavailable while [unavailable] is set. */
    private val store: EventLog = object : EventLog by log {
        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> {
            ensureAvailable()
            return log.read(ref, fromVersion, toVersion).onEach { streamsRead.incrementAndGet() }
        }

        override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String) =
            ensureAvailable().let { log.findByRequestId(type, tenantId, requestId) }

        // The events model in memory scans the log by its partitions' heads.
        override suspend fun head(partition: Int): Long? = ensureAvailable().let { log.head(partition) }

        override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
            appendGate?.await()
            return log.append(stream, transport)
        }

        private fun ensureAvailable() {
            if (unavailable) throw StorageUnavailableException("The log is unavailable; retry later.")
        }
    }

    /** The commands the node decided, in order. */
    val commands: MutableList<Command<*>> = CopyOnWriteArrayList()

    /** The commit times of the kernel: the system clock unless a test sets it. */
    var now: () -> Long = System::currentTimeMillis
    val kernel = Kernel(store, clock = { now() })
    val snapshots = InMemorySnapshotStore()
    private val registry = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop-service")
    private val coordinator = WaitCoordinator(
        Executor(kernel, limit = inFlight),
        registry,
        auditDirectory,
        LocalSignalSender(origin.node, registry),
        origin,
        WaitOptions(maxTimeout = 30.seconds, defaultTimeout = defaultWaitTimeout),
    )
    private val models = ModelRegistry(
        listOf(cartModel(commands, mounted)) +
            listOfNotNull(jobs.takeIf { it.isNotEmpty() }?.let { ScheduleJobModel.model(CONTEXT, it) }) +
            listOfNotNull(SnapshotRebuildModel.model(CONTEXT, setOf("cart")).takeIf { rebuilds }),
    )
    val gateway = CommandGateway(
        models,
        mapOf(CONTEXT to coordinator),
        transformers = transformers,
        validators = validators,
        ids = ids,
        tenants = tenants,
    )
    val reads = KernelStateReads(
        models,
        mapOf(CONTEXT to KernelStateReads.ContextReads(kernel, snapshots)),
        replayGate,
        maxReplay,
        minVersionWait,
    )

    /** The query models of the node's routes. */
    val httpQueries: HttpQueries = queries(store)

    /** The node's Manifest. */
    val manifest: Manifest = (manifest ?: cartManifest(mounted = mounted)).withJobs(jobs)

    /** The node's public routes, which [client] calls and a test may serve on a real server. */
    val routes: RouterFunction<ServerResponse> = wowRoutes(
        this.manifest,
        gateway,
        reads,
        callers,
        options,
        httpQueries,
        routes = serviceRoutes,
    )
    val client: WebTestClient = WebTestClient
        .bindToRouterFunction(routes)
        .build()
        .mutate()
        .responseTimeout(Duration.ofSeconds(10))
        .build()
}

val json: JsonMapper = JsonMapper.builder().build()

/** This Manifest with a saga subscription of each of [jobs] in its first context, and its job commands (D198). */
private fun Manifest.withJobs(jobs: List<ScheduleJob>): Manifest {
    if (jobs.isEmpty()) {
        return this
    }
    val context = contexts.first()
    val subscriptions = jobs.map { job ->
        SubscriptionManifest(
            "Jobs.${job.name}",
            SubscriptionKind.SAGA,
            SubscriptionSource(context.name, listOf(ScheduleJobs.AGGREGATE)),
            FunctionManifest(ManifestFunctionKind.SAGA, context.name, "Jobs", job.name),
            job = JobManifest(job.name, job.rule.expression, job.rule.zone, job.scope.name, job.sharded),
        )
    }
    return copyWith(
        contexts = listOf(
            context.copyWith(
                subscriptions = context.subscriptions + subscriptions,
                commands = SystemCommands.jobs(context.name),
            ),
        ) + contexts.drop(1)
    )
}

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

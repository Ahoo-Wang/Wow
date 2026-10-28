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

package me.ahoo.wow.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.PeerContracts
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import me.ahoo.wow.manifest.Creation as ManifestCreation

const val SALES = "sales"
const val STOCK = "stock"
const val SALES_SERVICE = "sales-service"
const val STOCK_SERVICE = "stock-service"
const val PREFIX = "test."
const val INSUFFICIENT = "stock.insufficient"

/** How many items one stock item holds: a reservation of more is rejected. */
const val AVAILABLE = 5

val ORDER: AggregateType = AggregateType(SALES, "order")
val ITEM: AggregateType = AggregateType(STOCK, "item")

data class PlaceOrder(val quantity: Int)
data class OrderPlaced(val quantity: Int)
class CancelOrder
class OrderCancelled

class OrderState {
    var quantity: Int = 0
    var cancelled: Boolean = false
}

data class Reserve(val quantity: Int)
data class Reserved(val quantity: Int)

class ItemState {
    var reserved: Int = 0
}

/** The order of the sales context; every order it cancels is recorded in [cancellations], by command id. */
fun orderModel(cancellations: MutableList<String> = CopyOnWriteArrayList()): AggregateModel<OrderState> =
    aggregate<OrderState>(SALES, "order") {
        initial { OrderState() }
        decide<PlaceOrder>(Creation.REQUIRED) { _, body -> Decision.events(OrderPlaced(body.quantity)) }
        decide<CancelOrder> { _, _ ->
            cancellations += command.id
            Decision.events(OrderCancelled())
        }
        evolve<OrderPlaced> { quantity = it.quantity }
        evolve<OrderCancelled> { cancelled = true }
    }

/** The stock items of the stock context: a reservation of more than [AVAILABLE] is rejected. */
fun itemModel(): AggregateModel<ItemState> = aggregate<ItemState>(STOCK, "item") {
    initial { ItemState() }
    decide<Reserve>(Creation.ALLOWED) { _, body ->
        if (body.quantity > AVAILABLE) {
            Decision.reject(INSUFFICIENT, "Only $AVAILABLE left.")
        } else {
            Decision.events(Reserved(body.quantity))
        }
    }
    evolve<Reserved> { reserved += it.quantity }
}

/**
 * The order saga of the sales context: each order placed reserves its quantity of the stock item of the same id, and
 * a reservation the stock context rejects cancels the order; each rejection it handles is recorded in [rejections].
 */
fun orderSaga(rejections: MutableList<CommandRejected<*>> = CopyOnWriteArrayList()): ProcessorBinding<OrderState> {
    val onReserveRejected = RejectionBinding.of(
        "OrderSaga",
        "onReserveRejected",
        Reserve::class.java,
    ) { rejected, context ->
        rejections += rejected
        listOf(CommandRequest.to(rejected.source, CancelOrder()))
    }
    return ProcessorBinding.saga(
        "OrderSaga",
        "onOrderPlaced",
        OrderState::class.java,
        rejections = listOf(onReserveRejected),
    ) { event, context ->
        event.events.filterIsInstance<OrderPlaced>().map {
            CommandRequest.to(ITEM.ref(event.ref.tenantId, event.ref.id), Reserve(it.quantity))
        }
    }
}

private fun aggregateManifest(
    context: String,
    name: String,
    commands: List<Pair<Class<*>, ManifestCreation>>,
    events: List<Class<*>>,
) = AggregateManifest(
    name,
    state = StateManifest(Manifest.schemaRef("${name}State")),
    commands = commands.map { (command, creation) ->
        CommandManifest(
            command.simpleName,
            schema = Manifest.schemaRef(command.name),
            creation = creation,
            routes = CommandRoutes.of(context, name, command.simpleName, creation),
        )
    } + Lifecycle.COMMANDS.keys.map { SystemCommands.lifecycle(context, name, it) },
    events = events.map { EventManifest(it.simpleName, schema = Manifest.schemaRef(it.name)) },
)

/** The sales service's Manifest: its orders, with their routes. */
fun salesManifest(): Manifest = Manifest(
    service = ServiceInfo(SALES_SERVICE),
    contexts = listOf(
        ContextManifest(
            SALES,
            aggregates = listOf(
                aggregateManifest(
                    SALES,
                    "order",
                    listOf(
                        PlaceOrder::class.java to ManifestCreation.REQUIRED,
                        CancelOrder::class.java to ManifestCreation.NONE,
                    ),
                    listOf(OrderPlaced::class.java, OrderCancelled::class.java),
                ),
            ),
        ),
    ),
)

/** The stock service's Manifest: its items, whose reservations the sales saga sends. */
fun stockManifest(): Manifest = Manifest(
    service = ServiceInfo(STOCK_SERVICE),
    contexts = listOf(
        ContextManifest(
            STOCK,
            aggregates = listOf(
                aggregateManifest(
                    STOCK,
                    "item",
                    listOf(Reserve::class.java to ManifestCreation.ALLOWED),
                    listOf(Reserved::class.java),
                ),
            ),
        ),
    ),
)

/** [manifest]'s contexts as the peer contract their api jar carries on the class path (decision D234). */
fun peer(manifest: Manifest): ManifestFragment = ManifestFragment(
    "${manifest.contexts.first().name}-api.jar!/${PeerContracts.RESOURCE}",
    Manifest(manifest.format, null, manifest.contexts, manifest.errors, manifest.defs),
)

/** In-memory stores of every context, timers included, opened once per context. */
class InMemoryBackends(private val partitions: Int = 2) : BackendFactory {
    val opened = ConcurrentHashMap<String, ContextBackends>()

    override fun backends(context: String): ContextBackends = opened.computeIfAbsent(context) {
        ContextBackends(
            log = InMemoryEventLog(partitions),
            checkpoints = InMemoryCheckpointStore(),
            leases = InMemoryLeaseStore(),
            ledger = InMemoryFailureLedger(),
            snapshots = SnapshotTargets.single(InMemorySnapshotStore()),
            timers = InMemoryTimerStore(),
        )
    }
}

/** Node options with short cluster and subscription timings. */
fun fastNodeOptions(maxAttempts: Int = 3): ServiceNodeOptions = ServiceNodeOptions(
    cluster = ClusterOptions(
        leaseTtl = 1_500.milliseconds,
        renewInterval = 300.milliseconds,
        memberTtl = 1_500.milliseconds,
        heartbeatInterval = 300.milliseconds,
        reconcileInterval = 100.milliseconds,
        routeRefreshInterval = 100.milliseconds,
        forwardTimeout = 1.seconds,
        drainTimeout = 5.seconds,
    ),
    subscription = SubscriptionOptions(
        retry = RetryPolicy(maxAttempts = maxAttempts, initialBackoff = 1.milliseconds, maxBackoff = 5.milliseconds),
        checkpointInterval = 10.milliseconds,
        stopTimeout = 2.seconds,
        readRetry = RetryPolicy(maxAttempts = 1, initialBackoff = 1.milliseconds, maxBackoff = 5.milliseconds),
    ),
)

/** Waits until [condition] holds, at most [timeout]. */
suspend fun eventually(timeout: Duration = 10.seconds, condition: suspend () -> Boolean) {
    withTimeout(timeout) {
        while (!condition()) {
            delay(2)
        }
    }
}

/** The string attribute [name] of this span, or null. */
fun SpanData.attr(name: String): String? = attributes.get(AttributeKey.stringKey(name))

/** An OpenTelemetry SDK whose spans end in [spans], propagating the W3C trace context, and a meter registry. */
class Telemetry {
    val spans: InMemorySpanExporter = InMemorySpanExporter.create()
    val sdk: OpenTelemetrySdk = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()
    val meters = SimpleMeterRegistry().apply { config().meterFilter(MetricNamingTest.CATALOG_ONLY) }
    var now: Long = 0
    val observer = OtelObserver(sdk, meters, clock = { now })

    /** The finished spans named [name]. */
    fun named(name: String): List<SpanData> = spans.finishedSpanItems.filter { it.name == name }

    /** The finished span named [name], which must be one. */
    fun single(name: String): SpanData = named(name).single()

    /** The finished span whose id is [spanId], or null. */
    fun byId(spanId: String): SpanData? = spans.finishedSpanItems.firstOrNull { it.spanId == spanId }

    /** The names of [span] and of its ancestors among the finished spans, from it up to the root. */
    fun lineage(
        span: SpanData
    ): List<String> = generateSequence(span) { byId(it.parentSpanId) }.map { it.name }.toList()
}

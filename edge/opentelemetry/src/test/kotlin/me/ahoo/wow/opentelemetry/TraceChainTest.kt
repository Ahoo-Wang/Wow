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

package me.ahoo.wow.opentelemetry

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.data.SpanData
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.http.wowRoutes
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SourceTopics
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import me.ahoo.wow.manifest.FunctionKind as ManifestFunctionKind

/**
 * The trace of one HTTP request across two services over in-memory topics (decision D174; roadmap 6.9's exit): the
 * request's span → the sales admission → its execution, decision and append → the order saga's handling of the
 * commit → the Kafka produce of the reservation to the stock context's command topic and the stock intake's
 * consumption of it → the stock execution, which rejects it → the receipt's consumption back in the sales context →
 * the cancellation it derives. Every span is of the request's trace, each the child of the one before it.
 */
class TraceChainTest {
    private val telemetry = Telemetry()
    private val topics = InMemoryTopics(PARTITIONS)
    private val producer = TracingTopicProducer(topics, telemetry.sdk)
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val cancelled = CopyOnWriteArrayList<String>()
    private val rejections = CopyOnWriteArrayList<CommandRejected<*>>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    private val salesManifest: Manifest = salesManifest().let { manifest ->
        manifest.copy(
            contexts = manifest.contexts.map {
                it.copy(
                    subscriptions = listOf(
                        SubscriptionManifest(
                            name = SAGA,
                            kind = SubscriptionKind.SAGA,
                            source = SubscriptionSource(SALES, listOf("order")),
                            function = FunctionManifest(ManifestFunctionKind.SAGA, SALES, "OrderSaga", "onOrderPlaced"),
                            derives = listOf(Manifest.schemaRef(Reserve::class.java.name)),
                        ),
                    ),
                )
            },
        )
    }
    private val stockManifest: Manifest = stockManifest()

    private fun ServiceNode.registered(): ServiceNode = also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private fun stockNode(): ServiceNode = ServiceNode(
        STOCK_SERVICE,
        "stock-1",
        "stock-1.internal:9090",
        listOf(itemModel()),
        stockManifest,
        InMemoryBackends(PARTITIONS),
        channel,
        options = fastNodeOptions(),
        contracts = ContractDirectory(stockManifest, listOf(peer(salesManifest))),
        topics = SourceTopics(topics, PREFIX, producer, intake = true),
        observer = telemetry.observer,
    ).registered()

    private fun salesNode(): ServiceNode = ServiceNode(
        SALES_SERVICE,
        "sales-1",
        "sales-1.internal:9090",
        listOf(orderModel(cancelled)),
        salesManifest,
        InMemoryBackends(PARTITIONS),
        channel,
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SALES to listOf(orderSaga(rejections)))),
        contracts = ContractDirectory(salesManifest, listOf(peer(stockManifest))),
        topics = SourceTopics(topics, PREFIX, producer),
        observer = telemetry.observer,
    ).registered()

    @Test
    fun `one trace should follow an HTTP request through admission, the kernel, a saga and Kafka and back`(): Unit =
        runBlocking {
            stockNode().start()
            val sales = salesNode().also { it.start() }
            val client = WebTestClient.bindToRouterFunction(wowRoutes(salesManifest, sales.gateway)).build()
                .mutate().responseTimeout(Duration.ofSeconds(10)).build()

            // The caller's span, whose context the request carries as its `traceparent`.
            val request = telemetry.sdk.getTracer("client").spanBuilder("POST /sales/order/{id}/place-order")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan()
            val headers = HashMap<String, String>()
            telemetry.sdk.propagators.textMapPropagator.inject(Context.root().with(request), headers, MapSetter)
            client.post().uri("/sales/order/o-1/place-order")
                .headers { http -> headers.forEach { (name, value) -> http.set(name, value) } }
                .header("Content-Type", "application/json")
                .bodyValue("""{"quantity":${AVAILABLE + 1}}""")
                .exchange()
                .expectStatus().isOk
            request.end()
            // The stock context rejects the reservation; its receipt cancels the order.
            eventually { cancelled.size == 1 && rejections.size == 1 }
            // A span is exported when it ends, and the stages end on their own threads, some after the cancellation
            // is decided: the receipt's consumption, for one, ends once the cancellation it derived is executed.
            eventually { ASSERTED.all { telemetry.named(it).isNotEmpty() } }
            eventually { telemetry.named("execute $SALES.order").size == 2 }

            val trace = request.spanContext.traceId
            val spans = telemetry.spans.finishedSpanItems
            spans.filter { it.name != CLIENT_SPAN }.map { it.traceId }.distinct().assert().containsExactly(trace)

            val admit = telemetry.single("admit $SALES.order")
            admit.kind.assert().isEqualTo(SpanKind.SERVER)
            admit.parentSpanId.assert().isEqualTo(request.spanContext.spanId)

            val (place, cancel) = telemetry.named("execute $SALES.order").sortedBy { it.startEpochNanos }
            place.attr(WowAttributes.OUTCOME).assert().isEqualTo("committed")
            telemetry.lineage(place).assert().containsExactly(place.name, admit.name, CLIENT_SPAN)
            telemetry.childrenOf(place).map { it.name }.assert()
                .containsExactlyInAnyOrder("decide $SALES.order", "append $SALES.order")

            // The saga handles the commit in the trace the append wrote with it (and later the cancellation's).
            val append = telemetry.childrenOf(place).single { it.name == "append $SALES.order" }
            val saga = telemetry.named("handle $SAGA").single { it.parentSpanId == append.spanId }

            // The reservation goes to the stock context's command topic: a produce, and the intake's consumption.
            val produce = telemetry.single("$COMMANDS publish")
            produce.kind.assert().isEqualTo(SpanKind.PRODUCER)
            produce.parentSpanId.assert().isEqualTo(saga.spanId)
            val intake = telemetry.single("$COMMANDS process")
            intake.kind.assert().isEqualTo(SpanKind.CONSUMER)
            intake.attr(WowAttributes.SUBSCRIPTION).assert().isEqualTo("wow.commands")
            val reserve = telemetry.single("execute $STOCK.item")
            reserve.attr(WowAttributes.OUTCOME).assert().isEqualTo("rejected")
            telemetry.lineage(reserve).assert().containsExactly(
                reserve.name,
                intake.name,
                saga.name,
                "append $SALES.order",
                place.name,
                admit.name,
                CLIENT_SPAN,
            )

            // The receipt of the rejection comes back through the sales context's receipts topic, in the same trace.
            val receipt = telemetry.single("$RECEIPTS process")
            receipt.kind.assert().isEqualTo(SpanKind.CONSUMER)
            receipt.parentSpanId.assert().isEqualTo(intake.spanId)
            telemetry.single("$RECEIPTS publish").parentSpanId.assert().isEqualTo(intake.spanId)
            telemetry.lineage(cancel).take(3).assert().containsExactly(cancel.name, receipt.name, intake.name)

            measured()
        }

    /** The metrics of the same work: each execution's commit latency, and the saga's lag. */
    private fun measured() {
        telemetry.meters.find(WowMetrics.COMMIT).tags(WowMetrics.CONTEXT, SALES, WowMetrics.OUTCOME, "committed")
            .timer()!!.count().assert().isEqualTo(2L)
        telemetry.meters.find(WowMetrics.COMMIT).tags(WowMetrics.CONTEXT, STOCK, WowMetrics.OUTCOME, "rejected")
            .timer()!!.count().assert().isEqualTo(1L)
        telemetry.meters.find(WowMetrics.SUBSCRIPTION_LAG).tags(WowMetrics.SUBSCRIPTION, SAGA).timer()!!
            .count().assert().isGreaterThanOrEqualTo(1L)
    }

    private fun Telemetry.childrenOf(span: SpanData): List<SpanData> =
        spans.finishedSpanItems.filter { it.parentSpanId == span.spanId }

    private companion object {
        const val PARTITIONS = 2
        const val SAGA = "OrderSaga.onOrderPlaced"
        const val COMMANDS = PREFIX + "wow.stock.commands"
        const val RECEIPTS = PREFIX + "wow.sales.receipts"
        const val CLIENT_SPAN = "POST /sales/order/{id}/place-order"

        /** The spans of the chain the test asserts, besides the executions it counts. */
        val ASSERTED = listOf(
            "admit $SALES.order",
            "decide $SALES.order",
            "append $SALES.order",
            "handle $SAGA",
            "$COMMANDS publish",
            "$COMMANDS process",
            "execute $STOCK.item",
            "$RECEIPTS publish",
            "$RECEIPTS process",
        )
    }
}

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

package me.ahoo.wow.runtime.binding

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import me.ahoo.wow.manifest.Creation as ManifestCreation

/**
 * A node of two contexts whose second one's join hangs, a lease it cannot take: the partitions the first one started
 * hold back their reads for the reconcile timeout at most, then read their backlogs, and a warning names the context
 * still joining.
 */
class HungContextJoinTest {
    private val stores = InMemoryBackends(partitions = PARTITIONS)

    /** Releases the back office's lease acquisitions, which wait until then. */
    private val released = CompletableDeferred<Unit>()

    private val backends = BackendFactory { context ->
        val opened = stores.backends(context)
        if (context != BACK) {
            return@BackendFactory opened
        }
        val leases = object : LeaseStore by opened.leases {
            override suspend fun acquire(group: String, partition: Int, owner: String, ttl: Duration): Lease? {
                released.await()
                return opened.leases.acquire(group, partition, owner, ttl)
            }
        }
        ContextBackends(
            log = opened.log,
            checkpoints = opened.checkpoints,
            leases = leases,
            ledger = opened.ledger,
            snapshots = opened.snapshots,
            reservations = opened.reservations,
        )
    }

    private val projected = CopyOnWriteArrayList<String>()
    private val projection = ProcessorBinding.project("CartView", "onEvent", CartState::class.java) { event, _ ->
        projected += event.ref.id
    }

    private val node = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel(), boxModel()),
        manifest(),
        backends,
        LoopbackClusterChannel(),
        options = ServiceNodeOptions(
            cluster = fastOptions(drainTimeout = 200.milliseconds, reconcileTimeout = BOUND),
            subscription = testOptions(),
        ),
        subscriptions = SubscriptionBindings(mapOf(SHOP to listOf(projection))),
    )

    @Test
    fun `a context whose join hangs should hold back the others' reads for the reconcile timeout at most, and be named`(): Unit =
        runBlocking {
            val log = stores.backends(SHOP).log
            repeat(CARTS) {
                val stream = EventStreamRecord(
                    CART.ref(TENANT, "c-$it"),
                    1,
                    "9x-$it",
                    "9x-$it",
                    "shop.cart.AddItems",
                    System.currentTimeMillis(),
                    listOf(EventRecord("shop.cart.ItemsAdded", 1, """{"items":["apple"]}""")),
                    Scope.NONE,
                )
                log.append(stream).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            }
            val logger = LoggerFactory.getLogger(ServiceNode::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            val starting = launch { node.start() }
            try {
                eventually { node.context(SHOP).ownership.owned.size == PARTITIONS }
                // Checked at once: the partitions open their reads once every context has joined, or the reconcile
                // timeout passed.
                projected.assert().isEmpty()

                eventually(timeout = 5.seconds) { projected.size == CARTS }
                starting.isActive.assert().isTrue()
                val warnings = synchronized(appender) { appender.list.toList() }
                    .filter { it.level == Level.WARN }.map { it.formattedMessage }
                warnings.single { "context $BACK is still joining" in it }.assert().contains("more than $BOUND")
            } finally {
                released.complete(Unit)
                starting.join()
                logger.detachAppender(appender)
                node.stop()
            }
        }

    /** The back office: a box that is filled, a context of the shop's service. */
    data class Fill(val n: Int)
    data class Filled(val n: Int)

    class BoxState {
        var n: Int = 0
    }

    private fun boxModel(): AggregateModel<BoxState> = aggregate<BoxState>(BACK, "box") {
        initial { BoxState() }
        decide<Fill>(Creation.ALLOWED) { _, body -> Decision.events(Filled(body.n)) }
        evolve<Filled> { n = it.n }
    }

    private fun manifest(): Manifest = shopManifest().let { shop ->
        Manifest(
            service = ServiceInfo(SHOP_SERVICE),
            contexts = shop.contexts + ContextManifest(
                BACK,
                aggregates = listOf(aggregateManifest("box", listOf("Fill" to ManifestCreation.ALLOWED), listOf("Filled"), BACK)),
            ),
        )
    }

    private companion object {
        const val BACK = "back"
        const val PARTITIONS = 4
        const val CARTS = 100
        val BOUND: Duration = 1.seconds
    }
}

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
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
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * RT-11: a node of two contexts whose start is cancelled while the second one joins, its read of its lease group's owners
 * on a store that does not answer. What the first one had started is stopped all the same: it leaves the members, releases
 * its leases, and the node ends STOPPED, so a later stop does nothing.
 */
class CancelledStartTest {
    private val stores = InMemoryBackends(partitions = PARTITIONS)

    /** Completed once the back office's join reads its owners, where it then waits. */
    private val asked = CompletableDeferred<Unit>()

    /** Ends the back office's reads of its owners, which wait until then; cancellable, as a store call is. */
    private val answered = CompletableDeferred<Unit>()

    /** Ends the shop's lease releases, which wait until then when [holdReleases]. */
    private val released = CompletableDeferred<Unit>()

    @Volatile
    private var holdReleases = false

    private val backends = BackendFactory { context ->
        val opened = stores.backends(context)
        val leases = if (context == BACK) {
            object : LeaseStore by opened.leases {
                override suspend fun owners(group: String): List<Lease> {
                    if (group == BACK_GROUP) {
                        asked.complete(Unit)
                        answered.await()
                    }
                    return opened.leases.owners(group)
                }
            }
        } else {
            object : LeaseStore by opened.leases {
                override suspend fun release(group: String, partition: Int, owner: String): Boolean {
                    if (holdReleases) {
                        released.await()
                    }
                    return opened.leases.release(group, partition, owner)
                }
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

    private val options = ServiceNodeOptions(
        cluster = fastOptions(drainTimeout = 200.milliseconds, reconcileTimeout = 2.seconds),
        subscription = testOptions(),
        wait = WaitOptions(maxTimeout = 1.seconds, defaultTimeout = 1.seconds),
    )

    private val node = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel(), boxModel()),
        manifest(),
        backends,
        LoopbackClusterChannel(),
        options = options,
    )

    private suspend fun shopMembers(): List<String> =
        stores.backends(SHOP).leases.members(SHOP_SERVICE).filter { SHOP_GROUP in it.groups }.map { it.node }

    @Test
    fun `a start cancelled while a context joins stops the contexts that had joined, and the node ends stopped`(): Unit =
        runBlocking {
            val starting = async { node.start() }
            try {
                eventually { node.context(SHOP).ownership.owned.size == PARTITIONS }
                shopMembers().assert().containsExactly("node-1")
                // Cancelled where the back office's join waits on the store, a call that can be cancelled.
                withTimeout(10.seconds) { asked.await() }

                starting.cancel()
                assertThrows<CancellationException> { withTimeout(60.seconds) { starting.await() } }

                node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
                shopMembers().assert().isEmpty()
                stores.backends(SHOP).leases.owners(SHOP_GROUP).assert().isEmpty()
                node.context(SHOP).ownership.isRunning.assert().isFalse()
                // Stopped already: a stop does nothing more.
                node.stop()
                node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
            } finally {
                answered.complete(Unit)
            }
        }

    @Test
    fun `a clean-up past the node's stop bound goes on in the background, naming what it had not stopped`(): Unit =
        runBlocking {
            val logger = LoggerFactory.getLogger(ServiceNode::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            holdReleases = true
            val starting = async { node.start() }
            try {
                eventually { node.context(SHOP).ownership.owned.size == PARTITIONS }
                withTimeout(10.seconds) { asked.await() }
                starting.cancel()
                // The caller waits for the stop at most its bound, then the cancellation goes on up.
                assertThrows<CancellationException> { withTimeout(60.seconds) { starting.await() } }
                node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
                val errors = synchronized(appender) { appender.list.toList() }
                    .filter { it.level == Level.ERROR }.map { it.formattedMessage }
                errors.single { "did not finish stopping what its failed start started" in it }.assert()
                    .contains("within ${options.stopBound}")
                    .contains("the steps [HANDED_OFF")
                    .contains("the lease groups of the contexts [$SHOP]")
            } finally {
                released.complete(Unit)
                answered.complete(Unit)
                logger.detachAppender(appender)
            }
            // The stop that went on in the background ends once the store answers.
            eventually(timeout = 10.seconds) { shopMembers().isEmpty() }
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
                aggregates = listOf(aggregateManifest("box", listOf("Fill" to Creation.ALLOWED), listOf("Filled"), BACK)),
            ),
        )
    }

    private companion object {
        const val BACK = "back"
        const val PARTITIONS = 4
        val SHOP_GROUP = "$SHOP_SERVICE/$SHOP"
        val BACK_GROUP = "$SHOP_SERVICE/$BACK"
    }
}

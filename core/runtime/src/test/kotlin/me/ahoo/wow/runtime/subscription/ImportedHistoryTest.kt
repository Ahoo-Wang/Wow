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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.binding.ORDER
import me.ahoo.wow.runtime.binding.SHOP
import me.ahoo.wow.runtime.binding.SHOP_SERVICE
import me.ahoo.wow.runtime.binding.TENANT
import me.ahoo.wow.runtime.binding.cartModel
import me.ahoo.wow.runtime.binding.orderModel
import me.ahoo.wow.runtime.binding.shopManifest
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.ImportCutover
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * The history migrate-data imported, as the subscriptions of the context's own log take it (#6928): a saga or a
 * reactor passes over the log's entries up to the cut-over, which 9.x's own sagas acted on, however it starts; a
 * projection builds from them; and a compensation's parked retry of one, 9.x's failure imported, is handled.
 */
class ImportedHistoryTest {
    private val backends = InMemoryBackends(partitions = PARTITIONS)
    private val nodes = ArrayList<ServiceNode>()
    private val acted = CopyOnWriteArrayList<String>()
    private val projected = CopyOnWriteArrayList<String>()

    /** The shop's own saga, which starts at the end of the log as a saga does, and a projection of its orders. */
    private val subscriptions = SubscriptionFactory { context ->
        if (context.context != SHOP) {
            emptyList()
        } else {
            listOf(
                Subscription.saga(SAGA, SubscriptionFilter(SHOP, setOf(ORDER.name)), StartFrom.END) {
                    acted += it.ref.id
                    HandledEntry.NOTHING
                },
                Subscription(BOARD, SubscriptionFilter(SHOP, setOf(ORDER.name))) { projected += it.ref.id },
            )
        }
    }

    /** The runtime tests' timings, the parked entries scanned every 100 ms, so a retry due is made at once. */
    private val options = fastNodeOptions().let { fast ->
        val parked = ParkedRetryOptions(scanInterval = 100.milliseconds)
        fast.copyWith(subscription = fast.subscription.copyWith(parked = parked))
    }

    @AfterEach
    fun stop(): Unit = runBlocking { nodes.forEach { it.stop() } }

    @Test
    fun `an own saga that ran in V10's first window passes over the history a second import appends`(): Unit =
        runBlocking {
            imported("o-1")
            val first = node()
            first.start()
            first.place("o-2")
            eventually { "o-2" in acted && "o-2" in projected }
            first.stop()
            // Rolled back by export; 9.x wrote o-3; the second import appends it and records the cut-over past it.
            imported("o-3")
            val second = node()
            second.start()
            second.place("o-4")
            eventually { caughtUp(SAGA) && caughtUp(BOARD) }

            acted.assert().describedAs("what the own saga acted on").containsExactlyInAnyOrder("o-2", "o-4")
            projected.assert().describedAs("what the projection built from")
                .containsExactlyInAnyOrder("o-1", "o-2", "o-3", "o-4")
        }

    @Test
    fun `a compensation's parked retry of an imported entry is handled by the own saga`(): Unit = runBlocking {
        imported("o-1")
        // As the import of 9.x's compensation parks it: 9.x's saga failed on o-1, and V10 retries it.
        val ref = orderModel().type.ref(TENANT, "o-1")
        val ledger = backends.opened.getValue(SHOP).ledger
        val saga = SubscriptionId(SHOP, SAGA)
        ledger.park(
            ParkedAggregate(
                subscription = saga,
                ref = ref,
                partition = Partitioner.partitionOf(ref, PARTITIONS),
                fromVersion = 1,
                reason = "9.x: the payment gateway was down.",
                failedAt = System.currentTimeMillis() - 1_000,
                // 9.x's spec, as the import gives it: retried after its backoff.
                retrySpec = ParkedRetrySpec.of(initialBackoff = 1.milliseconds),
            ),
        )
        node().start()

        eventually { "o-1" in acted && ledger.parked(saga, ref) == null }
        acted.assert().containsExactly("o-1")
    }

    private fun node(subscriptions: SubscriptionFactory = this.subscriptions): ServiceNode = ServiceNode(
        SHOP_SERVICE,
        "node-${nodes.size + 1}",
        "node.internal:9090",
        listOf(orderModel(), cartModel()),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = options,
        subscriptions = subscriptions,
    ).also { nodes += it }

    /**
     * The history migrate-data would have imported: [ids]' orders placed by a node that runs no subscription, then the
     * cut-over recorded at the log's heads, as the import records it.
     */
    private suspend fun imported(vararg ids: String) {
        val history = node(SubscriptionFactory { emptyList() })
        history.start()
        ids.forEach { history.place(it) }
        history.stop()
        val stores = backends.opened.getValue(SHOP)
        (0 until stores.log.partitions).forEach { partition ->
            stores.log.head(partition)?.let {
                stores.checkpoints.save(ImportCutover.groupOf(SHOP), ImportCutover.SUBSCRIPTION, partition, it)
            }
        }
    }

    /** Whether [subscription]'s checkpoint reached the log's head in every partition: it read all there is. */
    private suspend fun caughtUp(subscription: String): Boolean {
        val stores = backends.opened.getValue(SHOP)
        return (0 until stores.log.partitions).all { partition ->
            val head = stores.log.head(partition) ?: return@all true
            val read = stores.checkpoints.load(LeaseGroup(SHOP_SERVICE, SHOP).name, subscription, partition)
            read != null && read >= head
        }
    }

    private suspend fun ServiceNode.place(id: String) = gateway.execute(
        ServerCommandRequest(
            context = SHOP,
            aggregate = "order",
            command = "Place",
            body = CommandBody.of("""{"items":["apple"]}"""),
            caller = Caller(TENANT, Scope(ownerId = "alice")),
            aggregateId = id,
            wait = WaitPlan.COMMITTED,
        ),
    )

    private companion object {
        const val PARTITIONS = 2
        const val SAGA = "order.saga"
        const val BOARD = "order.board"
    }
}

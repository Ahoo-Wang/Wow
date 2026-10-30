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

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryReservationStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.subscription.Member
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * A node on standby through the dual-run window of a migration's cut-over (design 07 §3, decision D32): it refuses
 * every command and handles no subscription while what `migrate-data tail` imports lands in its log; once activated, a
 * saga that starts at the end of its stream handles the commits after the activation only, never the imported history.
 * The case is the 9.x example's: the cart saga of an order placed from a cart.
 */
class StandbyNodeTest {
    private val log = InMemoryEventLog(PARTITIONS)

    @Volatile
    private var leasesDown = false

    /** The lease store, down once [leasesDown] is set: the node's join at the activation fails. */
    private val store = InMemoryLeaseStore()
    private val leases = object : LeaseStore by store {
        override suspend fun heartbeat(
            group: String,
            node: String,
            address: String,
            ttl: Duration,
            groups: Set<String>,
        ): Member {
            check(!leasesDown) { "The lease store is down." }
            return store.heartbeat(group, node, address, ttl, groups)
        }
    }
    private val backends = BackendFactory {
        ContextBackends(
            log = log,
            checkpoints = InMemoryCheckpointStore(),
            leases = leases,
            ledger = InMemoryFailureLedger(),
            snapshots = SnapshotTargets.single(InMemorySnapshotStore()),
            reservations = InMemoryReservationStore(),
        )
    }
    private val handled = CopyOnWriteArrayList<String>()

    /** An order placed from a cart: the saga empties the cart named by its first item, as the 9.x CartSaga does. */
    private val onOrderPlaced = ProcessorBinding.saga(
        "CartSaga",
        "onOrderPlaced",
        OrderState::class.java,
        derives = listOf(AddItems::class.java),
    ) { event, c ->
        handled += event.ref.id
        event.events.filterIsInstance<Placed>().map {
            CommandRequest.to(CART.ref(event.ref.tenantId, it.items.first()), AddItems(it.items.drop(1)))
        }
    }

    private var node: ServiceNode? = null

    private fun node(): ServiceNode = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel()),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SHOP to listOf(onOrderPlaced))),
    ).also { node = it }

    @AfterEach
    fun stop(): Unit = runBlocking { node?.stop() ?: Unit }

    private fun place(order: String, vararg items: String) = ServerCommandRequest(
        context = SHOP,
        aggregate = "order",
        command = "Place",
        body = CommandBody.of("""{"items":[${items.joinToString { "\"$it\"" }}]}"""),
        caller = Caller(TENANT, Scope(ownerId = "bob")),
        aggregateId = order,
        wait = WaitPlan.COMMITTED,
    )

    /** What `migrate-data tail` appends while the node is on standby: an order placed in 9.x. */
    private suspend fun imported(order: String) {
        val stream = EventStreamRecord(
            ORDER.ref(TENANT, order),
            1,
            "9x-$order",
            "9x-$order",
            "shop.order.Place",
            System.currentTimeMillis(),
            listOf(EventRecord("shop.order.Placed", 1, """{"items":["cart-$order","apple"]}""")),
            Scope(ownerId = "bob"),
        )
        log.append(stream).assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    @Test
    fun `a node on standby refuses commands and handles nothing until it is activated, then only what came after`(): Unit =
        runBlocking {
            val node = node()
            node.start(standby = true)
            node.standby.assert().isTrue()
            node.activation.assert().isNull()

            val refused = node.gateway.send(place("o-standby", "cart-x", "pear")).toList().last()
            refused.status.assert().isEqualTo(CommandResult.Status.FAILED)
            refused.error!!.code.assert().isEqualTo(FrameworkCodes.NODE_STANDBY)

            // The tail imports 9.x's orders meanwhile; nothing handles them on standby.
            imported("o-9x-1")
            imported("o-9x-2")
            delay(SETTLE_MILLIS)
            handled.assert().isEmpty()

            val activated = node.activate("operator-1", "cut-over")
            activated.activated.assert().isTrue()
            activated.by.assert().isEqualTo("operator-1")
            activated.reason.assert().isEqualTo("cut-over")
            activated.at.assert().isNotNull()
            node.standby.assert().isFalse()
            node.activation.assert().isEqualTo(activated)
            // Activating again changes nothing, and says when it was.
            node.activate("operator-2").assert().isEqualTo(activated)

            node.gateway.send(place("o-v10", "cart-v10", "plum")).toList().last().status
                .assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            eventually { "o-v10" in handled }
            delay(SETTLE_MILLIS)
            // The saga's end of the stream was taken at the activation: the imported history is never handled again.
            handled.assert().containsExactly("o-v10")
            log.read(CART.ref(TENANT, "cart-o-9x-1")).toList().assert().isEmpty()
        }

    @Test
    fun `a node not on standby is activated as a no-op, and a node on standby stops`(): Unit = runBlocking {
        val running = node()
        running.start()
        running.standby.assert().isFalse()
        running.activate("operator").also {
            it.activated.assert().isFalse()
            it.at.assert().isNull()
        }
        running.stop()

        val standby = node()
        assertThrows<IllegalStateException> { standby.activate("operator") }
        standby.start(standby = true)
        standby.stop()
        assertThrows<IllegalStateException> { standby.activate("operator") }

        // Activated without a reason, the activation records none.
        val unexplained = node()
        unexplained.start(standby = true)
        unexplained.activate("operator").also {
            it.activated.assert().isTrue()
            it.reason.assert().isNull()
        }
        unexplained.stop()
    }

    @Test
    fun `an activation that fails stops the node and throws`(): Unit = runBlocking {
        val node = node()
        node.start(standby = true)
        leasesDown = true
        assertThrows<IllegalStateException> { node.activate("operator", "cut-over") }
            .message.assert().isEqualTo("The lease store is down.")
        node.activation.assert().isNull()
        // Stopped: it refuses another activation, as any node that is not running.
        assertThrows<IllegalStateException> { node.activate("operator") }
            .message.assert().contains("only a running node is activated")
    }

    private companion object {
        const val PARTITIONS = 4
        const val SETTLE_MILLIS = 500L
    }
}

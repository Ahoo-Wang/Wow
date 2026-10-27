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

package me.ahoo.wow.runtime.wait

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.manifest.SnapshotManifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.binding.CART
import me.ahoo.wow.runtime.binding.ORDER
import me.ahoo.wow.runtime.binding.SHOP
import me.ahoo.wow.runtime.binding.SHOP_SERVICE
import me.ahoo.wow.runtime.binding.TENANT
import me.ahoo.wow.runtime.binding.cartModel
import me.ahoo.wow.runtime.binding.orderModel
import me.ahoo.wow.runtime.binding.shopManifest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * An aggregate whose snapshots are turned off (`@AggregateRoot(snapshot = false)`, decisions D4, D195): no snapshot
 * is written for it, and a wait has no snapshot stage for it. The default wait ends at COMMITTED, and a wait that asks
 * for the snapshot is rejected at admission instead of being left to time out; the other aggregates keep theirs.
 */
class SnapshotOptOutTest {
    private val backends = InMemoryBackends(partitions = 4)

    /** The shop's Manifest with the cart's snapshots off, as the compiler writes `@AggregateRoot(snapshot = false)`. */
    private val manifest = shopManifest().let { manifest ->
        manifest.copyWith(
            contexts = manifest.contexts.map { context ->
                context.copyWith(
                    aggregates = context.aggregates.map {
                        if (it.name == CART.name) it.copyWith(snapshot = SnapshotManifest(enabled = false)) else it
                    },
                )
            },
        )
    }

    private val node = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel()),
        manifest,
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions(),
    )

    @AfterEach
    fun stop(): Unit = runBlocking { node.stop() }

    private suspend fun send(aggregate: String, command: String, id: String, wait: WaitPlan?): CommandResult =
        node.gateway.execute(
            ServerCommandRequest(
                context = SHOP,
                aggregate = aggregate,
                command = command,
                body = CommandBody.of("""{"items":["apple"]}"""),
                caller = Caller(TENANT, Scope.NONE),
                aggregateId = id,
                wait = wait,
            ),
        )

    @Test
    fun `a wait on an aggregate without snapshots should end at COMMITTED or be rejected, never hang`(): Unit =
        runBlocking {
            node.start()
            withTimeout(5.seconds) {
                // The default plan: COMMITTED in place of the snapshot the cart does not have.
                val committed = send("cart", "AddItems", "c-1", null)
                committed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
                committed.hop.stage.assert().isEqualTo(Stage.COMMITTED)
                // Asking for the snapshot is refused at admission, and nothing is committed.
                val refused = send("cart", "AddItems", "c-2", WaitPlan.SNAPSHOT)
                refused.status.assert().isEqualTo(CommandResult.Status.REJECTED)
                refused.error!!.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
                refused.error!!.message.assert().contains("has no snapshot subscription")
                // The order keeps its snapshots: the default waits for one, which is written.
                val placed = send("order", "Place", "o-1", null)
                placed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
                placed.hop.stage.assert().isEqualTo(Stage.HANDLED)
            }
            val snapshots = backends.opened.getValue(SHOP).primarySnapshots!!
            eventually { snapshots.load(ORDER.ref(TENANT, "o-1")) != null }
            snapshots.load(CART.ref(TENANT, "c-1")).assert().isNull()
            backends.opened.getValue(SHOP).log.read(CART.ref(TENANT, "c-2")).toList().assert().isEmpty()
        }
}

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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.ledger.MaintenanceSkips
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ParkedOps
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * What the access walks and the snapshot rewrites park, as an operator sees it on a running node: listed and counted
 * under `wow.access.walk` and `wow.snapshot.rewrite`, retried by running the work on the aggregate again, skipped.
 */
class MaintenanceSkipsNodeTest {
    private val backends = InMemoryBackends(partitions = PARTITIONS)

    private val node = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel()),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions(),
    )

    private val ref = CART.ref(TENANT, "c-1")
    private val walk = SubscriptionId(SHOP, MaintenanceSkips.ACCESS_WALK)
    private val rewrite = SubscriptionId(SHOP, MaintenanceSkips.SNAPSHOT_REWRITE)
    private val ledger get() = backends.opened.getValue(SHOP).ledger

    @AfterEach
    fun stop(): Unit = runBlocking { node.stop() }

    /** Commits a stream of [ref], as a command would. */
    private suspend fun committed() {
        val stream = EventStreamRecord(
            ref = ref,
            version = 1,
            commandId = "c-1",
            requestId = "r-1",
            commandType = "shop.cart.AddItems",
            commitTime = System.currentTimeMillis(),
            events = listOf(EventRecord("shop.cart.ItemsAdded", 1, """{"items":["item-1"]}""")),
            scope = Scope("alice"),
        )
        backends.opened.getValue(SHOP).log.append(stream).assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    /** Parks [ref] under [subscription], as the work does when it cannot read it. */
    private suspend fun parked(subscription: SubscriptionId, at: Long = 1L): ParkedAggregate {
        val entry = ParkedAggregate(subscription, ref, Partitioner.partitionOf(ref, PARTITIONS), 1, "skipped", at)
        ledger.park(entry).assert().isTrue()
        return entry
    }

    private suspend fun count(name: String): Int =
        node.parked.groupedSubscriptions.filter { it.name == name }.sumOf { node.parked.count(it, 10) }

    @Test
    fun `the entries the work parked should be listed, counted, retried and skipped as any parked entry`(): Unit =
        runBlocking {
            node.start()
            committed()
            parked(walk)
            parked(rewrite)

            node.parked.subscriptions.assert().contains(walk, rewrite)
            // The node runs every partition: it publishes the entries, and acts on them at once.
            node.parked.owns(walk, ref).assert().isTrue()
            node.parked.owns(rewrite, ref).assert().isTrue()
            count(MaintenanceSkips.ACCESS_WALK).assert().isEqualTo(1)
            count(MaintenanceSkips.SNAPSHOT_REWRITE).assert().isEqualTo(1)
            node.parked.get(walk, ref)!!.entry.reason.assert().isEqualTo("skipped")

            node.parked.retry(walk, ref).assert().isEqualTo(ParkedOps.Outcome.RESOLVED)
            node.parked.retry(rewrite, ref).assert().isEqualTo(ParkedOps.Outcome.RESOLVED)
            ledger.isParked(walk, ref).assert().isFalse()
            ledger.isParked(rewrite, ref).assert().isFalse()
            backends.opened.getValue(SHOP).snapshots!!.stores.values.single().load(ref).assert().isNotNull()

            val again = parked(walk, at = 2L)
            node.parked.skip(walk, ref, again.failedAt).assert().isEqualTo(ParkedOps.Outcome.RESOLVED)
            ledger.isParked(walk, ref).assert().isFalse()
        }

    private companion object {
        const val PARTITIONS = 4
    }
}

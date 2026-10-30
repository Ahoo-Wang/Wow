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

package me.ahoo.wow.runtime.node

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.subscription.OrphanCheckpoints
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionId
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.Checkpoint
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** What an operator reads of a node's subscriptions (decision D372 S7): where each stands, and what is left behind. */
class NodeSubscriptionsTest {
    private val backends = InMemoryBackends(PARTITIONS)

    /** What the slow subscription waits for. */
    private val slow = CompletableDeferred<Unit>()

    private val node = ServiceNode(
        NODE_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(accountModel(BANK)),
        accountsManifest(BANK),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions(),
        subscriptions = {
            listOf(
                Subscription(SLOW, SubscriptionFilter(BANK)) { slow.await() },
                Subscription(FAILING, SubscriptionFilter(BANK)) { error("Always fails.") },
                Subscription(MIRROR, SubscriptionFilter(BANK)) {
                    throw StorageUnavailableException("The mirror's store is down.")
                },
            )
        },
    )

    @AfterEach
    fun stop(): Unit = runBlocking {
        slow.complete(Unit)
        node.stop()
    }

    @Test
    fun `a running node shows each subscription in the partitions it runs, behind, parking or paused, and the orphans`(): Unit =
        runBlocking {
            val stores = backends.backends(BANK)
            stores.checkpoints.save(GROUP, "Old.onDeposit", 0, 5)
            stores.checkpoints.save(GROUP, "Old.onDeposit", 3, 2)
            stores.checkpoints.save(GROUP, "wow.snapshot.rebuild", 0, 7)
            val type = AggregateType(BANK, "account")
            repeat(3) { stores.ledger.park(ParkedAggregate("Old.onDeposit", type.ref("t", "o-$it"), 0, 1, "x", 0)) }
            node.start()
            val ref = type.ref("t", "a-1")
            node.gateway.execute(
                ServerCommandRequest(
                    context = BANK,
                    aggregate = "account",
                    command = "Deposit",
                    body = CommandBody.of("""{"amount":1}"""),
                    caller = Caller("t"),
                    aggregateId = ref.id,
                    wait = WaitPlan.COMMITTED,
                ),
            )
            val partition = Partitioner.partitionOf(ref, PARTITIONS)
            eventually { node.parked.list(SubscriptionId(BANK, FAILING)).entries.isNotEmpty() }
            eventually { node.context(BANK).subscriptions.paused().isNotEmpty() }
            val sample = NodeSampler(node).sample()

            val view = node.subscriptionsView(sample, maxParked = 2)

            view.node.assert().isEqualTo("node-1")
            val byName = view.subscriptions.associateBy { it.id }
            byName.keys.assert().contains(
                SubscriptionId(BANK, SLOW),
                SubscriptionId(BANK, FAILING),
                SubscriptionId(BANK, MIRROR),
                SubscriptionId(BANK, "wow.snapshot"),
            )
            view.subscriptions.map { it.group }.toSet().assert().containsExactly(GROUP)
            view.subscriptions.map { it.source }.toSet().assert().containsExactly(SubscriptionView.Source.LOG)
            // One node runs every partition: each with its live watermark and the head the sample read.
            val failing = byName.getValue(SubscriptionId(BANK, FAILING))
            failing.parked.assert().isEqualTo(1)
            failing.partitions.map { it.partition }.assert().isEqualTo((0 until PARTITIONS).toList())
            failing.partitions.all { it.runs && it.head == sample.heads[GROUP to it.partition] }.assert().isTrue()
            val behind = byName.getValue(SubscriptionId(BANK, SLOW)).partitions.single { it.partition == partition }
            behind.head.assert().isEqualTo(1L)
            behind.lag.assert().isEqualTo(1L)
            val paused = byName.getValue(SubscriptionId(BANK, MIRROR)).partitions.single { it.partition == partition }
            paused.paused!!.reason.assert().isNotBlank()
            byName.getValue(SubscriptionId(BANK, MIRROR)).partitions.filter { it.paused != null }.map { it.partition }
                .assert().containsExactly(partition)
            // The old name's checkpoints and parked entries, counted up to the limit; Wow's own names are left out.
            view.orphans.assert().containsExactly(
                OrphanCheckpoints(
                    GROUP,
                    "Old.onDeposit",
                    listOf(Checkpoint.of("Old.onDeposit", 0, 5), Checkpoint.of("Old.onDeposit", 3, 2)),
                    2,
                ),
            )
        }

    @Test
    fun `a node not running yet shows where the checkpoints stand, and neither counts nor tells orphans`(): Unit =
        runBlocking {
            val checkpoints = backends.backends(BANK).checkpoints
            checkpoints.save(GROUP, "wow.snapshot", 2, 7)
            // The application's subscriptions are registered as the node starts: until then, none is an orphan.
            checkpoints.save(GROUP, FAILING, 1, 3)

            val view = node.subscriptionsView(NodeSample.EMPTY, maxParked = 10)

            val snapshot = view.subscriptions.single { it.id == SubscriptionId(BANK, "wow.snapshot") }
            snapshot.parked.assert().isNull()
            snapshot.partitions.assert().containsExactly(PartitionView(2, 7))
            snapshot.partitions.single().lag.assert().isNull()
            view.orphans.assert().isEmpty()
        }

    private companion object {
        const val BANK = "bank"
        const val PARTITIONS = 4
        const val GROUP = "$NODE_SERVICE/$BANK"
        const val SLOW = "Slow.onDeposit"
        const val FAILING = "Failing.onDeposit"
        const val MIRROR = "Mirror.onDeposit"
    }
}

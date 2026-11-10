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

package me.ahoo.wow.runtime.health

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.clockOf
import me.ahoo.wow.runtime.node.ContextBackends
import me.ahoo.wow.runtime.node.NODE_SERVICE
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.accountsManifest
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.snapshot.SnapshotTargets
import me.ahoo.wow.runtime.subscription.PausedPartition
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.StorageUnavailableException
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import kotlin.time.Duration

/**
 * The subscriptions component of a node's health: behind, parking or paused is degraded and leaves the node ready, each
 * context's snapshot subscription with its own parked entries, a systemic pause degrading its subscription only.
 */
class NodeSubscriptionHealthTest : NodeHealthFixtures() {
    @Test
    fun `a subscription behind, parking or paused should be degraded, and leave the node ready`(): Unit =
        runBlocking {
            val node = node(
                subscriptions = {
                    listOf(
                        Subscription("Slow.onDeposit", SubscriptionFilter(BANK)) { slow.await() },
                        Subscription("Failing.onDeposit", SubscriptionFilter(BANK)) { error("Always fails.") },
                    )
                },
            )
            val paused = PausedPartition(
                "g",
                "Slow.onDeposit",
                1,
                1_000,
                "a systemic failure: StorageUnavailableException: jdbc:postgresql://db-1/bank refused alice",
                "StorageUnavailableException",
            )
            val options = HealthOptions(storeDownAfter = Duration.ZERO, lagDegradedAt = 1, parkedDegradedAt = 5)
            val health = healthOf(node, options) {
                NodeHealth(node, it, options, pausedOf = { listOf(paused) }, clock = clockOf { 3_000 })
            }
            node.start()
            // A first sample, before anything parks: the parked count grows from it.
            health.sample()
            node.gateway.execute(deposit("a-1"))
            eventually {
                health.sample()
                health.health().component(NodeHealth.SUBSCRIPTIONS)!!.components.single()
                    .component("Failing.onDeposit")!!.details["parked"] == 1
            }
            val group = health.health().component(NodeHealth.SUBSCRIPTIONS)!!.components.single()
            group.status.assert().isEqualTo(HealthStatus.DEGRADED)
            val failing = group.component("Failing.onDeposit")!!
            failing.status.assert().isEqualTo(HealthStatus.DEGRADED)
            failing.details["parkedGrowing"].assert().isEqualTo(true)
            val behind = group.component("Slow.onDeposit")!!
            behind.status.assert().isEqualTo(HealthStatus.DEGRADED)
            (behind.details["lag"] as Long).assert().isGreaterThanOrEqualTo(1L)
            behind.details["paused"].assert().isEqualTo(
                // The failure's class alone, never its message, which here names a host and a user.
                listOf(mapOf("partition" to 1, "pausedMillis" to 2_000L, "failure" to "StorageUnavailableException")),
            )
            // Subscriptions never take the node out of service.
            health.readiness().status.assert().isEqualTo(HealthStatus.UP)
            slow.complete(Unit)
        }

    @Test
    fun `each context's snapshot subscription should show its own parked entries, never summed with another's`(): Unit =
        runBlocking {
            val ledgers = mapOf(BANK to InMemoryFailureLedger(), SHOP to InMemoryFailureLedger())
            val node = ServiceNode(
                NODE_SERVICE,
                "node-1",
                "node-1.internal:9090",
                listOf(account(), account(context = SHOP)),
                accountsManifest(BANK, SHOP),
                { context ->
                    ContextBackends(
                        log = InMemoryEventLog(PARTITIONS),
                        checkpoints = InMemoryCheckpointStore(),
                        leases = InMemoryLeaseStore(),
                        ledger = ledgers.getValue(context),
                        snapshots = SnapshotTargets.single(InMemorySnapshotStore()),
                    )
                },
                channel,
                options = fastNodeOptions(),
            ).also { nodes += it }
            channel.bind(node.address, node.forwardHandler)
            ledgers.forEach { (context, ledger) ->
                ledger.park(
                    ParkedAggregate(
                        SubscriptionId(context, "wow.snapshot"),
                        AggregateType(context, "account").ref("t", "a-1"),
                        0,
                        1,
                        "x",
                        0
                    )
                )
            }
            val health = health(node)
            node.start()
            health.sample()
            val groups = health.health().component(NodeHealth.SUBSCRIPTIONS)!!.components
            val parked = groups.associate { group -> group.name to group.component("wow.snapshot")?.details?.get("parked") }
            parked.values.assert().containsOnly(1)
            parked.size.assert().isEqualTo(2)
        }

    @Test
    fun `a partition the runtime paused on a systemic failure should degrade its subscription only (D243)`(): Unit =
        runBlocking {
            val node = node(
                subscriptions = {
                    listOf(
                        Subscription("Mirror.onDeposit", SubscriptionFilter(BANK)) {
                            throw StorageUnavailableException("The mirror's store is down.")
                        },
                    )
                },
            )
            val health = health(node)
            node.start()
            node.gateway.execute(deposit("a-1"))
            eventually { node.context(BANK).subscriptions.paused().isNotEmpty() }
            health.sample()
            val mirror = health.health().component(NodeHealth.SUBSCRIPTIONS)!!.components.single()
                .component("Mirror.onDeposit")!!
            mirror.status.assert().isEqualTo(HealthStatus.DEGRADED)
            @Suppress("UNCHECKED_CAST")
            val paused = (mirror.details["paused"] as List<Map<String, Any?>>).single()
            paused["partition"].assert().isEqualTo(node.context(BANK).subscriptions.paused().single().partition)
            // The failure's class name, never its message.
            (paused["failure"] as String).assert().matches("[\\w.$]+")
            paused.keys.assert().doesNotContain("reason")
            // A pause waits for what it depends on: the node stays ready and live.
            health.readiness().status.assert().isEqualTo(HealthStatus.UP)
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
        }
}

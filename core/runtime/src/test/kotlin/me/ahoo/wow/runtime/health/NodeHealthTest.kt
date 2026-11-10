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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.runtime.node.BackendFactory
import me.ahoo.wow.runtime.node.NODE_SERVICE
import me.ahoo.wow.runtime.node.PlacedStores
import me.ahoo.wow.runtime.subscription.eventually
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The node's health (review R3.5-3): the readiness follows the node's state, its lease store, its event log and its
 * topics, and recovers with them; the liveness ignores a storage outage and fails only when the node is stuck.
 */
class NodeHealthTest : NodeHealthFixtures() {
    @Test
    fun `a running node should be ready and live, and not ready before it starts or once it stops`(): Unit =
        runBlocking {
            val node = node()
            val health = health(node)
            health.readiness().status.assert().isEqualTo(HealthStatus.DOWN)
            health.readiness().status(NodeHealth.NODE).assert().isEqualTo(HealthStatus.DOWN)
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)

            node.start()
            start(health)
            start(health)
            health.isRunning.assert().isTrue()
            eventually { health.readiness().status == HealthStatus.UP }
            node.gateway.execute(deposit("a-1"))
            val snapshotsOf = { health: ComponentHealth ->
                health.component(NodeHealth.SUBSCRIPTIONS)!!.components.single()
                    .component(me.ahoo.wow.api.result.Hop.SNAPSHOT_SUBSCRIPTION)!!
            }
            eventually {
                health.sample()
                snapshotsOf(health.health()).details["lag"] == 0L
            }
            val all = health.health()
            all.name.assert().isEqualTo(NodeHealth.WOW)
            all.status.assert().isEqualTo(HealthStatus.UP)
            all.components.map { it.name }.assert().containsExactly(
                NodeHealth.NODE,
                NodeHealth.OWNERSHIP,
                NodeHealth.STORES,
                NodeHealth.SECONDARY_STORES,
                NodeHealth.TOPICS,
                NodeHealth.SUBSCRIPTIONS,
                NodeHealth.EXECUTOR,
                NodeHealth.LIVENESS,
            )
            val group = all.component(NodeHealth.OWNERSHIP)!!.components.single()
            group.name.assert().isEqualTo("$NODE_SERVICE/$BANK")
            group.details["owned"].assert().isEqualTo(PARTITIONS)
            group.details["share"].assert().isEqualTo(PARTITIONS)
            group.details["handover"].assert().isEqualTo(false)
            val store = all.component(NodeHealth.STORES)!!.components.single()
            store.details["kind"].assert().isEqualTo(StoreActivity.LOG)
            store.details["failures"].assert().isEqualTo(0)
            all.component(NodeHealth.EXECUTOR)!!.details["maxInFlight"].assert().isEqualTo(4_096)
            // The snapshot subscription has caught up with the deposit.
            val snapshots = snapshotsOf(all)
            snapshots.details["lag"].assert().isEqualTo(0L)
            snapshots.details["parked"].assert().isEqualTo(0)
            all.component(NodeHealth.SUBSCRIPTIONS)!!.details.keys.assert().contains("sampledMillis")

            node.stop()
            health.readiness().status.assert().isEqualTo(HealthStatus.DOWN)
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
            health.close()
            health.isRunning.assert().isFalse()
        }

    @Test
    fun `only a store the write path needs should make the node not ready, naming it, and any other only be shown`(): Unit =
        runBlocking {
            val logs = ProbedStore("main", "postgres")
            val search = ProbedStore("search", "elasticsearch")
            val node = node(
                backends = BackendFactory {
                    backends.backends(it).copyWith(stores = PlacedStores(setOf(logs), setOf(search)))
                },
            )
            val health = health(node, HealthOptions(storeDownAfter = Duration.ZERO))
            node.start()
            health.sample()
            health.readiness().status.assert().isEqualTo(HealthStatus.UP)
            // An additional snapshot or search store down: the node serves its commands, and shows the store down.
            search.down = true
            health.sample()
            health.readiness().status.assert().isEqualTo(HealthStatus.UP)
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
            val secondary = health.health().component(NodeHealth.SECONDARY_STORES)!!
            secondary.status.assert().isEqualTo(HealthStatus.DOWN)
            secondary.component("store:search")!!.details["type"].assert().isEqualTo("elasticsearch")
            // The store of its logs down: the node is not ready, and says which store.
            search.down = false
            logs.down = true
            health.sample()
            health.readiness().status.assert().isEqualTo(HealthStatus.DOWN)
            health.readiness().component(NodeHealth.STORES)!!.component("store:main")!!.status.assert()
                .isEqualTo(HealthStatus.DOWN)
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
            node.stop()
            // The node closes no store: whoever opened them closes them, once nothing reads them any more.
            listOf(logs, search).forEach { it.closes.assert().isZero() }
        }

    @Test
    fun `a node on standby should be ready, its lease group marked standby until it is activated`(): Unit = runBlocking {
        val node = node()
        val health = health(node)
        node.start(standby = true)
        // It serves reads: ready, though it joins no lease group until it is activated.
        health.readiness().status.assert().isEqualTo(HealthStatus.UP)
        val group = health.readiness().component(NodeHealth.OWNERSHIP)!!.components.single()
        group.status.assert().isEqualTo(HealthStatus.UP)
        group.details["standby"].assert().isEqualTo(true)
        group.details["running"].assert().isEqualTo(false)

        node.activate("operator")
        eventually { health.readiness().status == HealthStatus.UP }
        health.readiness().component(NodeHealth.OWNERSHIP)!!.components.single().details.keys.assert()
            .doesNotContain("standby", "running")
        node.stop()
        health.readiness().status.assert().isEqualTo(HealthStatus.DOWN)
    }

    @Test
    fun `an unreachable lease store should make the node not ready until it answers again`(): Unit = runBlocking {
        val node = node()
        val health = health(node)
        node.start()
        health.sample()
        health.readiness().status.assert().isEqualTo(HealthStatus.UP)

        leases.down = true
        health.sample()
        val readiness = health.readiness()
        readiness.status.assert().isEqualTo(HealthStatus.DOWN)
        readiness.status(NodeHealth.OWNERSHIP).assert().isEqualTo(HealthStatus.DOWN)
        val group = readiness.component(NodeHealth.OWNERSHIP)!!.components.single()

        @Suppress("UNCHECKED_CAST")
        val leaseStore = group.details[NodeHealth.LEASE_STORE] as Map<String, Any?>
        leaseStore["status"].assert().isEqualTo("DOWN")
        leaseStore["lastError"].assert().isEqualTo("StorageUnavailableException")
        // The stores of the log answer: the liveness does not restart a node whose lease store is away.
        health.liveness().status.assert().isEqualTo(HealthStatus.UP)

        leases.down = false
        health.sample()
        // Failed lately, but answers again: degraded, and ready.
        health.readiness().status.assert().isEqualTo(HealthStatus.UP)
        health.health().status(NodeHealth.OWNERSHIP).assert().isEqualTo(HealthStatus.DEGRADED)
    }

    @Test
    fun `a store that is down should make the node not ready, and leave it live`(): Unit = runBlocking {
        val node = node()
        val health = health(node)
        node.start()
        start(health)
        eventually { health.readiness().status == HealthStatus.UP }

        log.down = true
        health.sample()
        val readiness = health.readiness()
        readiness.status.assert().isEqualTo(HealthStatus.DOWN)
        readiness.status(NodeHealth.STORES).assert().isEqualTo(HealthStatus.DOWN)
        val store = readiness.component(NodeHealth.STORES)!!.components.single()
        store.details["lastError"].assert().isEqualTo("StorageUnavailableException")
        store.details.keys.assert().contains("failingMillis")
        // A command fails with the store unavailable, and the kernel goes on: no restart storm.
        node.gateway.execute(deposit("a-1")).status.assert().isEqualTo(CommandResult.Status.FAILED)
        health.liveness().status.assert().isEqualTo(HealthStatus.UP)

        log.down = false
        health.sample()
        health.readiness().status.assert().isEqualTo(HealthStatus.UP)
        health.health().status(NodeHealth.STORES).assert().isEqualTo(HealthStatus.DEGRADED)
        // Once the window of the failures has passed, the store is up again.
        delay(5)
        health(node, HealthOptions(storeDownAfter = Duration.ZERO, storeWindow = 1.milliseconds)).health()
            .status(NodeHealth.STORES).assert().isEqualTo(HealthStatus.UP)
    }

    @Test
    fun `a store that does not answer should be down, and a kernel stuck on it should stay live`(): Unit =
        runBlocking {
            val node = node()
            val health = health(
                node,
                HealthOptions(
                    sampleInterval = 50.milliseconds,
                    storeDownAfter = 100.milliseconds,
                    stallTimeout = 100.milliseconds,
                ),
            )
            node.start()
            start(health)
            eventually { health.readiness().status == HealthStatus.UP }

            log.hang = CompletableDeferred()
            coroutineScope {
                val stuck = async { node.gateway.execute(deposit("a-1")) }
                eventually { node.context(BANK).executor.commandsInFlight == 1 }
                // Down once a probe timed out (after a second at least): a probe still under way is no failure.
                eventually { health.readiness().status == HealthStatus.DOWN }
                eventually {
                    health.readiness().component(NodeHealth.STORES)!!.components.single()
                        .details["lastError"] == "TimeoutException"
                }
                delay(200)
                // In flight with no progress, but the store does not answer: the kernel waits for it.
                health.liveness().status.assert().isEqualTo(HealthStatus.UP)
                log.hang!!.complete(Unit)
                stuck.await()
            }
            log.hang = null
            eventually { health.readiness().status == HealthStatus.UP }
        }

    @Test
    fun `with down-after zero a store should be down at its first failure, never while a probe is under way`(): Unit =
        runBlocking {
            val node = node()
            val health = health(node, HealthOptions(storeDownAfter = Duration.ZERO))
            node.start()
            eventually { health.readiness().status == HealthStatus.UP }

            // In flight: a probe that has not answered yet has not failed.
            log.hang = CompletableDeferred()
            coroutineScope {
                val probe = launch { health.sample() }
                eventually {
                    health.readiness().component(
                        NodeHealth.STORES
                    )!!.components.single().details.containsKey("probingMillis")
                }
                health.readiness().status.assert().isEqualTo(HealthStatus.UP)
                health.readiness().status(NodeHealth.STORES).assert().isNotEqualTo(HealthStatus.DOWN)

                // Timed out (after its timeout, a second at least): a failure, so down at once.
                probe.join()
                val timedOut = health.readiness()
                timedOut.status.assert().isEqualTo(HealthStatus.DOWN)
                timedOut.component(NodeHealth.STORES)!!.components.single().details["lastError"]
                    .assert().isEqualTo("TimeoutException")
            }
            log.hang!!.complete(Unit)
            log.hang = null

            // Recovers at its next success.
            health.sample()
            health.readiness().status.assert().isEqualTo(HealthStatus.UP)

            // Failed: down at the first failure.
            log.down = true
            health.sample()
            health.readiness().status(NodeHealth.STORES).assert().isEqualTo(HealthStatus.DOWN)
            log.down = false
            health.sample()
            health.readiness().status.assert().isEqualTo(HealthStatus.UP)
        }

    @Test
    fun `an ownership that keeps none of its share past the grace should be down`(): Unit = runBlocking {
        val node = node()
        val health = health(node, HealthOptions(handoverGrace = Duration.ZERO, storeDownAfter = 1.minutes))
        node.start()
        health.readiness().status.assert().isEqualTo(HealthStatus.UP)
        // The lease store stops renewing: the leases go overdue and the partitions stop here.
        leases.down = true
        eventually { node.context(BANK).ownership.owned.isEmpty() }
        val ownership = health.readiness().component(NodeHealth.OWNERSHIP)!!
        ownership.status.assert().isEqualTo(HealthStatus.DOWN)
        ownership.components.single().details["handover"].assert().isEqualTo(true)
        leases.down = false
        eventually { health.readiness().status == HealthStatus.UP }
    }

    @Test
    fun `options should refuse thresholds out of their range`() {
        HealthOptions().toString().assert().contains("storeDownAfter=15s")
        listOf(
            { HealthOptions(sampleInterval = Duration.ZERO) },
            { HealthOptions(storeDownAfter = (-1).milliseconds) },
            { HealthOptions(storeWindow = Duration.ZERO) },
            { HealthOptions(handoverGrace = (-1).milliseconds) },
            { HealthOptions(executorSaturation = 0.0) },
            { HealthOptions(executorSaturation = 1.5) },
            { HealthOptions(lagDegradedAt = 0) },
            { HealthOptions(parkedDegradedAt = 0) },
            { HealthOptions(stallTimeout = Duration.ZERO) },
        ).forEach { options ->
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> { options() }
        }
    }
}

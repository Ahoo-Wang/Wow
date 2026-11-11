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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.ExecutorOptions
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The node's liveness: a kernel stuck while the stores answer, a dispatcher or drivers that do not tick, and a reconcile
 * that does not end fail it; a slow store that answers does not.
 */
class NodeLivenessTest : NodeHealthFixtures() {
    @Test
    fun `a kernel stuck while the stores answer should fail the liveness`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val node = node(account(gate), fastNodeOptions().copyWith(executor = ExecutorOptions(maxInFlight = 1)))
        val health = health(node, HealthOptions(storeDownAfter = Duration.ZERO, stallTimeout = 100.milliseconds))
        node.start()
        start(health)
        coroutineScope {
            val stuck = async { node.gateway.execute(deposit("a-1")) }
            eventually { node.context(BANK).executor.commandsInFlight == 1 }
            // One command in flight fills a bound of one.
            health.health().status(NodeHealth.EXECUTOR).assert().isEqualTo(HealthStatus.DEGRADED)
            eventually { progressMillis(health) >= health.options.stallTimeout.inWholeMilliseconds }
            health.sample()
            val liveness = health.liveness()
            liveness.status.assert().isEqualTo(HealthStatus.DOWN)
            (liveness.details["stalls"] as List<*>).assert().contains(NodeHealth.STALLED_KERNEL)
            gate.complete(Unit)
            stuck.await()
        }
        health.liveness().status.assert().isEqualTo(HealthStatus.UP)
    }

    /**
     * The liveness of a running node whose dispatcher and drivers are threads of their own, each held for 400 ms by
     * [hold] (given the dispatcher's and the drivers' executors), against a stall timeout of 100 ms: the stalls it
     * listed once down with stalls that satisfy [listed], before it was up again.
     */
    private suspend fun stallsWhile(
        listed: (List<*>) -> Boolean = { true },
        hold: (ExecutorService, ExecutorService) -> Unit,
    ): List<*> {
        val node = node()
        node.start()
        val dispatcher = Executors.newSingleThreadExecutor()
        val drivers = Executors.newSingleThreadExecutor()
        try {
            val options = HealthOptions(stallTimeout = 100.milliseconds)
            val health = healthOf(node, options) {
                NodeHealth(node, it, options, dispatcher.asCoroutineDispatcher(), drivers.asCoroutineDispatcher())
            }
            start(health)
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
            hold(dispatcher, drivers)
            var stalls: List<*> = emptyList<Any>()
            eventually {
                val liveness = health.liveness()
                stalls = liveness.details["stalls"] as? List<*> ?: emptyList<Any>()
                liveness.status == HealthStatus.DOWN && liveness.details["stalled"] == stalls.first() && listed(stalls)
            }
            eventually { health.liveness().status == HealthStatus.UP }
            health.liveness().details.containsKey("stalls").assert().isFalse()
            return stalls
        } finally {
            dispatcher.shutdownNow()
            drivers.shutdownNow()
        }
    }

    @Test
    fun `a dispatcher that does not tick should fail the liveness, the drivers ticking on`(): Unit = runBlocking {
        stallsWhile { dispatcher, _ -> dispatcher.execute { Thread.sleep(HOLD_MILLIS) } }
            .assert().containsExactly(NodeHealth.STALLED_DISPATCHER)
    }

    @Test
    fun `drivers that run nothing should fail the liveness, as a wedged virtual-thread executor does`(): Unit =
        runBlocking {
            // The one driver thread runs nothing else: no store call of the node would return either.
            stallsWhile { _, drivers -> drivers.execute { Thread.sleep(HOLD_MILLIS) } }
                .assert().containsExactly(NodeHealth.STALLED_DRIVERS)
        }

    @Test
    fun `a dispatcher and drivers stalled at once should both be listed, the dispatcher first`(): Unit = runBlocking {
        // Each held for 400 ms against 100: a liveness while both are held lists both, in its fixed order.
        stallsWhile({ it.size == 2 }) { dispatcher, drivers ->
            dispatcher.execute { Thread.sleep(HOLD_MILLIS) }
            drivers.execute { Thread.sleep(HOLD_MILLIS) }
        }.assert().containsExactly(NodeHealth.STALLED_DISPATCHER, NodeHealth.STALLED_DRIVERS)
    }

    @Test
    fun `a reconcile that does not end should fail the liveness, naming its group, until it ends`(): Unit =
        runBlocking {
            val node = node()
            val health = health(node, HealthOptions(storeDownAfter = 1.minutes, stallTimeout = 300.milliseconds))
            node.start()
            start(health)
            val ownership = node.context(BANK).ownership
            eventually { ownership.owned.size == PARTITIONS }
            // The partitions are lost, and acquiring them again never returns: a store call on wedged drivers.
            val gate = CompletableDeferred<Unit>()
            val asked = CompletableDeferred<Unit>()
            leases.acquiring = gate
            leases.asked = asked
            leases.losing = true
            // A reconcile is acquiring a lost partition, not merely running: one that found nothing lost yet ends.
            asked.await()
            leases.losing = false
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
            eventually {
                val liveness = health.liveness()
                liveness.status == HealthStatus.DOWN &&
                    NodeHealth.STALLED_RECONCILE in liveness.details["stalls"] as List<*>
            }
            val liveness = health.liveness()
            liveness.details["group"].assert().isEqualTo(ownership.group.name)
            leases.acquiring = null
            gate.complete(Unit)
            eventually { ownership.owned.size == PARTITIONS }
            eventually { health.liveness().status == HealthStatus.UP }
        }

    @Test
    fun `a reconcile waiting on a slow store that answers should leave the node live`(): Unit = runBlocking {
        val node = node()
        val health = health(node, HealthOptions(storeDownAfter = 1.minutes, stallTimeout = 5.seconds))
        node.start()
        start(health)
        val ownership = node.context(BANK).ownership
        eventually { ownership.owned.size == PARTITIONS }
        // The partitions are lost, and a reconcile acquires them again from a store that takes a while to answer.
        val answer = CompletableDeferred<Unit>()
        val asked = CompletableDeferred<Unit>()
        leases.acquiring = answer
        leases.asked = asked
        leases.losing = true
        asked.await()
        leases.losing = false
        // That reconcile waits on the store now, well inside the stall timeout: the node stays live throughout, until the
        // ownership has been reconciling for the slow store's time.
        eventually {
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)
            (ownership.reconcilingFor ?: Duration.ZERO) >= SLOW_STORE
        }
        health.liveness().status.assert().isEqualTo(HealthStatus.UP)
        // The store answers: the reconcile ends with the partitions back, and the node is still live.
        leases.acquiring = null
        answer.complete(Unit)
        eventually { ownership.owned.size == PARTITIONS && ownership.reconcilingFor == null }
        health.liveness().status.assert().isEqualTo(HealthStatus.UP)
    }
}

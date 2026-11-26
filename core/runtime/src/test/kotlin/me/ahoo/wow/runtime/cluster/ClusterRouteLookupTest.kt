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

package me.ahoo.wow.runtime.cluster

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours

/**
 * The routes a node reads once more before a command whose route names no owner runs without the lease, as the
 * router asks for them: never while the command has a route, and the owner they name now gets the command.
 */
class ClusterRouteLookupTest : ClusterTestCase() {
    private val looked = CopyOnWriteArrayList<ObservedEvent.RouteLookedUp>()
    private val observer = object : Observer {
        override fun observed(event: ObservedEvent) {
            if (event is ObservedEvent.RouteLookedUp) looked += event
        }
    }

    /** A channel that records where each forward went and refuses to send it: the command then runs here. */
    private val sentTo = CopyOnWriteArrayList<String>()
    private val recording = ClusterChannel { address, _ ->
        sentTo += address
        throw ClusterChannelException("Recorded, not sent.", unsent = true)
    }

    @Test
    fun `a command with a route never reads the routes again`(): Unit = runBlocking {
        val (one) = cluster.start("node-1", "node-2")
        cluster.awaitStable()
        val router = PartitionedRouter(listOf(one.ownership), recording, cluster.options, observer)
        // Some held here, some forwarded to node-2: every one has a route.
        val ids = (0 until 16).map { "acc-$it" }
        ids.forEach { id ->
            val command = one.command(id)
            router.route(one.model, command, null) { one.coordinator.executeHere(one.model, command, null) }
        }
        ids.any { ownerOf(it) == "node-2" }.assert().isTrue()
        router.lookupsOf(CONTEXT)!!.reads.assert().isEqualTo(0L)
        looked.assert().isEmpty()
    }

    @Test
    fun `a command whose routes name no owner reads them once and goes to the owner they name now`(): Unit =
        runBlocking {
            // Routes read only when asked: node-1's stay as they were when it held every partition alone.
            cluster.close()
            cluster = TestCluster(options = fastOptionsWith(routeRefreshInterval = 1.hours))
            val one = cluster.start("node-1").single()
            eventually { one.ownership.owned.size == cluster.partitions }
            one.ownership.refreshRoutes()
            one.ownership.routes.owners.size.assert().isEqualTo(cluster.partitions)
            val two = cluster.start("node-2").single()
            val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
            val partition = cluster.partitionOf(id)
            eventually { cluster.owners()[partition] == "node-2" }
            // Its stale route names node-1 itself: no owner to send the command to.
            one.ownership.routes.owners[partition]?.node.assert().isEqualTo("node-1")
            one.ownership.forwardTarget(partition).assert().isNull()

            val router = PartitionedRouter(listOf(one.ownership), recording, cluster.options, observer)
            val command = one.command(id)
            router.route(one.model, command, null) { one.coordinator.executeHere(one.model, command, null) }

            sentTo.assert().containsExactly(two.ownership.address)
            val lookups = router.lookupsOf(CONTEXT)!!
            listOf(lookups.reads, lookups.forwards).assert().containsExactly(1L, 1L)
            looked.map { it.outcome to it.read }.assert()
                .containsExactly(ObservedEvent.RouteLookedUp.Outcome.OWNER to true)
        }

    /** [fastOptions] with another [routeRefreshInterval]. */
    private fun fastOptionsWith(routeRefreshInterval: kotlin.time.Duration): ClusterOptions = fastOptions().let {
        ClusterOptions(
            leaseTtl = it.leaseTtl,
            renewInterval = it.renewInterval,
            memberTtl = it.memberTtl,
            heartbeatInterval = it.heartbeatInterval,
            reconcileInterval = it.reconcileInterval,
            routeRefreshInterval = routeRefreshInterval,
            forwardTimeout = it.forwardTimeout,
            maxInFlightPerNode = it.maxInFlightPerNode,
            drainTimeout = it.drainTimeout,
        )
    }
}

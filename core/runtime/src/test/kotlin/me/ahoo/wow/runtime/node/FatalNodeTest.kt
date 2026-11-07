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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.Hrw
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.health.HealthStatus
import me.ahoo.wow.runtime.health.NodeHealth
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.Fatal.Component
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.Lease
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A node that meets a fatal error (X-80): an `OutOfMemoryError` a saga's lane met once left its node live and holding
 * its partitions, the lane dead, the entries stuck and the ledger empty. Now the node is fatal: its liveness and
 * readiness are down for good, it stops, and the other node takes its partitions over from the checkpoints.
 */
class FatalNodeTest {
    private val channel = LoopbackClusterChannel()
    private val backends = InMemoryBackends(partitions = PARTITIONS)
    private val nodes = CopyOnWriteArrayList<ServiceNode>()

    /** The entries each node's audit handled, by node. */
    private val audited = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    private val observed = CopyOnWriteArrayList<ObservedEvent>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    /** A node whose audit fails with an `OutOfMemoryError` for the aggregates [exhausts] names. */
    private fun node(name: String, exhausts: (String) -> Boolean = { false }): ServiceNode = ServiceNode(
        NODE_SERVICE,
        name,
        "$name.internal:9090",
        listOf(accountModel(BANK)),
        accountsManifest(BANK),
        backends,
        channel,
        options = fastNodeOptions(),
        subscriptions = SubscriptionFactory { context ->
            listOf(
                Subscription(AUDIT, SubscriptionFilter(context.context)) { entry ->
                    if (exhausts(entry.ref.id)) {
                        throw OutOfMemoryError("not really")
                    }
                    audited.computeIfAbsent(name) { CopyOnWriteArrayList() } += entry.ref.id
                },
            )
        },
        observer = object : Observer {
            override fun observed(event: ObservedEvent) {
                if (event is ObservedEvent.Fatal) observed += event
            }
        },
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private fun deposit(id: String) = ServerCommandRequest(
        context = BANK,
        aggregate = "account",
        command = "Deposit",
        body = CommandBody.of("""{"amount":1}"""),
        caller = Caller("t"),
        aggregateId = id,
        wait = WaitPlan.COMMITTED,
    )

    @Test
    fun `a node whose lane meets a fatal error should be down for good and hand its partitions to the other`(): Unit =
        runBlocking {
            val names = listOf("node-1", "node-2")
            // An aggregate node-1 owns while both run.
            val id = (0 until 64).map { "acc-$it" }.first { id ->
                Hrw.owner(Partitioner.partitionOf(accountModel(BANK).type.ref("t", id), PARTITIONS), names) == "node-1"
            }
            val one = node("node-1") { it == id }
            val two = node("node-2")
            one.start()
            two.start()
            eventually(15.seconds) {
                one.context(BANK).ownership.owned == Hrw.partitionsOf("node-1", names, PARTITIONS) &&
                    two.context(BANK).ownership.owned == Hrw.partitionsOf("node-2", names, PARTITIONS)
            }
            val health = NodeHealth(one, NodeSampler(one, 1.seconds))
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)

            one.gateway.execute(deposit(id)).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            eventually(15.seconds) { one.state == ServiceNode.State.STOPPED }

            val fatal = one.fatals.first!!
            fatal.component.assert().isEqualTo(Component.SUBSCRIPTION)
            fatal.name.assert().isEqualTo("$NODE_SERVICE/$BANK/$AUDIT")
            fatal.error.assert().isEqualTo(OutOfMemoryError::class.java.name)
            observed.assert().containsExactly(
                ObservedEvent.Fatal(
                    Component.SUBSCRIPTION,
                    "$NODE_SERVICE/$BANK/$AUDIT",
                    OutOfMemoryError::class.java.name
                ),
            )
            health.liveness().status.assert().isEqualTo(HealthStatus.DOWN)
            health.readiness().status.assert().isEqualTo(HealthStatus.DOWN)
            // Its entry is neither a business failure nor lost: no ledger entry, and the other node handles it.
            backends.opened.getValue(
                BANK
            ).ledger.isParked(AUDIT, accountModel(BANK).type.ref("t", id)).assert().isFalse()
            eventually(15.seconds) { two.context(BANK).ownership.owned == (0 until PARTITIONS).toSet() }
            eventually(15.seconds) { audited["node-2"].orEmpty().contains(id) }
            audited["node-1"].orEmpty().assert().doesNotContain(id)
            // The host's own stop changes nothing.
            one.stop()
            health.liveness().status.assert().isEqualTo(HealthStatus.DOWN)
        }

    @Test
    fun `a fatal error that escapes a loop of the node should be recorded as that loop's, and stop the node`(): Unit =
        runBlocking {
            // The renewals' loop of the ownership: an error that ends it is caught by the node's handler.
            val exhausted = AtomicBoolean()
            val leasing = BackendFactory { context ->
                val opened = backends.backends(context)
                ContextBackends(
                    log = opened.log,
                    checkpoints = opened.checkpoints,
                    leases = object : LeaseStore by opened.leases {
                        override suspend fun renew(
                            group: String,
                            partitions: Collection<Int>,
                            owner: String,
                            ttl: Duration
                        ): List<Lease> {
                            if (exhausted.get()) throw OutOfMemoryError("not really")
                            return opened.leases.renew(group, partitions, owner, ttl)
                        }
                    },
                    ledger = opened.ledger,
                )
            }
            val node = ServiceNode(
                NODE_SERVICE,
                "node-1",
                "node-1.internal:9090",
                listOf(accountModel(BANK)),
                accountsManifest(BANK),
                leasing,
                channel,
                options = fastNodeOptions(),
            ).also {
                nodes += it
                channel.bind(it.address, it.forwardHandler)
            }
            node.start()
            eventually(15.seconds) { node.context(BANK).ownership.owned.isNotEmpty() }
            exhausted.set(true)
            eventually(15.seconds) { node.state == ServiceNode.State.STOPPED }
            node.fatals.first!!.component.assert().isEqualTo(Component.OWNERSHIP)
            node.fatals.first!!.name.assert().isEqualTo("$NODE_SERVICE/$BANK")
            node.fatals.first!!.partition.assert().isNull()
        }

    private companion object {
        const val BANK = "bank"
        const val PARTITIONS = 8
        const val AUDIT = "Audit.onDeposit"
    }
}

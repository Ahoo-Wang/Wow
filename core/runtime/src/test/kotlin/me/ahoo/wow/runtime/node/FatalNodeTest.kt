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

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.Hrw
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.cluster.fastOptions
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
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A node that meets a fatal error (X-80): an `OutOfMemoryError` a saga's lane met once left its node live and holding
 * its partitions, the lane dead, the entries stuck and the ledger empty. Now the node is fatal: its liveness and
 * readiness are down for good, it steps out of its lease group, and the other node takes its partitions over from the
 * checkpoints; its gateway forwards what it is still given. A hand-off that fails again or outlasts its bound renews no
 * lease, so the other node takes the partitions once the leases expire.
 */
class FatalNodeTest {
    private val channel = LoopbackClusterChannel()
    private val backends = InMemoryBackends(partitions = PARTITIONS)
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val names = listOf("node-1", "node-2")

    /** The entries each node's audit handled, and the commands each node decided, by node. */
    private val audited = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    private val decided = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    private val observed = CopyOnWriteArrayList<ObservedEvent>()

    /** While set, node-1's lease store fails a release with an `OutOfMemoryError`; while [hangs], never answers it. */
    private val releaseFails = AtomicBoolean()
    private val hangs = AtomicBoolean()

    @AfterEach
    fun stop(): Unit = runBlocking {
        releaseFails.set(false)
        hangs.set(false)
        nodes.forEach { it.stop() }
    }

    /** node-1's lease store: the shared one, whose releases fail or hang as [releaseFails] and [hangs] say. */
    private val failing = BackendFactory { context ->
        val opened = backends.backends(context)
        ContextBackends(
            log = opened.log,
            checkpoints = opened.checkpoints,
            leases = object : LeaseStore by opened.leases {
                override suspend fun release(group: String, partition: Int, owner: String): Boolean {
                    if (releaseFails.get()) throw OutOfMemoryError("not really, again")
                    if (hangs.get()) awaitCancellation()
                    return opened.leases.release(group, partition, owner)
                }
            },
            ledger = opened.ledger,
            snapshots = opened.snapshots,
            reservations = opened.reservations,
        )
    }

    /** A node whose audit fails with an `OutOfMemoryError` for the aggregates [exhausts] names. */
    private fun node(
        name: String,
        backends: BackendFactory = this.backends,
        options: ServiceNodeOptions = fastNodeOptions(),
        exhausts: (String) -> Boolean = { false },
    ): ServiceNode = ServiceNode(
        NODE_SERVICE,
        name,
        "$name.internal:9090",
        listOf(accountModel(BANK, decided.computeIfAbsent(name) { CopyOnWriteArrayList() })),
        accountsManifest(BANK),
        backends,
        channel,
        options = options,
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

    private fun ref(id: String): AggregateRef = accountModel(BANK).type.ref("t", id)

    /** The [n]th account node-1 owns while both nodes run. */
    private fun ownedByOne(n: Int = 0): String = (0 until 256).map { "acc-$it" }.filter { id ->
        Hrw.owner(Partitioner.partitionOf(ref(id), PARTITIONS), names) == "node-1"
    }[n]

    private suspend fun startBoth(one: ServiceNode, two: ServiceNode) {
        one.start()
        two.start()
        eventually(15.seconds) {
            one.context(BANK).ownership.owned == Hrw.partitionsOf("node-1", names, PARTITIONS) &&
                two.context(BANK).ownership.owned == Hrw.partitionsOf("node-2", names, PARTITIONS)
        }
    }

    @Test
    fun `a node whose lane meets a fatal error should be down for good and hand its partitions to the other`(): Unit =
        runBlocking {
            val id = ownedByOne()
            val one = node("node-1") { it == id }
            val two = node("node-2")
            startBoth(one, two)
            val health = NodeHealth(one, NodeSampler(one, 1.seconds))
            health.liveness().status.assert().isEqualTo(HealthStatus.UP)

            one.gateway.execute(deposit(id)).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            eventually(15.seconds) { two.context(BANK).ownership.owned == (0 until PARTITIONS).toSet() }

            val fatal = one.fatals.first!!
            fatal.component.assert().isEqualTo(Component.SUBSCRIPTION)
            fatal.name.assert().isEqualTo("$NODE_SERVICE/$BANK/$AUDIT")
            fatal.error.assert().isEqualTo(OutOfMemoryError::class.java.name)
            observed.assert().containsExactly(
                ObservedEvent.Fatal(Component.SUBSCRIPTION, fatal.name, OutOfMemoryError::class.java.name),
            )
            health.liveness().status.assert().isEqualTo(HealthStatus.DOWN)
            health.readiness().status.assert().isEqualTo(HealthStatus.DOWN)
            one.context(BANK).ownership.owned.assert().isEmpty()
            // Its entry is neither a business failure nor lost: no ledger entry, and the other node handles it.
            backends.opened.getValue(BANK).ledger.isParked(SubscriptionId(BANK, AUDIT), ref(id)).assert().isFalse()
            eventually(15.seconds) { audited["node-2"].orEmpty().contains(id) }
            audited["node-1"].orEmpty().assert().doesNotContain(id)
            // The gateway stays open: a command node-1 is still given goes to the partition's new owner.
            val next = ownedByOne(1)
            eventually(15.seconds) {
                one.context(BANK).ownership.routes.owners[Partitioner.partitionOf(ref(next), PARTITIONS)]?.node ==
                    "node-2"
            }
            val before = decided.getValue("node-1").size
            one.gateway.execute(deposit(next)).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            decided.getValue("node-1").size.assert().isEqualTo(before)
            one.state.assert().isEqualTo(ServiceNode.State.RUNNING)
            // Nothing in the process brings it back, the host's stop included.
            one.stop()
            health.liveness().status.assert().isEqualTo(HealthStatus.DOWN)
        }

    @Test
    fun `a fatal error that escapes a loop of the node should be recorded as that loop's, and the node step out`(): Unit =
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
                            ttl: Duration,
                        ): List<Lease> {
                            if (exhausted.get()) throw OutOfMemoryError("not really")
                            return opened.leases.renew(group, partitions, owner, ttl)
                        }
                    },
                    ledger = opened.ledger,
                )
            }
            val node = node("node-1", backends = leasing)
            node.start()
            eventually(15.seconds) { node.context(BANK).ownership.owned.isNotEmpty() }
            exhausted.set(true)
            eventually(15.seconds) { node.fatals.isFatal && node.context(BANK).ownership.owned.isEmpty() }
            node.fatals.first!!.component.assert().isEqualTo(Component.OWNERSHIP)
            node.fatals.first!!.name.assert().isEqualTo("$NODE_SERVICE/$BANK")
            node.fatals.first!!.partition.assert().isNull()
        }

    @Test
    fun `a hand-off that meets a fatal error again should renew no lease, so the other node takes them on expiry`(): Unit =
        runBlocking {
            val id = ownedByOne()
            val one = node("node-1", backends = failing) { it == id }
            val two = node("node-2")
            startBoth(one, two)
            // The hand-off needs memory too: every release of node-1 fails from now on.
            releaseFails.set(true)
            one.gateway.execute(deposit(id))

            // Its leases lapse, not renewed: node-2 takes every partition, and handles the entry.
            eventually(15.seconds) { two.context(BANK).ownership.owned == (0 until PARTITIONS).toSet() }
            eventually(15.seconds) { audited["node-2"].orEmpty().contains(id) }
            // The release's error is the node's too, recorded once the step-out's bounded clean-up ends.
            eventually(15.seconds) { one.fatals.count >= 2 }
            one.context(BANK).ownership.owned.assert().isEmpty()
        }

    @Test
    fun `a hand-off that outlasts its bound should renew no lease, so the other node takes them on expiry`(): Unit =
        runBlocking {
            val id = ownedByOne()
            val bounded = fastNodeOptions().copyWith(
                cluster = fastOptions(drainTimeout = 1.seconds, reconcileTimeout = 2.seconds)
            )
            val one = node("node-1", backends = failing, options = bounded) { it == id }
            val two = node("node-2")
            startBoth(one, two)
            // No release of node-1 answers: its hand-off cannot end.
            hangs.set(true)
            one.gateway.execute(deposit(id))

            eventually(20.seconds) { two.context(BANK).ownership.owned == (0 until PARTITIONS).toSet() }
            eventually(15.seconds) { audited["node-2"].orEmpty().contains(id) }
            one.fatals.count.assert().isEqualTo(1)
        }

    @Test
    fun `a node that met a fatal error before it started should not start, nor one on standby be activated`(): Unit =
        runBlocking {
            val early = node("node-1")
            early.fatals.record(Component.NODE, "background", OutOfMemoryError("not really"))
            assertThrows<IllegalStateException> { runBlocking { early.start() } }.message.assert().contains(
                "fatal error"
            )

            val standby = node("node-2")
            standby.start(standby = true)
            standby.fatals.record(Component.NODE, "background", OutOfMemoryError("not really"))
            assertThrows<IllegalStateException> {
                runBlocking {
                    standby.activate(
                        "ops"
                    )
                }
            }.message.assert().contains("fatal error")
            standby.context(BANK).ownership.isRunning.assert().isFalse()
        }

    private companion object {
        const val BANK = "bank"
        const val PARTITIONS = 8
        const val AUDIT = "Audit.onDeposit"
    }
}

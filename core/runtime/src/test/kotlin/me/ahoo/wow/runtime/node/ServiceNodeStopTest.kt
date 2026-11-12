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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.binding.SHOP
import me.ahoo.wow.runtime.binding.SHOP_SERVICE
import me.ahoo.wow.runtime.binding.cartModel
import me.ahoo.wow.runtime.binding.orderModel
import me.ahoo.wow.runtime.binding.shopManifest
import me.ahoo.wow.runtime.cluster.Account
import me.ahoo.wow.runtime.cluster.Deposit
import me.ahoo.wow.runtime.cluster.Deposited
import me.ahoo.wow.runtime.cluster.Hrw
import me.ahoo.wow.runtime.cluster.RecordingLeases
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.LeaseStore
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A node's stop: in order (hand off, let the waits end, close the gateway, the executors and the logs), once the
 * commands still executing have settled, past a decide still running at the drain timeout, and with each context's
 * hand-off run to its end, every part of it, whatever another's does.
 */
class ServiceNodeStopTest : ServiceNodeFixtures() {
    @Test
    fun `a node stops in order - hand off, let the waits end, close the gateway, the executors and the logs`(): Unit =
        runBlocking {
            val events = CopyOnWriteArrayList<String>()
            val inMemory = InMemoryBackends()
            val backends = BackendFactory { context ->
                inMemory.backends(context).let { it.copyWith(leases = RecordingLeases(it.leases, events)) }
            }
            val entered = CompletableDeferred<Unit>()
            val stuck = SubscriptionFactory { context ->
                listOf(
                    Subscription(PROJECTOR, SubscriptionFilter(context.context)) {
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    },
                )
            }
            lateinit var node: ServiceNode
            node = node(
                "node-1",
                backends = backends,
                options = fastNodeOptions().copyWith(subscription = testOptions(stopTimeout = 100.milliseconds)),
                subscriptions = stuck,
                listener = { step -> events += "$step ${node.waits.size} ${node.state}" },
            )
            node.start()
            val timeout = 2.seconds
            val waiting = async(Dispatchers.Default) {
                node.gateway.execute(deposit(id = "a-1", wait = WaitPlan.of(Hop.handled(PROJECTOR)).within(timeout)))
            }
            entered.await()
            node.waits.size.assert().isEqualTo(1)

            val started = System.nanoTime()
            node.stop()
            val elapsed = ((System.nanoTime() - started) / 1_000_000).milliseconds

            val result = waiting.await()
            result.status.assert().isEqualTo(CommandResult.Status.FAILED)
            result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
            result.hop.assert().isEqualTo(Hop.committed(BANK))
            // Stopping waited for the wait, which ended at its timeout, not sooner.
            elapsed.assert().isGreaterThan(timeout - 500.milliseconds)
            val releases = events.filter { it.startsWith("release ") }
            releases.size.assert().isEqualTo(8)
            events.drop(releases.size).assert().containsExactly(
                "HANDED_OFF 1 STOPPING",
                "WAITS_ENDED 0 STOPPING",
                "GATEWAY_CLOSED 0 STOPPING",
                "EXECUTIONS_ENDED 0 STOPPING",
                "EXECUTORS_CLOSED 0 STOPPING",
                "LOGS_CLOSED 0 STOPPING",
            )
            node.state.assert().isEqualTo(ServiceNode.State.STOPPED)

            // The executor refuses, and the append queue takes nothing.
            val bank = node.context(BANK)
            val model = bank.models.first()
            val late = Command("c-late", "r-late", model.type.ref(TENANT, "a-2"), Deposit())
            bank.coordinator.executeHere(model, late, null).committed.error!!.code
                .assert().isEqualTo(FrameworkCodes.NODE_CLOSING)
        }

    @Test
    fun `a node stops only once the commands still executing have settled`(): Unit = runBlocking {
        val backends = InMemoryBackends(partitions = 16)
        val options = fastNodeOptions().copyWith(cluster = fastOptions(forwardTimeout = 10.seconds))
        val decided = CopyOnWriteArrayList<String>()
        val steps = CopyOnWriteArrayList<ServiceNode.Step>()
        val one = node("node-1", backends = backends, options = options, listener = { steps += it })
        val two = node("node-2", backends = backends, decided = decided, options = options)
        one.start()
        two.start()
        val names = listOf("node-1", "node-2")
        eventually(15.seconds) {
            one.context(BANK).ownership.routes.owners.mapValues { it.value.node } == Hrw.assignment(names, 16)
        }
        val id = (0 until 64).map { "acc-$it" }.first {
            Hrw.owner(Partitioner.partitionOf(AggregateType(BANK, "account").ref(TENANT, it), 16), names) == "node-2"
        }
        replyGate = CompletableDeferred()

        // Its caller goes away while node-2's reply is held: the command goes on executing in node-1's coordinator.
        val caller = launch(Dispatchers.Default) {
            one.gateway.execute(deposit(id = id, wait = WaitPlan.COMMITTED.within(10.seconds)))
        }
        eventually { decided.isNotEmpty() }
        caller.cancelAndJoin()
        one.waits.size.assert().isEqualTo(0)
        one.context(BANK).coordinator.executing.assert().isEqualTo(1)

        val stopping = async(Dispatchers.Default) { one.stop() }
        // The gateway closed: the stop now joins the execution node-2's held reply keeps running.
        eventually { ServiceNode.Step.GATEWAY_CLOSED in steps }
        stopping.isCompleted.assert().isFalse()
        steps.assert().doesNotContain(ServiceNode.Step.EXECUTIONS_ENDED)

        replyGate!!.complete(Unit)
        withTimeout(10.seconds) { stopping.await() }
        steps.takeLast(3).assert().containsExactly(
            ServiceNode.Step.EXECUTIONS_ENDED,
            ServiceNode.Step.EXECUTORS_CLOSED,
            ServiceNode.Step.LOGS_CLOSED,
        )
        one.context(BANK).coordinator.executing.assert().isEqualTo(0)
        decided.assert().hasSize(1)
    }

    @Test
    fun `a context whose stop fails should not cancel the other context's, and its failure should be thrown`(): Unit =
        runBlocking {
            val inMemory = InMemoryBackends()
            val failed = CompletableDeferred<Unit>()
            val backends = BackendFactory { context ->
                inMemory.backends(context).let { opened ->
                    val leases = opened.leases
                    opened.copyWith(
                        leases = if (context == BANK) {
                            snapshotsFailToLeave(leases, failed)
                        } else {
                            // The ledger's partitions are released only once the bank's stop has failed.
                            object : LeaseStore by leases {
                                override suspend fun release(group: String, partition: Int, owner: String): Boolean {
                                    failed.await()
                                    delay(RELEASE_AFTER)
                                    return leases.release(group, partition, owner)
                                }
                            }
                        },
                    )
                }
            }
            val node = node("node-1", BANK, LEDGER, backends = backends)
            node.start()
            val ledger = inMemory.opened.getValue(LEDGER).leases
            val group = "$NODE_SERVICE/$LEDGER"
            eventually { ledger.owners(group).count { it.owner == "node-1" } == 8 }
            ledger.members(NODE_SERVICE).map { it.node }.assert().contains("node-1")

            val thrown = runCatching { node.stop() }.exceptionOrNull()

            thrown.assert().isInstanceOf(StopFailed::class.java)
            // The ledger's stop ran to its end all the same: it left its members and released every partition.
            ledger.members(NODE_SERVICE).map { it.node }.assert().doesNotContain("node-1")
            ledger.owners(group).filter { it.owner == "node-1" }.assert().isEmpty()
            node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
        }

    @Test
    fun `a context's stop should run past a part that failed, its access walks left after its snapshots failed`(): Unit =
        runBlocking {
            val inMemory = InMemoryBackends(partitions = 4)
            val backends = BackendFactory { context ->
                inMemory.backends(context).let { it.copyWith(leases = snapshotsFailToLeave(it.leases)) }
            }
            val node = ServiceNode(
                SHOP_SERVICE,
                "node-1",
                "node-1.internal:9090",
                listOf(orderModel(), cartModel()),
                shopManifest(),
                backends,
                channel,
                options = fastNodeOptions(),
            ).also { nodes["node-1"] = it }
            node.start()
            val leases = inMemory.opened.getValue(SHOP).leases
            val walkers = "$SHOP_SERVICE/$SHOP#access"
            eventually { leases.members(walkers).any { it.node == "node-1" } }

            val thrown = runCatching { node.stop() }.exceptionOrNull()

            thrown.assert().isInstanceOf(StopFailed::class.java)
            // The access walks, stopped after the snapshots, were stopped all the same: they left their members.
            leases.members(walkers).map { it.node }.assert().doesNotContain("node-1")
        }

    /**
     * [leases] on which a context's snapshot maintenance fails to leave its state revisions, the part of its stop after
     * its partitions' hand-off; [failed] is completed first.
     */
    private fun snapshotsFailToLeave(
        leases: LeaseStore,
        failed: CompletableDeferred<Unit> = CompletableDeferred(),
    ): LeaseStore = object : LeaseStore by leases {
        override suspend fun leave(service: String, node: String) {
            if ("#snapshot/" in service) {
                failed.complete(Unit)
                throw StopFailed("$service could not be left")
            }
            leases.leave(service, node)
        }
    }

    class StopFailed(message: String) : RuntimeException(message)

    /** A node whose deposits never finish deciding, by [decide]: its stop still returns, past the drain timeout. */
    private fun stopsPastStuckDecide(decide: suspend () -> Unit): Unit = runBlocking {
        val deciding = CompletableDeferred<Unit>()
        val stuck = { context: String ->
            aggregate<Account>(context, "account") {
                initial { Account() }
                decideSuspending<Deposit>(Creation.ALLOWED) { _, body ->
                    deciding.complete(Unit)
                    decide()
                    Decision.events(Deposited(body.amount))
                }
                evolve<Deposited> { balance += it.amount }
            }
        }
        val drain = 200.milliseconds
        val options = fastNodeOptions().copyWith(
            cluster = fastOptions(drainTimeout = drain),
            wait = WaitOptions(maxTimeout = 1.seconds, defaultTimeout = 1.seconds),
        )
        val steps = CopyOnWriteArrayList<ServiceNode.Step>()
        val node = node("node-1", model = stuck, options = options, listener = { steps += it })
        node.start()
        val result = node.gateway.execute(deposit(id = "a-1", wait = WaitPlan.COMMITTED.within(100.milliseconds)))
        result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        deciding.await()
        node.context(BANK).coordinator.executing.assert().isEqualTo(1)

        // Before, the executors waited without a bound for the very commands the coordinators cancel after them.
        withTimeout(10.seconds) { node.stop() }
        steps.takeLast(3).assert().containsExactly(
            ServiceNode.Step.EXECUTIONS_ENDED,
            ServiceNode.Step.EXECUTORS_CLOSED,
            ServiceNode.Step.LOGS_CLOSED,
        )
        node.state.assert().isEqualTo(ServiceNode.State.STOPPED)
    }

    @Test
    fun `a node stops past a decide still running at the drain timeout, cancelling it`() {
        stopsPastStuckDecide { CompletableDeferred<Unit>().await() }
    }

    @Test
    fun `a node stops past a decide that ignores its cancellation, once the drain timeout passes again`() {
        val release = CompletableDeferred<Unit>()
        try {
            stopsPastStuckDecide { withContext(NonCancellable) { release.await() } }
        } finally {
            release.complete(Unit)
        }
    }

    private companion object {
        val RELEASE_AFTER = 100.milliseconds
    }
}

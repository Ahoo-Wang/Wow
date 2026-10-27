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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Three nodes of one service in one JVM (design 02 §2–§3; decisions D70–D72, D80): HRW assignment through the
 * leases, routing to the owner, rebalancing on join and leave, and degrading to Local when a forward fails.
 */
class ClusterTest {
    private var cluster = TestCluster()

    @AfterEach
    fun close(): Unit = runBlocking {
        cluster.nodes.values.forEach { it.kill() }
        cluster.close()
    }

    private fun ownerOf(id: String): String = Hrw.owner(cluster.partitionOf(id), cluster.running.map { it.name })!!

    @Test
    fun `in the steady state every command runs on the owner of its partition`(): Unit = runBlocking {
        val (one, two, three) = cluster.start("node-1", "node-2", "node-3")
        cluster.awaitStable()
        val ids = (0 until 48).map { "acc-$it" }
        val results = ids.map { id -> listOf(one, two, three)[id.hashCode().mod(3)].deposit(id) }
        results.forEach { it.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED) }
        ids.zip(results).forEach { (id, result) ->
            val owner = cluster.node(ownerOf(id))
            owner.decided.assert().contains(result.commandId)
            cluster.nodes.values.filter { it != owner }.forEach { it.decided.assert().doesNotContain(result.commandId) }
        }
        // Every node received commands it did not own, so forwarding happened.
        listOf(one, two, three).forEach { it.decided.size.assert().isLessThan(ids.size) }
    }

    @Test
    fun `only the owner of its partition runs a command under the partition's lease`(): Unit = runBlocking {
        // Decision D261: the kernel trusts and caches the states of an execution that holds the lease, and no other.
        val (one, two) = cluster.start("node-1", "node-2")
        cluster.awaitStable()
        val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
        val here = two.deposit(id)
        val forwarded = one.deposit(id)
        two.leaseHeld[here.commandId].assert().isEqualTo("true")
        two.leaseHeld[forwarded.commandId].assert().isEqualTo("true")
        val refusing = PartitionedRouter(listOf(one.ownership), { _, _ -> ForwardReply.refused(Refusal.NOT_OWNER) })
        val command = one.command(id)
        refusing.route(one.model, command, null) { one.coordinator.executeHere(one.model, command, null) }
        one.leaseHeld[command.id].assert().isEqualTo("false")
        val unpartitioned = one.command(id)
        PartitionedRouter(emptyList(), cluster.channel).route(one.model, unpartitioned, null) {
            one.coordinator.executeHere(one.model, unpartitioned, null)
        }
        one.leaseHeld[unpartitioned.id].assert().isEqualTo("unknown")
    }

    @Test
    fun `a forwarded command's later hops are signalled to the node it arrived at`(): Unit = runBlocking {
        val (one) = cluster.start("node-1", "node-2", "node-3")
        cluster.awaitStable()
        val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) != "node-1" }
        val result = one.deposit(id, wait = WaitPlan.of(Hop.handled(PROJECTOR)))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.assert().isEqualTo(Hop.handled(PROJECTOR, CONTEXT))
        cluster.node(ownerOf(id)).projected.assert().contains(id to 1)
        one.decided.assert().doesNotContain(result.commandId)
    }

    @Test
    fun `a joining node takes about P over N partitions, and only from their owners`(): Unit = runBlocking {
        cluster.close()
        cluster = TestCluster(partitions = 32)
        cluster.start("node-1", "node-2")
        cluster.awaitStable()
        val before = cluster.owners()
        cluster.start("node-3")
        cluster.awaitStable()
        val after = cluster.owners()
        val moved = (0 until 32).filter { before[it] != after[it] }
        moved.forEach { after[it].assert().isEqualTo("node-3") }
        moved.toSet().assert().isEqualTo(Hrw.partitionsOf("node-3", listOf("node-1", "node-2", "node-3"), 32))
        moved.size.assert().isBetween(32 / 3 / 2, 32 / 3 * 2)
        // Each moved partition was handed off: released by its old owner before the new one acquired it.
        moved.forEach { cluster.events.assert().contains("release $it ${before[it]}") }
    }

    @Test
    fun `a graceful leave hands off in order - stop accepting, drain, save checkpoints, release`(): Unit =
        runBlocking {
            val (_, _, three) = cluster.start("node-1", "node-2", "node-3")
            cluster.awaitStable()
            val partition = three.ownership.owned.first()
            // Something for node-3's projector to have handled, so stopping the partition saves a checkpoint.
            val warm = cluster.idIn(partition)
            three.deposit(warm)
            eventually { three.projected.contains(warm to 1) }

            val gate = CompletableDeferred<Unit>().also { three.hold = it }
            val slowId = cluster.idIn(partition, skip = 1)
            val slow = async(Dispatchers.Default) { three.deposit(slowId) }
            eventually { three.decided.size == 2 }
            val leaving = async(Dispatchers.Default) { three.leave() }

            eventually { !three.ownership.accepts(partition) }
            val next = cluster.node(Hrw.owner(partition, listOf("node-1", "node-2"))!!)
            eventually { partition in next.ownership.target }
            // Stopped accepting: a new command for the partition goes to the next owner, even through node-3.
            three.hold = null
            val late = three.deposit(cluster.idIn(partition, skip = 2))
            late.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            next.decided.assert().contains(late.commandId)
            three.decided.assert().doesNotContain(late.commandId)
            // The command in flight holds the hand-off up.
            cluster.events.assert().doesNotContain("release $partition node-3")

            gate.complete(Unit)
            slow.await().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            leaving.await()

            val events = cluster.events.toList()
            val appended = events.indexOf("append $slowId")
            // The first save after the append: the subscriptions saved their starting checkpoints when they started.
            val saved = (appended + 1 until events.size).firstOrNull { events[it].startsWith("checkpoint $partition ") } ?: -1
            val released = events.indexOf("release $partition node-3")
            (appended >= 0 && saved > appended && released > saved).assert().isTrue()
            three.ownership.owned.assert().isEmpty()
            cluster.awaitStable()
            cluster.owners()[partition].assert().isEqualTo(next.name)
        }

    @Test
    fun `when the owner dies, forwards degrade to Local and its partitions move after the lease TTL`(): Unit =
        runBlocking {
            val (one, _, three) = cluster.start("node-1", "node-2", "node-3")
            cluster.awaitStable()
            val partition = three.ownership.owned.first()
            three.kill()
            cluster.owners()[partition].assert().isEqualTo("node-3")

            val id = cluster.idIn(partition)
            val result = one.deposit(id)
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.version.assert().isEqualTo(1)
            one.decided.assert().contains(result.commandId)

            cluster.awaitStable()
            cluster.owners().values.toSet().assert().isEqualTo(setOf("node-1", "node-2"))
            cluster.events.none { it.endsWith(" node-3") && it.startsWith("release") }.assert().isTrue()
            // Review A5-14: the member that left is forgotten.
            eventually { one.ownership.knownPeers.none { it.startsWith("node-3@") } }
        }

    @Test
    fun `a forward whose reply comes too late is unknown, not decided here, and a retry gets the owner's commit`(): Unit =
        runBlocking {
            cluster.close()
            cluster = TestCluster(options = fastOptions(forwardTimeout = 300.milliseconds))
            val (one, two) = cluster.start("node-1", "node-2")
            cluster.awaitStable()
            val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
            cluster.latencies[two.address] = 2.seconds

            val result = one.deposit(id)

            // Decision D223: the owner received it, so it does not run here too.
            result.status.assert().isEqualTo(CommandResult.Status.FAILED)
            result.error!!.code.assert().isEqualTo(ResultCodes.UNKNOWN)
            result.hop.assert().isEqualTo(Hop.committed(CONTEXT))
            one.decided.assert().isEmpty()
            eventually { cluster.events.contains("append $id") }
            cluster.latencies.clear()
            val retry = one.deposit(id, commandId = result.commandId)
            retry.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            retry.duplicate.assert().isTrue()
            retry.version.assert().isEqualTo(1)
            two.decided.assert().containsExactly(result.commandId)
            one.decided.assert().isEmpty()
            cluster.log.positionOf(TestNode.ref(id), 2).assert().isNull()
            cluster.events.count { it == "append $id" }.assert().isEqualTo(1)
        }

    @Test
    fun `an owner deciding longer than the forward timeout decides the request exactly once`(): Unit = runBlocking {
        cluster.close()
        cluster = TestCluster(options = fastOptions(forwardTimeout = 300.milliseconds))
        val (one, two) = cluster.start("node-1", "node-2")
        cluster.awaitStable()
        val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
        // The owner's decide is slow, as one calling a payment service is (finding A1-6).
        val decide = CompletableDeferred<Unit>().also { two.hold = it }

        val result = one.deposit(id)

        result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        result.error!!.code.assert().isEqualTo(ResultCodes.UNKNOWN)
        two.decided.assert().containsExactly(result.commandId)
        one.decided.assert().isEmpty()
        decide.complete(Unit)
        eventually { cluster.events.contains("append $id") }
        two.hold = null
        val retry = one.deposit(id, commandId = result.commandId)
        retry.duplicate.assert().isTrue()
        retry.version.assert().isEqualTo(1)
        two.decided.assert().containsExactly(result.commandId)
        one.decided.assert().isEmpty()
    }

    @Test
    fun `a forward waits no longer than the command's own deadline`(): Unit = runBlocking {
        val (one, two) = cluster.start("node-1", "node-2")
        cluster.awaitStable()
        val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
        cluster.latencies[two.address] = 3.seconds

        val started = System.nanoTime()
        val result = one.deposit(id, wait = WaitPlan.COMMITTED.within(300.milliseconds))
        val elapsed = (System.nanoTime() - started) / 1_000_000

        // The default forward timeout is one second; the wait had 300 ms, which bound the forward too (D64).
        elapsed.assert().isBetween(250L, 900L)
        // The router gives up the forward at the deadline without running the command here (decision D223): its
        // outcome is unknown, or the wait times out first; either way the answer comes in time.
        result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        listOf(ResultCodes.UNKNOWN, ResultCodes.WAIT_TIMEOUT).assert().contains(result.error!!.code)
        one.decided.assert().isEmpty()
        // The forward went on apart from the wait: the owner committed it, and a retry is answered from that commit.
        eventually { cluster.events.contains("append $id") }
        cluster.latencies.clear()
        val retry = one.deposit(id, commandId = result.commandId)
        retry.duplicate.assert().isTrue()
        retry.version.assert().isEqualTo(1)
        two.decided.assert().containsExactly(result.commandId)
    }

    @Test
    fun `a node connects to each peer once its routes show it, before forwarding to it`(): Unit = runBlocking {
        val (one, two) = cluster.start("node-1", "node-2")
        cluster.awaitStable()
        eventually { one.address in cluster.connects && two.address in cluster.connects }
        val greeted = cluster.connects.size
        // The routes keep showing the same peers: they are not greeted again.
        delay(300)
        cluster.connects.size.assert().isEqualTo(greeted)

        val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
        one.deposit(id).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        two.decided.assert().hasSize(1)
        cluster.connects.size.assert().isEqualTo(greeted + 1)
    }

    @Test
    fun `connecting to a peer is not counted against the forward's timeout`(): Unit = runBlocking {
        val (one, two) = cluster.start("node-1", "node-2")
        cluster.awaitStable()
        val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
        // Longer than the forward timeout, one second, as to a node that has just started (D80).
        cluster.connectLatencies[two.address] = 1_500.milliseconds

        val started = System.nanoTime()
        val result = one.deposit(id)
        val elapsed = (System.nanoTime() - started) / 1_000_000

        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        two.decided.assert().containsExactly(result.commandId)
        one.decided.assert().isEmpty()
        elapsed.assert().isGreaterThanOrEqualTo(1_500L)
    }

    @Test
    fun `a peer that cannot be connected to runs the command here`(): Unit = runBlocking {
        val (one, two) = cluster.start("node-1", "node-2")
        cluster.awaitStable()
        val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
        cluster.unreachable += two.address

        val result = one.deposit(id)

        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        one.decided.assert().containsExactly(result.commandId)
        two.decided.assert().isEmpty()
    }

    @Test
    fun `connecting waits no longer than the command's own deadline, then the command runs here`(): Unit =
        runBlocking {
            val (one, two) = cluster.start("node-1", "node-2")
            cluster.awaitStable()
            val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
            cluster.connectLatencies[two.address] = 3.seconds

            val started = System.nanoTime()
            val result = one.deposit(id, wait = WaitPlan.COMMITTED.within(300.milliseconds))
            val elapsed = (System.nanoTime() - started) / 1_000_000

            elapsed.assert().isBetween(250L, 900L)
            // The wait and the connection end at the same deadline: the command, run here then, is too late for the
            // wait, unless it settles first.
            if (result.status == CommandResult.Status.FAILED) {
                result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
            }
            // Not connected by the deadline, the router gave up the peer and ran the command here (D71).
            eventually { one.decided.contains(result.commandId) }
            two.decided.assert().isEmpty()
        }

    @Test
    fun `a peer with its in-flight forwards at the limit is not forwarded to`(): Unit = runBlocking {
        cluster.close()
        cluster = TestCluster(options = fastOptions(forwardTimeout = 5.seconds, maxInFlightPerPeer = 1))
        val (one, two) = cluster.start("node-1", "node-2")
        cluster.awaitStable()
        val (first, second) = (0 until 64).map { "acc-$it" }.filter { ownerOf(it) == "node-2" }.take(2)
        val gate = CompletableDeferred<Unit>().also { two.hold = it }
        val forwarded = async(Dispatchers.Default) { one.deposit(first) }
        eventually { two.decided.size == 1 }

        val local = one.deposit(second)
        one.decided.assert().containsExactly(local.commandId)
        gate.complete(Unit)
        two.decided.assert().containsExactly(forwarded.await().commandId)
    }

    @Test
    fun `a node refuses a forward it does not own, one past its deadline, and a type it does not know`(): Unit =
        runBlocking {
            val node = cluster.node("node-1")
            val endpoint = ForwardEndpoint(node.coordinator, listOf(node.model), listOf(node.ownership))
            val command = node.command("acc-1")
            val wire = ClusterCodec.wire(node.model, command)
            val later = System.currentTimeMillis() + 10_000
            endpoint.forward(ForwardCommand(command = wire, deadline = later))
                .refused.assert().isEqualTo(Refusal.NOT_OWNER)
            endpoint.forward(ForwardCommand(command = wire, deadline = 1L))
                .refused.assert().isEqualTo(Refusal.EXPIRED)
            endpoint.forward(ForwardCommand(command = wire.copyWith(type = "bank.account.Close"), deadline = later))
                .refused.assert().isEqualTo(Refusal.UNKNOWN_TYPE)
            node.decided.assert().isEmpty()
        }

    @Test
    fun `a refused forward runs locally`(): Unit = runBlocking {
        val (one) = cluster.start("node-1", "node-2")
        cluster.awaitStable()
        val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
        var asked = 0
        val refusing = PartitionedRouter(
            listOf(one.ownership),
            { _, _ ->
                asked++
                ForwardReply.refused(Refusal.NOT_OWNER)
            },
            cluster.options,
        )
        val command = one.command(id)
        val settlement = refusing.route(one.model, command, null) {
            one.coordinator.executeHere(one.model, command, null)
        }
        asked.assert().isEqualTo(1)
        // Review A5-14: the peer's forward counter goes once it has none in flight.
        refusing.peersInFlight.assert().isEmpty()
        settlement.committed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        one.decided.assert().containsExactly(command.id)
    }

    @Test
    fun `only a forward the owner surely did not receive runs locally`(): Unit = runBlocking {
        // Decision D223: a refusal or an unsent request runs here; a failure after the send is unknown.
        val (one) = cluster.start("node-1", "node-2")
        cluster.awaitStable()
        val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
        val answers = listOf<suspend () -> ForwardReply>(
            { ForwardReply.refused(Refusal.EXPIRED) },
            { ForwardReply.refused(Refusal.UNKNOWN_TYPE) },
            { throw ClusterChannelException("Connection refused", unsent = true) },
            { throw ClusterChannelException("Stream reset") },
            { throw IllegalStateException("The reply does not decode.") },
        )
        val failed = mutableListOf<String>()
        val observer = object : Observer {
            override fun forwardFailed(type: AggregateType, outcome: String) {
                failed += outcome
            }
        }
        val outcomes = answers.map { answer ->
            val router = PartitionedRouter(listOf(one.ownership), { _, _ -> answer() }, cluster.options, observer)
            val command = one.command(id)
            router.route(one.model, command, null) { one.coordinator.executeHere(one.model, command, null) }
        }
        outcomes.take(3).forEach { it.committed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED) }
        outcomes.drop(3).forEach {
            it.committed.status.assert().isEqualTo(CommandResult.Status.FAILED)
            it.committed.error!!.code.assert().isEqualTo(ResultCodes.UNKNOWN)
            it.outcome.assert().isNull()
        }
        one.decided.assert().hasSize(3)
        // Review A5-11: each forward without the owner's settlement is observed, by outcome.
        failed.assert().containsExactly("local", "local", "local", "unknown", "unknown")
    }

    @Test
    fun `a forward whose wait ran out before it was sent is withdrawn, not unknown nor run here`(): Unit =
        runBlocking {
            // Review R2.1-9: withTimeoutOrNull(0) never sends, so no reply is not an unknown outcome.
            val (one) = cluster.start("node-1", "node-2")
            cluster.awaitStable()
            val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
            val now = AtomicLong(System.currentTimeMillis())
            val sent = AtomicInteger()
            val channel = object : ClusterChannel {
                override suspend fun forward(address: String, request: ForwardCommand): ForwardReply {
                    sent.incrementAndGet()
                    error("Never sent.")
                }

                // Connected just as the wait runs out.
                override suspend fun connect(address: String) {
                    now.addAndGet(1_000)
                }
            }
            val failed = CopyOnWriteArrayList<String>()
            val observer = object : Observer {
                override fun forwardFailed(type: AggregateType, outcome: String) {
                    failed += outcome
                }
            }
            val router = PartitionedRouter(listOf(one.ownership), channel, cluster.options, observer) { now.get() }
            val command = one.command(id)
            val wait = WaitContext(
                "w-1",
                Origin("node-1", one.address, "bank"),
                listOf(Hop.committed(CONTEXT)),
                now.get() + 500
            )
            val settlement = router.route(
                one.model,
                command,
                wait
            ) { one.coordinator.executeHere(one.model, command, wait) }
            settlement.committed.status.assert().isEqualTo(CommandResult.Status.FAILED)
            settlement.committed.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
            sent.get().assert().isZero()
            one.decided.assert().isEmpty()
            failed.assert().isEmpty()
        }
}

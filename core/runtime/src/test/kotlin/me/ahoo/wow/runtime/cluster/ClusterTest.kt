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
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.runtime.subscription.eventually
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
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
            val saved = events.indexOfFirst { it.startsWith("checkpoint $partition ") }
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
        }

    @Test
    fun `a forward that times out after the owner committed settles as a duplicate, not a second commit`(): Unit =
        runBlocking {
            cluster.close()
            cluster = TestCluster(options = fastOptions(forwardTimeout = 300.milliseconds))
            val (one, two) = cluster.start("node-1", "node-2")
            cluster.awaitStable()
            val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
            cluster.latencies[two.address] = 2.seconds

            val result = one.deposit(id)

            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.duplicate.assert().isTrue()
            result.version.assert().isEqualTo(1)
            two.decided.assert().containsExactly(result.commandId)
            one.decided.assert().isEmpty()
            cluster.log.positionOf(TestNode.ref(id), 2).assert().isNull()
            cluster.events.count { it == "append $id" }.assert().isEqualTo(1)
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

        // The default forward timeout is one second; the command had 300 ms left, and then ran here.
        elapsed.assert().isBetween(250L, 900L)
        two.decided.assert().containsExactly(result.commandId)
        result.duplicate.assert().isTrue()
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
            endpoint.forward(ForwardCommand(command = wire.copy(type = "bank.account.Close"), deadline = later))
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
        settlement.committed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        one.decided.assert().containsExactly(command.id)
    }
}

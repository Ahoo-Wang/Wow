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

package me.ahoo.wow.runtime.admission

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.runtime.cluster.CONTEXT
import me.ahoo.wow.runtime.cluster.FROZEN_LEASES
import me.ahoo.wow.runtime.cluster.Hrw
import me.ahoo.wow.runtime.cluster.TestCluster
import me.ahoo.wow.runtime.cluster.TestNode
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.subscription.eventually
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Admission runs on the node a request arrives at; the admitted command is forwarded over the cluster channel to its
 * partition's owner, which executes it without admitting it again (decisions D71, D92).
 */
class ClusterAdmissionTest {
    /**
     * The test asserts where the command ran, so nothing may make it degrade to running locally (D71): a forward slow
     * under load, nor a stall of the JVM that expires the leases, which [FROZEN_LEASES] rules out.
     */
    private val cluster = TestCluster(options = fastOptions(forwardTimeout = 10.seconds), leaseClock = FROZEN_LEASES)

    @AfterEach
    fun close(): Unit = runBlocking {
        cluster.nodes.values.forEach { it.kill() }
        cluster.close()
    }

    /** The actions each node's authorizer saw. */
    private val authorized = CopyOnWriteArrayList<Pair<String, CommandAction>>()

    private fun gateway(node: TestNode) = CommandGateway(
        ModelRegistry(listOf(node.model)),
        mapOf(CONTEXT to node.coordinator),
        authorizer = { _, action ->
            authorized += node.name to action
            Authorization.Allowed
        },
    )

    private fun request(id: String, caller: Caller) = ServerCommandRequest(
        context = CONTEXT,
        aggregate = "account",
        command = "Deposit",
        body = CommandBody.of("""{"amount":5}"""),
        caller = caller,
        aggregateId = id,
        wait = WaitPlan.COMMITTED,
    )

    @Test
    fun `a command admitted on one node is forwarded to its owner and keeps its admitted identity`(): Unit =
        runBlocking {
            val (one, two) = cluster.start("node-1", "node-2")
            cluster.awaitStable()
            val id = (0 until 64).map { "acc-$it" }
                .first { Hrw.owner(cluster.partitionOf(it), listOf("node-1", "node-2")) == "node-2" }
            val alice = Caller("t", Scope("alice"), subject = "alice")

            val result = gateway(one).execute(request(id, alice))
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.hop.assert().isEqualTo(Hop.committed(CONTEXT))
            two.decided.assert().containsExactly(result.commandId)
            one.decided.assert().isEmpty()
            authorized.map { it.first }.assert().containsExactly("node-1")

            // The owner's kernel guards the scope the arriving node admitted: another owner is refused there.
            val bob = gateway(one).execute(request(id, Caller("t", Scope("bob"), subject = "bob")))
            bob.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            bob.error!!.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
            two.decided.size.assert().isEqualTo(1)
            one.decided.assert().isEmpty()
        }

    /**
     * Why the test above uses [FROZEN_LEASES]. A stall longer than the lease TTL (CPU starvation, a GC or class-loading
     * pause) expires every lease and membership on the wall clock; until the nodes renew and the routes are read
     * again, the partition has no known owner, so the command runs on the node it arrived at (D71) instead of on its
     * owner. Its admitted identity is kept all the same: it was admitted once, and the kernel there guards its scope.
     */
    @Test
    fun `while a stall has expired the leases a command runs where it arrived and keeps its admitted identity`(): Unit =
        runBlocking {
            val clock = StallingClock()
            val stalling = TestCluster(options = fastOptions(forwardTimeout = 10.seconds), leaseClock = clock)
            try {
                val (one, two) = stalling.start("node-1", "node-2")
                stalling.awaitStable()
                val id = (0 until 64).map { "acc-$it" }
                    .first { Hrw.owner(stalling.partitionOf(it), listOf("node-1", "node-2")) == "node-2" }
                val alice = Caller("t", Scope("alice"), subject = "alice")

                clock.stall()
                eventually { one.ownership.routes.members.isEmpty() }
                val result = gateway(one).execute(request(id, alice))
                result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
                one.decided.assert().containsExactly(result.commandId)
                two.decided.assert().isEmpty()
                val bob = gateway(one).execute(request(id, Caller("t", Scope("bob"), subject = "bob")))
                bob.error!!.code.assert().isEqualTo(FrameworkCodes.AGGREGATE_FORBIDDEN)
                authorized.map { it.first }.assert().containsExactly("node-1", "node-1")

                clock.resume()
                stalling.awaitStable()
                val after = gateway(one).execute(request(id, alice))
                after.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
                two.decided.assert().containsExactly(after.commandId)
                one.decided.size.assert().isEqualTo(1)
            } finally {
                stalling.nodes.values.forEach { it.kill() }
                stalling.close()
            }
        }

    /**
     * A lease clock standing still, but while [stall]ed every reading leaps an hour on: whatever a node writes is
     * expired at the next reading, as when a stall outlasts every TTL, for as long as the test needs.
     */
    private class StallingClock : Clock() {
        private val start = Instant.parse("2026-01-01T00:00:00Z")
        private val leaps = AtomicLong()

        @Volatile
        private var stalled = false

        fun stall() {
            stalled = true
        }

        fun resume() {
            stalled = false
        }

        override fun instant(): Instant {
            val leap = if (stalled) leaps.incrementAndGet() else leaps.get()
            return start.plusMillis(leap * 1.hours.inWholeMilliseconds)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = throw UnsupportedOperationException()
    }
}

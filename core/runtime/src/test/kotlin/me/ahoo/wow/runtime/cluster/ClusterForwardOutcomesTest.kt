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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.runtime.clockOf
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.wait.ResultCodes
import me.ahoo.wow.runtime.wait.Settlement
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a forward settles as when the owner refuses it, it is withdrawn, its answer comes late or never: run here only
 * when the owner surely did not receive it, otherwise withdrawn, busy, the owner's outcome or unknown.
 */
class ClusterForwardOutcomesTest : ClusterTestCase() {
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
    fun `a forward past the peer's bound is refused busy and runs nowhere, one at the bound minus one goes`(): Unit =
        runBlocking {
            val (one) = cluster.start("node-1", "node-2")
            cluster.awaitStable()
            val (first, second, third) = (0 until 64).map { "acc-$it" }.filter { ownerOf(it) == "node-2" }.take(3)
            val gate = CompletableDeferred<Unit>()
            val sent = AtomicInteger()
            val (events, observer) = observing()
            // The peer takes at most two forwards from this node; each forward it is sent waits at the gate.
            val router = PartitionedRouter(
                listOf(one.ownership),
                { _, _ ->
                    sent.incrementAndGet()
                    gate.await()
                    ForwardReply.refused(Refusal.NOT_OWNER)
                },
                fastOptions(forwardTimeout = 5.seconds, maxInFlightPerPeer = 2),
                observer,
            )
            val held = async(Dispatchers.Default) { router.routeOn(one, one.command(first), null) }
            eventually { sent.get() == 1 }
            // One in flight, the bound minus one: the next forward is sent.
            val atBound = async(Dispatchers.Default) { router.routeOn(one, one.command(second), null) }
            eventually { sent.get() == 2 }

            // Two in flight, the bound: the next is refused busy before it is sent, and nothing runs it here.
            val past = one.command(third)
            val refused = router.routeOn(one, past, null)
            refused.committed.status.assert().isEqualTo(CommandResult.Status.FAILED)
            refused.committed.error!!.code.assert().isEqualTo(ResultCodes.BUSY)
            refused.outcome.assert().isNull()
            sent.get().assert().isEqualTo(2)
            one.decided.assert().isEmpty()
            cluster.events.assert().doesNotContain("append $third")
            events.filterIsInstance<ObservedEvent.Refused>().map { it.code }.assert().containsExactly(ResultCodes.BUSY)
            events.filterIsInstance<ObservedEvent.ForwardFailed>().map { it.outcome }.assert()
                .containsExactly(ObservedEvent.ForwardFailed.Outcome.BUSY)

            // The forwards in flight were sent; their refusal before the deadline still runs them here.
            gate.complete(Unit)
            listOf(held.await(), atBound.await()).forEach {
                it.committed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            }
            one.decided.assert().hasSize(2).doesNotContain(past.id)
            router.peersInFlight.assert().isEmpty()
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
        val failed = mutableListOf<ObservedEvent.ForwardFailed.Outcome>()
        val observer = object : Observer {
            override fun observed(event: ObservedEvent) {
                when (event) {
                    is ObservedEvent.ForwardFailed -> failed += event.outcome
                    else -> Unit
                }
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
        val (local, unknown) = ObservedEvent.ForwardFailed.Outcome.LOCAL to ObservedEvent.ForwardFailed.Outcome.UNKNOWN
        failed.assert().containsExactly(local, local, local, unknown, unknown)
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
            val failed = CopyOnWriteArrayList<ObservedEvent.ForwardFailed.Outcome>()
            val observer = object : Observer {
                override fun observed(event: ObservedEvent) {
                    when (event) {
                        is ObservedEvent.ForwardFailed -> failed += event.outcome
                        else -> Unit
                    }
                }
            }
            val router = PartitionedRouter(
                listOf(one.ownership),
                channel,
                cluster.options,
                observer,
                clockOf { now.get() }
            )
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

    @Test
    fun `a forward that gets no stream to its owner by its deadline is withdrawn or busy, never unknown nor run here`(): Unit =
        runBlocking {
            // Review X-61: a forward still waiting for one of the owner's streams was not sent, so its outcome is known.
            val (one, two) = cluster.start("node-1", "node-2")
            cluster.awaitStable()
            val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
            val sent = AtomicInteger()
            // Every stream to the owner stays taken.
            val full = object : ClusterChannel {
                override suspend fun forward(address: String, request: ForwardCommand): ForwardReply {
                    sent.incrementAndGet()
                    return cluster.loopback.forward(address, request)
                }

                override suspend fun reserve(address: String): ClusterStream = awaitCancellation()
            }
            val (events, observer) = observing()
            val options = fastOptions(forwardTimeout = 300.milliseconds)
            val router = PartitionedRouter(listOf(one.ownership), full, options, observer)

            // Its wait ends with the forward's deadline: withdrawn, as a command still queued in its lane then is.
            val withdrawn = router.routeOn(one, one.command(id), one.waitOf(300.milliseconds))
            // The forward's deadline comes before its wait's: busy, which a retry later may pass.
            val busy = router.routeOn(one, one.command(id), one.waitOf(5.seconds))
            val detached = router.routeOn(one, one.command(id), null)

            withdrawn.committed.status.assert().isEqualTo(CommandResult.Status.FAILED)
            withdrawn.committed.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
            listOf(busy, detached).forEach {
                it.committed.status.assert().isEqualTo(CommandResult.Status.FAILED)
                it.committed.error!!.code.assert().isEqualTo(ResultCodes.BUSY)
                it.committed.hop.assert().isEqualTo(Hop.committed(CONTEXT))
            }
            sent.get().assert().isZero()
            // Not run here instead, beside the owner (X-34).
            one.decided.assert().isEmpty()
            two.decided.assert().isEmpty()
            events.filterIsInstance<ObservedEvent.ForwardFailed>().assert().isEmpty()
            events.filterIsInstance<ObservedEvent.Refused>().map { it.code }.assert()
                .containsExactly(ResultCodes.WAIT_TIMEOUT, ResultCodes.BUSY, ResultCodes.BUSY)
            router.peersInFlight.assert().isEmpty()
        }

    @Test
    fun `a forward sent on a reserved stream is answered by the owner, and a stream it took too late is freed`(): Unit =
        runBlocking {
            val (one, two) = cluster.start("node-1", "node-2")
            cluster.awaitStable()
            val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
            val reserved = AtomicInteger()
            val released = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val channel = object : ClusterChannel {
                override suspend fun forward(address: String, request: ForwardCommand): ForwardReply =
                    error("Forwarded on its stream.")

                override suspend fun reserve(address: String): ClusterStream {
                    gate.await()
                    reserved.incrementAndGet()
                    return object : ClusterStream {
                        private var sent = false

                        override suspend fun forward(request: ForwardCommand): ForwardReply {
                            sent = true
                            return cluster.loopback.forward(address, request)
                        }

                        override fun release() {
                            if (!sent) {
                                released.incrementAndGet()
                            }
                        }
                    }
                }
            }
            val router = PartitionedRouter(listOf(one.ownership), channel, cluster.options)
            gate.complete(Unit)
            val answered = router.routeOn(one, one.command(id), null)
            answered.committed.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            two.decided.assert().containsExactly(answered.committed.commandId)

            // A stream that comes just as the wait runs out: not sent on, and given back.
            val now = AtomicLong(System.currentTimeMillis())
            val late = object : ClusterChannel by channel {
                override suspend fun reserve(address: String): ClusterStream =
                    channel.reserve(address).also { now.addAndGet(1_000) }
            }
            val clock = clockOf { now.get() }
            val lateRouter = PartitionedRouter(listOf(one.ownership), late, cluster.options, clock = clock)
            val withdrawn = lateRouter.routeOn(one, one.command(id), one.waitOf(500.milliseconds, now.get()))
            withdrawn.committed.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
            reserved.get().assert().isEqualTo(2)
            released.get().assert().isEqualTo(1)
            two.decided.assert().hasSize(1)
            one.decided.assert().isEmpty()
        }

    @Test
    fun `an owner's answer just after the forward's deadline is its outcome, a command it withdrew a wait timeout`(): Unit =
        runBlocking {
            // Review X-61: the owner withdraws a command still queued at its wait's deadline and says so; the router
            // waits the channel's grace for that answer instead of reporting the outcome unknown at the same instant.
            cluster.close()
            cluster = TestCluster(options = fastOptions(forwardTimeout = 5.seconds))
            val (one, two) = cluster.start("node-1", "node-2")
            cluster.awaitStable()
            val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
            // The aggregate's lane on the owner is taken by a decide that waits for the gate.
            val gate = CompletableDeferred<Unit>().also { two.hold = it }
            val first = async(Dispatchers.Default) { one.deposit(id) }
            eventually { two.decided.size == 1 }
            val (events, observer) = observing()
            val router = PartitionedRouter(listOf(one.ownership), graced(1.seconds), cluster.options, observer)
            val command = one.command(id)

            val started = System.nanoTime()
            val settlement = router.routeOn(one, command, one.waitOf(300.milliseconds))
            val elapsed = (System.nanoTime() - started) / 1_000_000

            settlement.committed.status.assert().isEqualTo(CommandResult.Status.FAILED)
            settlement.committed.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
            // The owner answered at the deadline, well within the grace.
            elapsed.assert().isBetween(250L, 1_000L)
            events.filterIsInstance<ObservedEvent.ForwardFailed>().assert().isEmpty()
            gate.complete(Unit)
            first.await().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            two.decided.assert().containsExactly(first.await().commandId)
            one.decided.assert().isEmpty()
        }

    @Test
    fun `an owner that does not answer by the grace after the deadline leaves the outcome unknown`(): Unit =
        runBlocking {
            cluster.close()
            cluster = TestCluster(options = fastOptions(forwardTimeout = 300.milliseconds))
            val (one, two) = cluster.start("node-1", "node-2")
            cluster.awaitStable()
            val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
            val gate = CompletableDeferred<Unit>().also { two.hold = it }
            val (events, observer) = observing()
            val router = PartitionedRouter(listOf(one.ownership), graced(300.milliseconds), cluster.options, observer)
            val command = one.command(id)

            val started = System.nanoTime()
            val settlement = router.routeOn(one, command, null)
            val elapsed = (System.nanoTime() - started) / 1_000_000

            settlement.committed.status.assert().isEqualTo(CommandResult.Status.FAILED)
            settlement.committed.error!!.code.assert().isEqualTo(ResultCodes.UNKNOWN)
            // The caller waited the grace past the forward's deadline, and no longer.
            elapsed.assert().isBetween(550L, 1_500L)
            events.filterIsInstance<ObservedEvent.ForwardFailed>().map { it.outcome }.assert()
                .containsExactly(ObservedEvent.ForwardFailed.Outcome.UNKNOWN)
            two.decided.assert().containsExactly(command.id)
            one.decided.assert().isEmpty()
            gate.complete(Unit)
            eventually { cluster.events.contains("append $id") }
        }

    @Test
    fun `past its deadline a forward the owner did not take runs nowhere`(): Unit = runBlocking {
        // Review X-61: a refusal or an unsent request runs here only while the forward's deadline is ahead.
        val (one, two) = cluster.start("node-1", "node-2")
        cluster.awaitStable()
        val id = (0 until 64).map { "acc-$it" }.first { ownerOf(it) == "node-2" }
        val answers = listOf<suspend () -> ForwardReply>(
            { ForwardReply.refused(Refusal.EXPIRED) },
            { ForwardReply.refused(Refusal.NOT_OWNER) },
            { throw ClusterChannelException("Not sent by its deadline", unsent = true) },
        )
        val (events, observer) = observing()
        val options = fastOptions(forwardTimeout = 200.milliseconds)
        val outcomes = answers.flatMap { answer ->
            val channel = object : ClusterChannel {
                override suspend fun forward(address: String, request: ForwardCommand): ForwardReply {
                    delay(request.deadline - System.currentTimeMillis() + 100)
                    return answer()
                }

                override val answerGrace = 1.seconds
            }
            val router = PartitionedRouter(listOf(one.ownership), channel, options, observer)
            listOf(
                router.routeOn(one, one.command(id), null),
                // The wait's deadline is the forward's.
                router.routeOn(one, one.command(id), one.waitOf(200.milliseconds)),
                // The forward's deadline comes first, and the wait's passes before the answer.
                router.routeOn(one, one.command(id), one.waitOf(250.milliseconds)),
                router.routeOn(one, one.command(id), one.waitOf(5.seconds)),
            )
        }
        val each = listOf(ResultCodes.BUSY, ResultCodes.WAIT_TIMEOUT, ResultCodes.WAIT_TIMEOUT, ResultCodes.BUSY)
        outcomes.map { it.committed.error!!.code }.assert().containsExactlyElementsOf(each + each + each)
        one.decided.assert().isEmpty()
        two.decided.assert().isEmpty()
        events.filterIsInstance<ObservedEvent.ForwardFailed>().assert().isEmpty()
    }

    /** The cluster's channel, its streams carrying the owner's reply [grace] past a forward's deadline. */
    private fun graced(grace: Duration): ClusterChannel = object : ClusterChannel by cluster.channel {
        override val answerGrace = grace
    }

    /** The events an observer saw, and the observer. */
    private fun observing(): Pair<List<ObservedEvent>, Observer> {
        val events = CopyOnWriteArrayList<ObservedEvent>()
        return events to object : Observer {
            override fun observed(event: ObservedEvent) {
                events += event
            }
        }
    }

    /** [command] routed by this router from [node], run there as the node's coordinator runs it. */
    private suspend fun PartitionedRouter.routeOn(node: TestNode, command: Command<*>, wait: WaitContext?): Settlement =
        route(node.model, command, wait) { node.coordinator.executeHere(node.model, command, wait) }

    /** A wait of this node's that ends [within] from [now]. */
    private fun TestNode.waitOf(within: Duration, now: Long = System.currentTimeMillis()): WaitContext =
        WaitContext(
            "w-${cluster.nextId()}",
            Origin(name, address, "bank"),
            listOf(Hop.committed(CONTEXT)),
            now + within.inWholeMilliseconds
        )
}

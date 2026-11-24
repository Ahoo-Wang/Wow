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

package me.ahoo.wow.runtime.wait

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.admissionOf
import me.ahoo.wow.runtime.cluster.TopicRouter
import me.ahoo.wow.runtime.command.CommandIntake
import me.ahoo.wow.runtime.command.MessageEntries
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.subscription.EntrySource
import me.ahoo.wow.runtime.subscription.LogSource
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A wait through a command topic whose commit is signalled before or after the broker acknowledged the command: the
 * wait keeps the furthest stage it reached, and a timeout reports it, with its version (issue #5509).
 */
class TopicWaitProgressTest {
    data class Place(val items: Int)
    data class Placed(val items: Int)
    class Order {
        var items = 0
    }

    @Test
    fun `a commit signalled before the broker's acknowledgement is the progress a timeout reports`(): Unit =
        runBlocking {
            scenario(topicRouted = true, acknowledgedAfterCommit = true).timedOutAtCommitted()
        }

    @Test
    fun `a commit signalled after the broker's acknowledgement is the progress a timeout reports`(): Unit =
        runBlocking {
            scenario(topicRouted = true, acknowledgedAfterCommit = false).timedOutAtCommitted()
        }

    @Test
    fun `a wait through the topic ends at its snapshot, whichever of commit and acknowledgement came first`(): Unit =
        runBlocking {
            val result = scenario(topicRouted = true, acknowledgedAfterCommit = true, snapshotTaken = true)
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.hop.assert().isEqualTo(Hop.snapshot("shop"))
        }

    @Test
    fun `a command run directly times out at committed while its snapshot is not taken`(): Unit = runBlocking {
        scenario(topicRouted = false, acknowledgedAfterCommit = false).timedOutAtCommitted()
    }

    @Test
    fun `a request retried through the topic answers as a duplicate at COMMITTED, whatever hop it waits for next`():
        Unit = runBlocking {
        // #6758: the owner signals the duplicate's commit, and a snapshot that has not handled the first commit proves
        // nothing more; nothing signals the duplicate again, so its wait ends there rather than at its deadline.
        val snapshots = runtime(
            "snapshot",
            LogSource(log),
            Subscription(
                "wow.snapshot",
                SubscriptionFilter("shop"),
                kind = FunctionKind.SYSTEM
            ) { snapshotGate.await() },
        )
        val coordinator = WaitCoordinator(
            Executor(Kernel(log)),
            waits,
            snapshots,
            signals,
            origin,
            topicRouter = TopicRouter(setOf(order.type), TOPIC, 1, producer(afterCommit = false)),
            deadlines = held,
        )
        val intake = CommandIntake(
            "shop",
            listOf(order),
            coordinator,
            SourceTopics(topics, producer = topics),
            admissionOf(),
        )
        val intakeRuntime = runtime("intake", MessageEntries.commands(TOPIC, topics, 1), intake.subscription)
        try {
            snapshots.start(setOf(0))
            intakeRuntime.start(setOf(0))
            val ref = order.type.ref("t", "o-1")
            val first = Command("c-1", "r-1", ref, Place(3), wait = WaitPlan.COMMITTED)
            withTimeout(10.seconds) { coordinator.stream(order, first).toList() }.last().status.assert()
                .isEqualTo(CommandResult.Status.SUCCEEDED)
            listOf(WaitPlan.of(Hop.committed(), Hop.snapshot()), WaitPlan.SNAPSHOT).forEachIndexed { at, plan ->
                val retry = first.copyWith(id = "c-retry-$at", wait = plan)
                // Not passed by the deadline: a duplicate that waited for it would hang until the ten seconds end.
                val last = withTimeout(10.seconds) { coordinator.stream(order, retry).toList() }.last()
                last.status.assert().describedAs("$plan: ${last.error?.code}").isEqualTo(CommandResult.Status.SUCCEEDED)
                last.duplicate.assert().isTrue()
                last.hop.assert().isEqualTo(Hop.committed("shop"))
                last.version.assert().isEqualTo(1)
            }
            log.read(ref).toList().single().version.assert().isEqualTo(1)
        } finally {
            snapshotGate.complete(Unit)
            intakeRuntime.stop()
            snapshots.stop()
            coordinator.join()
            coordinator.close()
        }
    }

    @Test
    fun `a command the owner committed answers its commit, though the broker's acknowledgement was lost`(): Unit =
        runBlocking {
            // #6756: the owner's signal tells that the command was written, whatever the producer said.
            answersCommitAfterLostAcknowledgement(WaitPlan.of(Hop.committed()))
        }

    @Test
    fun `a commit signalled before ACCEPTED, which the plan names first, also tells the command was written`(): Unit =
        runBlocking {
            // The owner's commit waits in the wait's buffer until ACCEPTED applies: it is heard all the same.
            answersCommitAfterLostAcknowledgement(WaitPlan.of(Hop.accepted(), Hop.committed()))
        }

    @Test
    fun `a command the owner rejected answers its rejection, though the broker's acknowledgement was lost`(): Unit =
        runBlocking {
            // Its rejection ended the wait before any hop was reached: it is heard all the same.
            answersCommitAfterLostAcknowledgement(WaitPlan.of(Hop.committed()), items = -1)
        }

    private suspend fun answersCommitAfterLostAcknowledgement(plan: WaitPlan, items: Int = 3) {
        val lost = object : TopicProducer {
            override suspend fun send(topic: String, messages: List<TopicMessage>) {
                topics.send(topic, messages)
                committed.await()
                throw IllegalStateException("The broker's acknowledgement was lost.")
            }
        }
        relayed(lost) { coordinator, ref ->
            val last = withTimeout(10.seconds) {
                coordinator.stream(order, Command("c-1", "r-1", ref, Place(items), wait = plan)).toList()
            }.last()
            val rejected = items < 0
            last.status.assert()
                .describedAs("${last.hop}: ${last.error?.code}")
                .isEqualTo(if (rejected) CommandResult.Status.REJECTED else CommandResult.Status.SUCCEEDED)
            last.hop.assert().isEqualTo(Hop.committed("shop"))
            if (rejected) {
                last.error!!.code.assert().isEqualTo("shop.order.refused")
                log.read(ref).toList().assert().isEmpty()
            } else {
                log.read(ref).toList().single().version.assert().isEqualTo(1)
            }
        }
    }

    @Test
    fun `an acknowledgement later than the deadline times out at the hop the owner signalled, not at ACCEPTED`(): Unit =
        runBlocking {
            val late = object : TopicProducer {
                override suspend fun send(topic: String, messages: List<TopicMessage>) {
                    topics.send(topic, messages)
                    committed.await()
                    delay(10.seconds)
                }
            }
            relayed(late) { coordinator, ref ->
                // The snapshot is held: the wait reaches its commit and no further, and the send outlasts its deadline.
                val plan = WaitPlan.of(Hop.committed(), Hop.snapshot()).within(1.seconds)
                val command = Command("c-1", "r-1", ref, Place(3), wait = plan)
                val results = held.timedOut(coordinator.stream(order, command)) { it.hop.stage == Stage.COMMITTED }
                results.last().timedOutAtCommitted()
            }
        }

    @Test
    fun `a send that fails before the owner heard of the command answers UNKNOWN at ACCEPTED`(): Unit = runBlocking {
        val refused = object : TopicProducer {
            override suspend fun send(topic: String, messages: List<TopicMessage>) {
                throw IllegalStateException("The broker refused the connection.")
            }
        }
        relayed(refused) { coordinator, ref ->
            val plan = WaitPlan.of(Hop.committed())
            val last = withTimeout(10.seconds) {
                coordinator.stream(order, Command("c-1", "r-1", ref, Place(3), wait = plan)).toList()
            }.single()
            last.status.assert().isEqualTo(CommandResult.Status.FAILED)
            last.error!!.code.assert().isEqualTo(ResultCodes.UNKNOWN)
            last.hop.assert().isEqualTo(Hop.accepted("shop"))
            log.read(ref).toList().assert().isEmpty()
        }
    }

    @Test
    fun `a command the intake parks after its retries ends its wait failed parked, never timing out`(): Unit =
        runBlocking {
            // #6757: a technical failure the intake retries is not signalled; once it gives up and parks the command,
            // the wait ends with that, at the command's COMMITTED hop, rather than at its deadline (held here).
            val failing = mockk<Executor>(relaxed = true) {
                coEvery { execute(any(), any(), any()) } returns
                    CommandOutcome.Unknown(IllegalStateException("The append timed out."))
            }
            relayed(producer(afterCommit = false), failing) { coordinator, ref ->
                val plan = WaitPlan.of(Hop.committed())
                val last = withTimeout(10.seconds) {
                    coordinator.stream(order, Command("c-1", "r-1", ref, Place(3), wait = plan)).toList()
                }.last()
                last.status.assert().describedAs("${last.error?.code}").isEqualTo(CommandResult.Status.FAILED)
                last.error!!.code.assert().isEqualTo(ResultCodes.SUBSCRIPTION_PARKED)
                last.error!!.message.assert().doesNotContain("timed out")
                last.hop.assert().isEqualTo(Hop.committed("shop"))
            }
        }

    /**
     * Runs [block] with a coordinator that sends `shop.order` through the command topic with [producer], its intake
     * reading the topic, the snapshot held unless the scenario opened it, and the deadlines [held].
     */
    private suspend fun relayed(
        producer: TopicProducer,
        executor: Executor = Executor(Kernel(log)),
        block: suspend (WaitCoordinator, AggregateRef) -> Unit,
    ) {
        val snapshots = runtime(
            "snapshot",
            LogSource(log),
            Subscription("wow.snapshot", SubscriptionFilter("shop"), kind = FunctionKind.SYSTEM) {
                snapshotGate.await()
            },
        )
        val coordinator = WaitCoordinator(
            executor,
            waits,
            snapshots,
            signals,
            origin,
            topicRouter = TopicRouter(setOf(order.type), TOPIC, 1, producer),
            deadlines = held,
        )
        val intake = CommandIntake(
            "shop",
            listOf(order),
            coordinator,
            SourceTopics(topics, producer = topics),
            admissionOf(),
        )
        val intakeRuntime = runtime("intake", MessageEntries.commands(TOPIC, topics, 1), intake.subscription)
        try {
            snapshots.start(setOf(0))
            intakeRuntime.start(setOf(0))
            block(coordinator, order.type.ref("t", "o-1"))
        } finally {
            snapshotGate.complete(Unit)
            intakeRuntime.stop()
            snapshots.stop()
            coordinator.join()
            coordinator.close()
        }
    }

    private fun CommandResult.timedOutAtCommitted() {
        status.assert().isEqualTo(CommandResult.Status.FAILED)
        error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        hop.assert().isEqualTo(Hop.committed("shop"))
        version.assert().isEqualTo(1)
        error!!.message.assert().isEqualTo("The wait timed out after reaching committed(shop).")
    }

    private val order = aggregate<Order>("shop", "order") {
        initial { Order() }
        decide<Place>(Creation.ALLOWED) { _, body ->
            if (body.items < 0) {
                Decision.reject("shop.order.refused", "Refused.")
            } else {
                Decision.events(Placed(body.items))
            }
        }
        evolve<Placed> { items = it.items }
    }
    private val log = InMemoryEventLog(partitions = 1)
    private val waits = WaitRegistry()
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop-service")
    private val topics = InMemoryTopics(1).also { it.create(TOPIC, 1) }

    /** The commit's signal, once the intake sent it. */
    private val committed = CompletableDeferred<CommandResult>()
    private val signals = SignalSender { target, signal, deadline ->
        LocalSignalSender(origin.node, waits).send(target, signal, deadline)
        if (signal.result.hop.stage == Stage.COMMITTED) committed.complete(signal.result)
    }

    /** Open once the snapshot subscription may handle the commit. */
    private val snapshotGate = CompletableDeferred<Unit>()

    /** The deadline of the scenario's wait, passed once its commit is reached. */
    private val held = HeldDeadlines()

    private fun runtime(name: String, entries: EntrySource, subscription: Subscription) = SubscriptionRuntime(
        name,
        entries,
        InMemoryCheckpointStore(),
        InMemoryFailureLedger(),
        listOf(subscription),
        testOptions(),
        signals = signals,
    )

    /** A producer whose acknowledgement comes back once the commit was signalled, when [afterCommit]. */
    private fun producer(afterCommit: Boolean) = object : TopicProducer {
        override suspend fun send(topic: String, messages: List<TopicMessage>) {
            topics.send(topic, messages)
            if (afterCommit) committed.await()
        }
    }

    /**
     * Runs a command that waits for its commit, then its snapshot: its result. Unless [snapshotTaken], its deadline
     * passes once the commit is reached, whatever the time it took.
     */
    private suspend fun scenario(
        topicRouted: Boolean,
        acknowledgedAfterCommit: Boolean,
        snapshotTaken: Boolean = false,
    ): CommandResult {
        if (snapshotTaken) snapshotGate.complete(Unit)
        val snapshots = runtime(
            "snapshot",
            LogSource(log),
            Subscription(
                "wow.snapshot",
                SubscriptionFilter("shop"),
                kind = FunctionKind.SYSTEM
            ) { snapshotGate.await() },
        )
        val router = TopicRouter(setOf(order.type), TOPIC, 1, producer(acknowledgedAfterCommit))
        val coordinator = WaitCoordinator(
            Executor(Kernel(log)),
            waits,
            snapshots,
            signals,
            origin,
            topicRouter = router.takeIf { topicRouted },
            deadlines = held,
        )
        val intake = CommandIntake(
            "shop",
            listOf(order),
            coordinator,
            SourceTopics(topics, producer = topics),
            admissionOf(),
        )
        val intakeRuntime = runtime("intake", MessageEntries.commands(TOPIC, topics, 1), intake.subscription)
        try {
            snapshots.start(setOf(0))
            intakeRuntime.start(setOf(0))
            val ref = order.type.ref("t", "o-1")
            val plan = WaitPlan.of(Hop.committed(), Hop.snapshot())
            val results = coordinator.stream(order, Command("c-1", "r-1", ref, Place(3), wait = plan))
            val result = held.timedOut(results) { !snapshotTaken && it.hop.stage == Stage.COMMITTED }.last()
            log.read(ref).toList().single().version.assert().isEqualTo(1)
            return result
        } finally {
            snapshotGate.complete(Unit)
            intakeRuntime.stop()
            snapshots.stop()
            coordinator.join()
            coordinator.close()
        }
    }

    private companion object {
        const val TOPIC = "wow.shop.commands"
    }
}

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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.Executor
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

    private fun CommandResult.timedOutAtCommitted() {
        status.assert().isEqualTo(CommandResult.Status.FAILED)
        error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        hop.assert().isEqualTo(Hop.committed("shop"))
        version.assert().isEqualTo(1)
        error!!.message.assert().isEqualTo("The wait timed out after reaching committed(shop).")
    }

    private val order = aggregate<Order>("shop", "order") {
        initial { Order() }
        decide<Place>(Creation.ALLOWED) { _, body -> Decision.events(Placed(body.items)) }
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

    /** Runs a command with the default wait (its snapshot) within a second: its result. */
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
                kind = FunctionKind.SNAPSHOT
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
        )
        val intake = CommandIntake("shop", listOf(order), coordinator, SourceTopics(topics, producer = topics))
        val intakeRuntime = runtime("intake", MessageEntries.commands(TOPIC, topics, 1), intake.subscription)
        try {
            snapshots.start(setOf(0))
            intakeRuntime.start(setOf(0))
            val ref = order.type.ref("t", "o-1")
            val result = coordinator.execute(
                order,
                Command("c-1", "r-1", ref, Place(3), wait = WaitPlan.DEFAULT.within(1.seconds))
            )
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

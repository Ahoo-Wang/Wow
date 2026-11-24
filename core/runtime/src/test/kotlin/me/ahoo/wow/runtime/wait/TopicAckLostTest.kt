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
import kotlinx.coroutines.withTimeout
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
 * Deep review r1 (runtime wait): a command sent through a command topic whose broker wrote it but whose
 * acknowledgement was lost. The owner's intake runs it and signals its commit to the wait, registered before the send
 * (WaitCoordinator.stream), so the wait has its answer, SUCCEEDED at COMMITTED. The failed send then answers FAILED
 * UNKNOWN at ACCEPTED (`sendThrough`), and `stream` emits that and returns: what the wait received is thrown away. The
 * caller is told to retry a command that committed, and its retry, a duplicate through the topic, has no step that
 * stops it at COMMITTED.
 */
class TopicAckLostTest {
    data class Place(val items: Int)
    data class Placed(val items: Int)
    class Order {
        var items = 0
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

    private fun runtime(name: String, entries: EntrySource, subscription: Subscription) = SubscriptionRuntime(
        name,
        entries,
        InMemoryCheckpointStore(),
        InMemoryFailureLedger(),
        listOf(subscription),
        testOptions(),
        signals = signals,
    )

    /** A broker that writes the command, and whose acknowledgement is lost once the commit was signalled. */
    private val lostAck = object : TopicProducer {
        override suspend fun send(topic: String, messages: List<TopicMessage>) {
            topics.send(topic, messages)
            committed.await()
            throw IllegalStateException("The broker's acknowledgement was lost.")
        }
    }

    @Test
    fun `a command the owner committed should answer its commit, though the broker's acknowledgement was lost`(): Unit =
        runBlocking {
            val snapshots = runtime(
                "snapshot",
                LogSource(log),
                Subscription("wow.snapshot", SubscriptionFilter("shop"), kind = FunctionKind.SYSTEM) {},
            )
            val coordinator = WaitCoordinator(
                Executor(Kernel(log)),
                waits,
                snapshots,
                signals,
                origin,
                topicRouter = TopicRouter(setOf(order.type), TOPIC, 1, lostAck),
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
                val plan = WaitPlan.of(Hop.committed())
                val results = withTimeout(10.seconds) {
                    coordinator.stream(order, Command("c-1", "r-1", ref, Place(3), wait = plan)).toList()
                }
                // The command committed once, and the wait was signalled so.
                log.read(ref).toList().single().version.assert().isEqualTo(1)
                committed.await().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
                val last = results.last()
                last.status.assert()
                    .describedAs("the answer at ${last.hop}: ${last.error?.code} ${last.error?.message}")
                    .isEqualTo(CommandResult.Status.SUCCEEDED)
                last.hop.assert().isEqualTo(Hop.committed("shop"))
            } finally {
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

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
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Deep review r1 (runtime wait): a request retried through a command topic. The direct path ends a duplicate at
 * COMMITTED, since a duplicate is not signalled again (WaitCoordinator.stream: `waiting.finish(settlement.committed)`
 * when `settlement.committed.duplicate`), as the KDoc promises: "a duplicate stopped at its last known hop". The relay
 * branch has no such step, and the owner's intake (`InheritedWaits.forward`) signals only COMMITTED (duplicate), plus a
 * HANDLED hop `LocalExecution.handledBefore` can prove; a snapshot that has not handled the first commit yet proves
 * none. So the duplicate waits for its deadline and ends WAIT_TIMEOUT instead of SUCCEEDED (duplicate) at COMMITTED.
 */
class TopicDuplicateTest {
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
    private val signals = SignalSender { target, signal, deadline ->
        LocalSignalSender(origin.node, waits).send(target, signal, deadline)
    }

    /** Never opened: the snapshot subscription has not handled the first commit when the duplicate arrives. */
    private val snapshotGate = CompletableDeferred<Unit>()
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

    @Test
    fun `a request retried through the command topic should stop at COMMITTED as a duplicate, not time out`(): Unit =
        runBlocking {
            val snapshots = runtime(
                "snapshot",
                LogSource(log),
                Subscription("wow.snapshot", SubscriptionFilter("shop"), kind = FunctionKind.SYSTEM) {
                    snapshotGate.await()
                },
            )
            val coordinator = WaitCoordinator(
                Executor(Kernel(log)),
                waits,
                snapshots,
                signals,
                origin,
                topicRouter = TopicRouter(setOf(order.type), TOPIC, 1, topics),
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
                // The same request again, waiting for its snapshot, which cannot be told to have handled it.
                val retry = first.copyWith(id = "c-retry", wait = WaitPlan.of(Hop.committed(), Hop.snapshot()))
                val results = held.timedOut(coordinator.stream(order, retry)) { it.hop.stage == Stage.COMMITTED }
                log.read(ref).toList().single().version.assert().isEqualTo(1)
                val last = results.last()
                last.status.assert()
                    .describedAs("the duplicate's answer at ${last.hop}: ${last.error?.code} ${last.error?.message}")
                    .isEqualTo(CommandResult.Status.SUCCEEDED)
                last.duplicate.assert().isTrue()
                last.hop.assert().isEqualTo(Hop.committed("shop"))
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

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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.wait.Origin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Design 04 §6 in one service: the stages, chains, duplicates and timeouts of waits (decisions D61–D65, D69).
 */
class WaitCoordinatorTest : WaitCoordinatorFixtures() {
    @Test
    fun `the default wait answers once the snapshot subscription has handled the commit`(): Unit = runBlocking {
        started()
        val gate = CompletableDeferred<Unit>().also { snapshotGate = it }
        val command = place()
        val result = async { coordinator.execute(order, command) }
        // The snapshot subscription holds the committed entry: what the wait answers on is held.
        eventually { snapshotHeld.get() == 1 }
        result.isCompleted.assert().isFalse()
        registry.size.assert().isEqualTo(1)
        gate.complete(Unit)
        val answered = withTimeout(5.seconds) { result.await() }
        answered.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        answered.hop.assert().isEqualTo(Hop.snapshot("shop"))
        answered.function.kind.assert().isEqualTo(FunctionKind.SNAPSHOT)
        answered.version.assert().isEqualTo(1)
        answered.commandId.assert().isEqualTo(command.id)
        snapshotted.assert().contains("${command.ref.id}@1")
        registry.size.assert().isEqualTo(0)
    }

    @Test
    fun `a wait beyond the node's bound is refused busy before its command runs (R3-4-3)`(): Unit = runBlocking {
        started()
        val gate = CompletableDeferred<Unit>().also { snapshotGate = it }
        val capped = coordinator(WaitOptions(maxRegistered = 1))
        val first = place()
        val held = async { capped.execute(order, first) }
        eventually { registry.size == 1 }
        val second = place()
        val refused = capped.execute(order, second)
        refused.status.assert().isEqualTo(CommandResult.Status.FAILED)
        refused.error!!.code.assert().isEqualTo(ResultCodes.BUSY)
        refused.hop.assert().isEqualTo(Hop.accepted("shop"))
        log.findByRequestId(second.ref, second.requestId).assert().isNull()
        // A COMMITTED wait is registered too, and the stream refuses it alike.
        capped.stream(order, place(wait = WaitPlan.COMMITTED)).toList().single().error!!.code
            .assert().isEqualTo(ResultCodes.BUSY)
        gate.complete(Unit)
        withTimeout(5.seconds) { held.await() }.hop.assert().isEqualTo(Hop.snapshot("shop"))
        registry.size.assert().isEqualTo(0)
        capped.execute(order, second).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        assertThrows<IllegalArgumentException> { WaitOptions(maxRegistered = 0) }
        WaitOptions().maxRegistered.assert().isEqualTo(WaitOptions.DEFAULT_MAX_REGISTERED)
    }

    @Test
    fun `a COMMITTED wait answers once the events are appended, and writes no wait context`(): Unit = runBlocking {
        started()
        snapshotGate = CompletableDeferred()
        val result = coordinator.execute(order, place(wait = WaitPlan.COMMITTED))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.assert().isEqualTo(Hop.committed("shop"))
        result.function.kind.assert().isEqualTo(FunctionKind.COMMAND)
        result.function.name.assert().isEqualTo("Place")
        val partition = log.positionOf(result.ref, 1)!!.partition
        log.tail(partition).take(1).toList().single().transport.assert().isNull()
    }

    @Test
    fun `without a snapshot subscription the default wait is COMMITTED`(): Unit = runBlocking {
        val result = coordinator(WaitOptions(), SubscriptionDirectory.NONE).execute(order, place())
        result.hop.assert().isEqualTo(Hop.committed("shop"))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `an explicit snapshot wait without a snapshot subscription is rejected without executing`(): Unit =
        runBlocking {
            val coordinator = coordinator(WaitOptions(), SubscriptionDirectory.NONE)
            listOf(WaitPlan.SNAPSHOT, WaitPlan.of(Hop.committed(), Hop.snapshot("shop"))).forEach { wait ->
                val command = place(wait = wait)
                val result = coordinator.execute(order, command)
                result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
                result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
                result.error!!.message.assert().contains("shop.order has no snapshot subscription")
                result.hop.assert().isEqualTo(Hop.accepted("shop"))
                log.findByRequestId(command.ref, command.requestId).assert().isNull()
            }
            registry.size.assert().isEqualTo(0)
        }

    @Test
    fun `an explicit snapshot wait is rejected for an aggregate whose snapshots are disabled`(): Unit = runBlocking {
        started()
        val ordersUnsnapshotted = object : SubscriptionDirectory by runtime {
            override fun covers(subscription: String, type: me.ahoo.wow.api.aggregate.AggregateType): Boolean =
                type != order.type && runtime.covers(subscription, type)
        }
        val coordinator = coordinator(WaitOptions(), ordersUnsnapshotted)
        val explicit = place(wait = WaitPlan.SNAPSHOT.within(5.seconds))
        coordinator.execute(order, explicit).error!!.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
        log.findByRequestId(explicit.ref, explicit.requestId).assert().isNull()
        val byDefault = coordinator.execute(order, place(wait = WaitPlan.DEFAULT.within(5.seconds)))
        byDefault.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        byDefault.hop.assert().isEqualTo(Hop.committed("shop"))
    }

    @Test
    fun `a rejection fails the wait at once`(): Unit = runBlocking {
        started()
        snapshotGate = CompletableDeferred()
        val result = withTimeout(2.seconds) { coordinator.execute(order, place(reject = true)) }
        result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        result.error!!.code.assert().isEqualTo("shop.order.refused")
        result.hop.assert().isEqualTo(Hop.committed("shop"))
        // A creation's rejection writes nothing, so it has no version.
        result.version.assert().isNull()
    }

    @Test
    fun `a rejection with events ends the wait at the rejection, at the version its events reached`(): Unit =
        runBlocking {
            started()
            val placed = place()
            coordinator.execute(order, placed).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            // The snapshot never handles the rejection's events: the wait does not wait for it.
            snapshotGate = CompletableDeferred()
            val cancel = Command("c-${sequence.incrementAndGet()}", "r-cancel", placed.ref, Cancel(withEvents = true))
            val rejected = withTimeout(2.seconds) { coordinator.execute(order, cancel) }
            rejected.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            rejected.error!!.code.assert().isEqualTo("shop.order.locked")
            rejected.hop.assert().isEqualTo(Hop.committed("shop"))
            rejected.version.assert().isEqualTo(2)
            rejected.results.assert().isEmpty()
            // A retry is answered as the same rejection, at the same version.
            val retried = withTimeout(2.seconds) { coordinator.execute(order, cancel) }
            retried.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            retried.version.assert().isEqualTo(2)
            retried.duplicate.assert().isTrue()
            // Without events, the version is the one the decision saw.
            val bare = Command("c-${sequence.incrementAndGet()}", "r-bare", placed.ref, Cancel(withEvents = false))
            withTimeout(2.seconds) { coordinator.execute(order, bare) }.version.assert().isEqualTo(2)
        }

    @Test
    fun `the server caps the timeout a caller asks for`(): Unit = runBlocking {
        started()
        snapshotGate = CompletableDeferred()
        val capped = coordinator(WaitOptions(maxTimeout = 150.milliseconds, defaultTimeout = 150.milliseconds))
        val result = withTimeout(
            5.seconds
        ) { capped.execute(order, place(wait = WaitPlan.SNAPSHOT.within(60.seconds))) }
        result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
    }

    @Test
    fun `a subscription that parks the aggregate fails the wait`(): Unit = runBlocking {
        started()
        val result = withTimeout(5.seconds) {
            coordinator.execute(order, place(wait = WaitPlan.of(Hop.handled("order.failing"))))
        }
        result.status.assert().isEqualTo(CommandResult.Status.FAILED)
        result.error!!.code.assert().isEqualTo(ResultCodes.SUBSCRIPTION_PARKED)
        result.hop.assert().isEqualTo(Hop.handled("order.failing", "shop"))
    }

    @Test
    fun `a subscription whose filter passes over the commit reaches its hop at once`(): Unit = runBlocking {
        started()
        val result = coordinator.execute(order, place(wait = WaitPlan.of(Hop.handled("stock.projector"))))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.assert().isEqualTo(Hop.handled("stock.projector", "shop"))
    }

    @Test
    fun `a command that changed nothing reaches its later hops at once`(): Unit = runBlocking {
        started()
        val placed = place(wait = WaitPlan.COMMITTED)
        coordinator.execute(order, placed)
        snapshotGate = CompletableDeferred()
        val touch = Command("c-touch", "r-touch", placed.ref, Touch())
        val result = withTimeout(2.seconds) { coordinator.execute(order, touch) }
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.assert().isEqualTo(Hop.snapshot("shop"))
        result.version.assert().isEqualTo(1)
    }

    @Test
    fun `an invalid chain is rejected without executing the command`(): Unit = runBlocking {
        started()
        val command = place(wait = WaitPlan.of(Hop.snapshot(), Hop.committed()))
        val result = coordinator.execute(order, command)
        result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
        result.hop.assert().isEqualTo(Hop.accepted("shop"))
        log.findByRequestId(command.ref, command.requestId).assert().isNull()
    }

    @Test
    fun `a duplicate request reaches the snapshot when its checkpoint has passed the commit`(): Unit = runBlocking {
        started()
        val first = place()
        val answered = coordinator.execute(order, first)
        answered.hop.assert().isEqualTo(Hop.snapshot("shop"))
        answered.results.assert().isEqualTo(mapOf(ITEMS to 0))
        val retry = first.copyWith(id = "c-retry")
        val result = coordinator.execute(order, retry)
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        result.duplicate.assert().isTrue()
        result.hop.assert().isEqualTo(Hop.snapshot("shop"))
        result.function.kind.assert().isEqualTo(FunctionKind.SNAPSHOT)
        // The results are not persisted (D49): only the first commit answered them.
        result.results.assert().isEmpty()
    }

    @Test
    fun `a duplicate request stops at COMMITTED when the snapshot cannot be told to have handled it`(): Unit =
        runBlocking {
            started()
            snapshotGate = CompletableDeferred()
            val first = place(wait = WaitPlan.COMMITTED)
            coordinator.execute(order, first)
            val result = withTimeout(
                2.seconds
            ) { coordinator.execute(order, first.copyWith(id = "c-retry", wait = WaitPlan.SNAPSHOT)) }
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.duplicate.assert().isTrue()
            result.hop.assert().isEqualTo(Hop.committed("shop"))
            result.version.assert().isEqualTo(1)
        }

    @Test
    fun `a duplicate request on a parked aggregate stops at COMMITTED`(): Unit = runBlocking {
        started()
        val first = place(wait = WaitPlan.of(Hop.handled("order.failing")))
        coordinator.execute(order, first).status.assert().isEqualTo(CommandResult.Status.FAILED)
        eventually { runtime.hasHandled("order.failing", log.findByRequestId(first.ref, first.requestId)!!).not() }
        val result = coordinator.execute(order, first.copyWith(id = "c-retry"))
        result.duplicate.assert().isTrue()
        result.hop.assert().isEqualTo(Hop.committed("shop"))
    }

    @Test
    fun `a chain into a context the node does not know is let through and times out at the hop it reached`(): Unit =
        runBlocking {
            started()
            val shopOnly = object : SubscriptionDirectory by runtime {
                override fun hasContext(context: String): Boolean = context == "shop"
            }
            // The saga's commands are the shop's: a chain that expects them in the payment context is wrong.
            val unknown = Hop.committed("payment")
            val plan = WaitPlan.of(Hop.committed(), Hop.handled("order.saga"), unknown).within(1.seconds)
            val result = coordinator(WaitOptions(), shopOnly).execute(order, place(wait = plan, items = 1))
            result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
            result.hop.assert().isEqualTo(Hop.handled("order.saga", "shop"))
            // With unknown contexts rejected, the plan is invalid and the command does not run.
            val strict = coordinator(WaitOptions(unknownContexts = UnknownContexts.REJECT), shopOnly)
            strict.execute(order, place(wait = plan)).error!!.code.assert().isEqualTo(ResultCodes.WAIT_INVALID)
        }

    @Test
    fun `a signal for a wait on another node goes to the remote sender`(): Unit = runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val sender = LocalSignalSender(
            "node-1",
            registry
        ) { origin, signal, _ -> sent += "${origin.node}:${signal.waitId}" }
        val result = CommandResult(
            commandId = "c-1",
            requestId = "r-1",
            correlationId = "c-1",
            ref = order.type.ref("t", "o-1"),
            hop = Hop.committed("shop"),
            function = commandFunction(order, place()),
            status = CommandResult.Status.SUCCEEDED,
            signalTime = 1L,
        )
        sender.send(
            Origin("node-2", "10.0.0.2:9090", "shop"),
            WaitSignal(waitId = "w-9", result = result),
            Long.MAX_VALUE
        )
        sender.send(origin, WaitSignal(waitId = "w-8", result = result), Long.MAX_VALUE)
        sent.assert().containsExactly("node-2:w-9")
    }
}

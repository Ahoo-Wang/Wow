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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.runtime.subscription.eventually
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Executions that outlive or race their wait: joined and closed by the coordinator, bounded by the deadline as forwards
 * are, never cancelled by a waiter that leaves, and only logged when they fail after it.
 */
class WaitSlowExecutionTest : WaitCoordinatorFixtures() {
    private fun placeSlowly(wait: WaitPlan, id: String = "o-${sequence.incrementAndGet()}") =
        Command("c-$id", "r-$id", order.type.ref("t", id), PlaceSlowly(), wait = wait)

    /** A retry of [command], with its request id: the kernel answers it from the commit, if there is one. */
    private suspend fun retried(coordinator: WaitCoordinator, command: Command<*>): CommandResult {
        eventually { log.findByRequestId(command.ref, command.requestId) != null }
        return coordinator.execute(order, command.copyWith(id = "${command.id}-retry", wait = WaitPlan.COMMITTED))
    }

    @Test
    fun `join waits for an execution that outlived its wait, which settles normally`(): Unit = runBlocking {
        started()
        val command = placeSlowly(WaitPlan.COMMITTED.within(100.milliseconds))
        coordinator.execute(order, command).hop.assert().isEqualTo(Hop.accepted("shop"))
        coordinator.executing.assert().isEqualTo(1)
        val joining = async(Dispatchers.Default) { coordinator.join() }
        delay(100)
        joining.isCompleted.assert().isFalse()

        decideGate.complete(Unit)
        withTimeout(5.seconds) { joining.await() }
        coordinator.executing.assert().isEqualTo(0)
        log.findByRequestId(command.ref, command.requestId)!!.version.assert().isEqualTo(1)
        coordinator.join()
    }

    @Test
    fun `close cancels what still executes`(): Unit = runBlocking {
        started()
        val command = placeSlowly(WaitPlan.COMMITTED.within(100.milliseconds))
        coordinator.execute(order, command).hop.assert().isEqualTo(Hop.accepted("shop"))
        coordinator.close()
        eventually { coordinator.executing == 0 }
        decideGate.complete(Unit)
        log.findByRequestId(command.ref, command.requestId).assert().isNull()
    }

    @Test
    fun `the default timeout is positive, and only capped by the maximum`() {
        WaitOptions(maxTimeout = 5.seconds).defaultTimeout.assert().isEqualTo(WaitPlan.DEFAULT_TIMEOUT)
        assertThrows<IllegalArgumentException> { WaitOptions(defaultTimeout = 0.seconds) }
    }

    @Test
    fun `the deadline bounds a slow execution, which still commits and answers a retry`(): Unit = runBlocking {
        started()
        val command = placeSlowly(WaitPlan.COMMITTED.within(200.milliseconds))
        val emitted = withTimeout(5.seconds) { coordinator.stream(order, command).toList() }
        val timedOut = emitted.single()
        timedOut.status.assert().isEqualTo(CommandResult.Status.FAILED)
        timedOut.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        timedOut.error!!.message.assert().isEqualTo(
            "The wait timed out at accepted(shop), before the command's outcome was known.",
        )
        timedOut.hop.assert().isEqualTo(Hop.accepted("shop"))
        timedOut.version.assert().isNull()
        registry.size.assert().isEqualTo(0)
        log.findByRequestId(command.ref, command.requestId).assert().isNull()

        decideGate.complete(Unit)
        val retry = retried(coordinator, command)
        retry.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        retry.duplicate.assert().isTrue()
        retry.hop.assert().isEqualTo(Hop.committed("shop"))
        retry.version.assert().isEqualTo(1)
    }

    @Test
    fun `a slow execution settled before the deadline answers as usual`(): Unit = runBlocking {
        started()
        val result = background.async { coordinator.execute(order, placeSlowly(WaitPlan.COMMITTED)) }
        deciding.await()
        delay(100)
        decideGate.complete(Unit)
        val answered = withTimeout(5.seconds) { result.await() }
        answered.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        answered.hop.assert().isEqualTo(Hop.committed("shop"))
        answered.version.assert().isEqualTo(1)
    }

    @Test
    fun `a forward that does not settle by the deadline times out at ACCEPTED, and the owner still commits`(): Unit =
        runBlocking {
            started()
            val owner = CompletableDeferred<Unit>()
            val forwarding = WaitCoordinator(
                executor,
                registry,
                runtime,
                signals,
                origin,
                WaitOptions(),
                router = { _, _, _, local ->
                    owner.await()
                    local()
                },
            )
            val command = place(wait = WaitPlan.of(Hop.accepted(), Hop.committed()).within(200.milliseconds))
            val emitted = withTimeout(5.seconds) { forwarding.stream(order, command).toList() }
            emitted.map { it.hop }.assert().containsExactly(Hop.accepted("shop"), Hop.accepted("shop"))
            emitted.first().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            emitted.last().error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)

            owner.complete(Unit)
            val retry = retried(forwarding, command)
            retry.duplicate.assert().isTrue()
            retry.version.assert().isEqualTo(1)
        }

    @Test
    fun `a waiter that leaves does not cancel the execution`(): Unit = runBlocking {
        started()
        val command = placeSlowly(WaitPlan.COMMITTED)
        val waiter = background.async { coordinator.execute(order, command) }
        deciding.await()
        waiter.cancel()
        eventually { registry.size == 0 }
        decideGate.complete(Unit)
        retried(coordinator, command).duplicate.assert().isTrue()
    }

    @Test
    fun `an execution that fails after its wait timed out is only logged`(): Unit = runBlocking {
        val failing = CompletableDeferred<Unit>()
        val failed = CompletableDeferred<Unit>()
        val broken = WaitCoordinator(
            executor,
            registry,
            SubscriptionDirectory.NONE,
            signals,
            origin,
            WaitOptions(),
            router = { _, _, _, _ ->
                try {
                    failing.await()
                    error("The owner is gone.")
                } finally {
                    failed.complete(Unit)
                }
            },
        )
        val result = broken.execute(order, place(wait = WaitPlan.COMMITTED.within(100.milliseconds)))
        result.error!!.code.assert().isEqualTo(ResultCodes.WAIT_TIMEOUT)
        failing.complete(Unit)
        withTimeout(5.seconds) { failed.await() }
        delay(100) // The coordinator logs the failure once it reaches it, with nobody to answer.
        // While waited for, the failure is the caller's.
        val failingNow = WaitCoordinator(
            executor,
            registry,
            SubscriptionDirectory.NONE,
            signals,
            origin,
            WaitOptions(),
            router = { _, _, _, _ -> error("The owner is gone.") },
        )
        runCatching { failingNow.execute(order, place(wait = WaitPlan.COMMITTED)) }
            .exceptionOrNull()!!.message.assert().isEqualTo("The owner is gone.")
        registry.size.assert().isEqualTo(0)
    }
}

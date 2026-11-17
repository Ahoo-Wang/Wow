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

package me.ahoo.wow.runtime.timer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.subscription.SubscriptionId
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * An operator's retry or skip of a target and a parking of one of its timers, at once: the target keeps its ledger
 * entry while a timer of it is PARKED, whichever of the two writes last. Each interleaving is held at the store's or the
 * ledger's call by a gate, with the real runner, loop, store and ledger.
 */
class TimerRecoveryRaceTest : TimerRunnerFixtures() {
    private val parkedId = SubscriptionId(SHOP, TimerOptions.PARKED_SUBSCRIPTION)

    /** Reached by an operator's resolve of the ledger entry, which then waits for [resolving]. */
    private val atResolve = CompletableDeferred<Unit>()
    private val resolving = CompletableDeferred<Unit>()

    /** The ledger, its operator's resolve held at [resolving]. */
    private val heldResolve = object : FailureLedger by ledger {
        override suspend fun resolveAnywhere(subscription: SubscriptionId, ref: AggregateRef): ParkedAggregate? {
            atResolve.complete(Unit)
            resolving.await()
            return ledger.resolveAnywhere(subscription, ref)
        }
    }

    /** Reached by a parking's PARKED write, which then waits for [parkingWrite]. */
    private val atParking = CompletableDeferred<Unit>()
    private val parkingWrite = CompletableDeferred<Unit>()

    /** Whether a PARKED write's answer is lost: the write lands, then the call throws. */
    private var replyLost = false

    /** The store, its PARKED writes held at [parkingWrite]. */
    private val heldParking = object : TimerStore by store {
        override suspend fun settle(changes: List<TimerChange>): List<Boolean> {
            val parking = changes.any { it.next.status == TimerStatus.PARKED }
            if (parking) {
                atParking.complete(Unit)
                parkingWrite.await()
            }
            return store.settle(changes).also {
                if (parking && replyLost) throw IllegalStateException("The reply was lost.")
            }
        }
    }

    private suspend fun entry(): ParkedAggregate? = ledger.parked(parkedId, order("o-1"))

    /** The target's timer [key] PARKED with its ledger entry, as a parking leaves it. */
    private suspend fun parked(key: String) {
        val timer = timer(key)
        store.put(timer)
        val parked = timer.copyWith(status = TimerStatus.PARKED, attempts = 1, outcome = "down")
        store.settle(listOf(TimerChange(timer, parked))).single().assert().isTrue()
        ledger.park(ParkedAggregate(parkedId, order("o-1"), 0, 1, "${timer.id}: down", clock.millis()))
    }

    private fun recoveryWhileParking(skip: Boolean): Unit = runBlocking {
        // What makes it certain: the operator's call has settled the PARKED timer it read (old) and waits at its
        // resolve until the new timer is PARKED in the store, by the real parking of the partition's owner; then the
        // resolve runs. Only the operator's call is left to write the ledger after that.
        parked("old")
        val runner = TimerRunner(SHOP, store, heldResolve, firer, loop(maxAttempts = 1)).also { runners += it }
        val operator = async { if (skip) runner.skip(order("o-1")) else runner.retry(order("o-1")) }
        withTimeout(10.seconds) { atResolve.await() }
        answers["new"] = IllegalStateException("down")
        store.put(timer("new"))
        runner.startPartition(0)
        eventually { status("new").status == TimerStatus.PARKED }
        resolving.complete(Unit)
        withTimeout(10.seconds) { operator.await() }.assert().isEqualTo(1)
        store.parked(order("o-1")).map { it.key }.assert().containsExactly("new")
        entry().assert().isNotNull()
    }

    @Test
    fun `a retry that resolves the target after another timer of it parked leaves the entry for that one`(): Unit =
        recoveryWhileParking(skip = false)

    @Test
    fun `a skip that resolves the target after another timer of it parked leaves the entry for that one`(): Unit =
        recoveryWhileParking(skip = true)

    private fun retryWhileParking(): Unit =
        runBlocking {
            // What makes it certain: the parking recorded its entry and waits at its PARKED write while the
            // operator's retry finds no PARKED timer (it is still FIRING) and resolves the entry; then the PARKED
            // write runs. The parking records the entry again in the same coroutine, after that write.
            answers["new"] = IllegalStateException("down")
            store.put(timer("new"))
            val runner = TimerRunner(SHOP, heldParking, ledger, firer, loop(maxAttempts = 1)).also { runners += it }
            runner.startPartition(0)
            withTimeout(10.seconds) { atParking.await() }
            entry().assert().isNotNull()
            status("new").status.assert().isEqualTo(TimerStatus.FIRING)
            runner.retry(order("o-1")).assert().isEqualTo(0)
            entry().assert().isNull()
            parkingWrite.complete(Unit)
            eventually { status("new").status == TimerStatus.PARKED && entry() != null }
        }

    @Test
    fun `a retry that resolves the target while a timer of it is parking leaves the entry for that one`(): Unit =
        retryWhileParking()

    @Test
    fun `a parking whose PARKED write landed but whose answer was lost records the entry again`() {
        replyLost = true
        retryWhileParking()
    }
}

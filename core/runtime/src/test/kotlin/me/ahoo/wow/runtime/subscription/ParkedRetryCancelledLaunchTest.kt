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

package me.ahoo.wow.runtime.subscription

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.ledger.LedgerRequest
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.runtime.timer.TestClock
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A parked entry's run that a stop cancels after its dispatch took a retry permit and marked the entry running, but
 * before the run's coroutine started: the permit and the mark are given back, so a later scan, of this run of the
 * runtime or the next, runs the entry.
 *
 * What makes each count certain: the runs' scope dispatches on the test's scheduler, so a launched run starts only when
 * the test runs the scheduler, and the stop's cancellation comes between the dispatch and that start. The worker is a
 * mock only at its retry permits and its retry.
 */
class ParkedRetryCancelledLaunchTest {
    private val id = SubscriptionId("shop", "cancelled-launch")
    private val ref = cart("x")
    private val ledger = InMemoryFailureLedger()
    private val retries = ParkedRetries(
        "group",
        ledger,
        ParkedRetryOptions(ParkedRetrySpec(maxRetries = 0)),
        TestClock(),
        ParkedNotifier.NONE,
    )
    private val permits = Semaphore(1)
    private val calls = AtomicInteger()

    private fun worker(semaphore: Semaphore = permits) = mockk<SubscriptionWorker> {
        every { retryPermits } returns semaphore
        coEvery { retry(ref) } coAnswers {
            calls.incrementAndGet()
            true
        }
    }

    private suspend fun requestRetry() {
        ledger.park(ParkedAggregate(id, ref, 0, 1, "failed", 0, request = LedgerRequest.RETRY))
    }

    private suspend fun scan(scope: CoroutineScope, worker: SubscriptionWorker) {
        retries.scan(scope, listOf(id), setOf(0)) { entries -> entries.map { worker } }
    }

    private fun runs(scheduler: TestCoroutineScheduler) =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

    @Test
    fun `a run cancelled before it starts gives its permit back and leaves the request`(): Unit = runTest {
        requestRetry()
        val scope = runs(testScheduler)
        scan(scope, worker())
        // Dispatched: the permit is taken, the run not started.
        permits.availablePermits.assert().isEqualTo(0)
        scope.cancel()
        testScheduler.runCurrent()
        calls.get().assert().isEqualTo(0)
        ledger.parked(id, ref)!!.request.assert().isEqualTo(LedgerRequest.RETRY)
        permits.availablePermits.assert().isEqualTo(1)
    }

    @Test
    fun `the next run of the runtime runs an entry whose run was cancelled before it started`(): Unit = runTest {
        requestRetry()
        val stopped = runs(testScheduler)
        scan(stopped, worker())
        stopped.cancel()
        testScheduler.runCurrent()
        // A runtime started again has new workers, each with its own permits: only the entry's mark could stop it.
        val started = runs(testScheduler)
        try {
            scan(started, worker(Semaphore(1)))
            testScheduler.runCurrent()
            calls.get().assert().isEqualTo(1)
            ledger.parked(id, ref)!!.request.assert().isNull()
        } finally {
            started.cancel()
        }
    }

    @Test
    fun `a run that starts retries once and gives its permit back`(): Unit = runTest {
        requestRetry()
        val scope = runs(testScheduler)
        try {
            scan(scope, worker())
            testScheduler.runCurrent()
            calls.get().assert().isEqualTo(1)
            permits.availablePermits.assert().isEqualTo(1)
            ledger.parked(id, ref)!!.request.assert().isNull()
        } finally {
            scope.cancel()
        }
    }
}

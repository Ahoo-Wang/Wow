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

package me.ahoo.wow.benchmark.workload

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OpenLoopServerTrackerTest {
    @Test
    fun `should close once when handler terminates before send succeeds`() {
        val tracker = OpenLoopServerTracker()
        val sendTicket = tracker.sendStarted("command")
        val handlerTicket = tracker.handlerStarted("command")

        requireNotNull(handlerTicket).handlerTerminated("ON_COMPLETE", failed = false)
        tracker.currentOutstanding().assert().isEqualTo(1)
        sendTicket.sendSucceeded()

        val snapshot = tracker.snapshot()
        snapshot.currentOutstanding.assert().isZero()
        snapshot.naturallyClosed.assert().isEqualTo(1)
        snapshot.invariantViolations().assert().isEmpty()
    }

    @Test
    fun `should close a failed send that never reached the handler`() {
        val tracker = OpenLoopServerTracker()

        tracker.sendStarted("command").sendFailed()

        val snapshot = tracker.snapshot()
        snapshot.sendFailed.assert().isEqualTo(1)
        snapshot.handlerStarted.assert().isZero()
        snapshot.invariantViolations().assert().isEmpty()
    }

    @Test
    fun `should keep a successful send outstanding until handler termination`() {
        val tracker = OpenLoopServerTracker()
        tracker.sendStarted("command").sendSucceeded()

        tracker.currentOutstanding().assert().isEqualTo(1)
        val handlerTicket = requireNotNull(tracker.handlerStarted("command"))
        handlerTicket.handlerTerminated("ON_COMPLETE", failed = true)

        val snapshot = tracker.snapshot()
        snapshot.handlerFailed.assert().isEqualTo(1)
        snapshot.handlerCompleted.assert().isEqualTo(1)
        snapshot.handlerErrored.assert().isZero()
        snapshot.handlerCancelled.assert().isZero()
        snapshot.handlerTerminalSignals.assert().containsEntry("ON_COMPLETE", 1)
        snapshot.invariantViolations().assert().isEmpty()
    }

    @Test
    fun `should reject handler cancellation as an invalid natural close`() {
        val tracker = OpenLoopServerTracker()
        val sendTicket = tracker.sendStarted("command")
        val handlerTicket = requireNotNull(tracker.handlerStarted("command"))

        sendTicket.sendSucceeded()
        handlerTicket.handlerTerminated("CANCEL", failed = false)

        val snapshot = tracker.snapshot()
        snapshot.currentOutstanding.assert().isZero()
        snapshot.handlerCompleted.assert().isZero()
        snapshot.handlerErrored.assert().isZero()
        snapshot.handlerCancelled.assert().isEqualTo(1)
        snapshot.invariantViolations()
            .assert()
            .contains("handlerCancelled[1] != 0")
    }

    @Test
    fun `should classify reactive handler errors separately`() {
        val tracker = OpenLoopServerTracker()
        val sendTicket = tracker.sendStarted("command")
        val handlerTicket = requireNotNull(tracker.handlerStarted("command"))

        sendTicket.sendSucceeded()
        handlerTicket.handlerTerminated("ON_ERROR", failed = true)

        val snapshot = tracker.snapshot()
        snapshot.handlerCompleted.assert().isZero()
        snapshot.handlerErrored.assert().isEqualTo(1)
        snapshot.handlerCancelled.assert().isZero()
        snapshot.invariantViolations().assert().isEmpty()
    }

    @Test
    fun `should report a cancelled send without handler as forced close`() {
        val tracker = OpenLoopServerTracker()
        tracker.sendStarted("command").sendCancelled()

        tracker.forceCloseRemaining().assert().isEqualTo(1)

        val violations = tracker.snapshot().invariantViolations()
        violations.assert().contains("forcedClosed[1] != 0")
    }

    @Test
    fun `should settle concurrent send and handler termination exactly once`() {
        val tracker = OpenLoopServerTracker()
        val sendTicket = tracker.sendStarted("command")
        val handlerTicket = requireNotNull(tracker.handlerStarted("command"))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        executor.execute {
            start.await()
            sendTicket.sendSucceeded()
        }
        executor.execute {
            start.await()
            handlerTicket.handlerTerminated("ON_COMPLETE", failed = false)
        }

        start.countDown()
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS).assert().isTrue()

        val snapshot = tracker.snapshot()
        snapshot.naturallyClosed.assert().isEqualTo(1)
        snapshot.currentOutstanding.assert().isZero()
        snapshot.invariantViolations().assert().isEmpty()
    }
}

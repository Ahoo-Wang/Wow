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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OpenLoopDeadlineWheelTest {
    @Test
    fun `should expire an entry on the first resolution boundary after its deadline`() {
        val wheel = OpenLoopDeadlineWheel<TestDeadline>(
            resolutionNanos = 10,
            maximumTimeoutNanos = 100,
            initialTimeNanos = 0,
            deadlineNanos = TestDeadline::deadlineNanos,
        )
        val deadline = TestDeadline(25)
        val unexpected = { _: TestDeadline -> error("Must not expire while registering.") }
        wheel.add(deadline, nowNanos = 0, onExpired = unexpected)

        wheel.expireDue(20) { error("Must not expire before the deadline.") }
            .expired.assert().isZero()
        wheel.expireDue(29) { error("Must not expire before the next wheel tick.") }
            .expired.assert().isZero()
        val expired = mutableListOf<TestDeadline>()
        val sweep = wheel.expireDue(30, expired::add)

        expired.assert().containsExactly(deadline)
        sweep.expired.assert().isEqualTo(1)
        wheel.size.assert().isZero()
    }

    @Test
    fun `should remove completed entries in constant-time buckets`() {
        val wheel = OpenLoopDeadlineWheel<TestDeadline>(
            resolutionNanos = 10,
            maximumTimeoutNanos = 100,
            initialTimeNanos = 0,
            deadlineNanos = TestDeadline::deadlineNanos,
        )
        val completed = TestDeadline(20)
        val registration = wheel.add(
            completed,
            nowNanos = 0,
            onExpired = { error("Must not expire while registering.") },
        )

        wheel.remove(registration).assert().isTrue()
        wheel.expireDue(100) { error("A removed entry must never expire.") }
        wheel.size.assert().isZero()
    }

    @Test
    fun `should process skipped ticks without expiring a future modulo collision`() {
        val wheel = OpenLoopDeadlineWheel<TestDeadline>(
            resolutionNanos = 10,
            maximumTimeoutNanos = 20,
            initialTimeNanos = 0,
            deadlineNanos = TestDeadline::deadlineNanos,
        )
        val due = TestDeadline(20)
        val futureCollision = TestDeadline(60)
        val unexpected = { _: TestDeadline -> error("Must not expire while registering.") }
        wheel.add(due, nowNanos = 0, onExpired = unexpected)
        wheel.add(futureCollision, nowNanos = 50, onExpired = unexpected)
        val expired = mutableListOf<TestDeadline>()

        wheel.expireDue(50, expired::add)

        expired.assert().containsExactly(due)
        wheel.size.assert().isEqualTo(1)
        wheel.expireDue(60, expired::add)
        expired.assert().containsExactly(due, futureCollision)
        wheel.size.assert().isZero()
    }

    @Test
    fun `should expire an exact-boundary deadline at that boundary`() {
        val wheel = OpenLoopDeadlineWheel<TestDeadline>(
            resolutionNanos = 10,
            maximumTimeoutNanos = 100,
            initialTimeNanos = 0,
            deadlineNanos = TestDeadline::deadlineNanos,
        )
        val deadline = TestDeadline(20)
        wheel.add(
            deadline,
            nowNanos = 0,
            onExpired = { error("Must not expire while registering.") },
        )
        val expired = mutableListOf<TestDeadline>()

        wheel.expireDue(19, expired::add).expired.assert().isZero()
        val sweep = wheel.expireDue(20, expired::add)

        expired.assert().containsExactly(deadline)
        sweep.elapsedTicks.assert().isEqualTo(1)
        sweep.bucketsVisited.assert().isEqualTo(1)
    }

    @Test
    fun `should visit each bucket at most once after a long observer pause`() {
        val wheel = OpenLoopDeadlineWheel<TestDeadline>(
            resolutionNanos = 10,
            maximumTimeoutNanos = 20,
            initialTimeNanos = 0,
            deadlineNanos = TestDeadline::deadlineNanos,
        )
        val deadline = TestDeadline(20)
        wheel.add(
            deadline,
            nowNanos = 0,
            onExpired = { error("Must not expire while registering.") },
        )
        val expired = mutableListOf<TestDeadline>()

        val sweep = wheel.expireDue(10_000, expired::add)

        sweep.elapsedTicks.assert().isEqualTo(1_000)
        sweep.bucketsVisited.assert().isEqualTo(4)
        sweep.candidatesVisited.assert().isEqualTo(1)
        expired.assert().containsExactly(deadline)
    }

    @Test
    fun `should settle concurrent remove and expiry exactly once`() {
        val entryCount = 10_000
        val wheel = OpenLoopDeadlineWheel<TestDeadline>(
            resolutionNanos = 10,
            maximumTimeoutNanos = 100,
            initialTimeNanos = 0,
            deadlineNanos = TestDeadline::deadlineNanos,
        )
        val registrations = (0 until entryCount).map {
            wheel.add(
                TestDeadline(100),
                nowNanos = 0,
                onExpired = { error("Must not expire while registering.") },
            )
        }
        val start = CountDownLatch(1)
        val expired = ConcurrentLinkedQueue<TestDeadline>()
        val removed = ConcurrentLinkedQueue<OpenLoopDeadlineRegistration<TestDeadline>>()
        val executor = Executors.newFixedThreadPool(2)
        executor.execute {
            start.await()
            registrations.forEach { registration ->
                if (wheel.remove(registration)) {
                    removed.add(registration)
                }
            }
        }
        executor.execute {
            start.await()
            wheel.expireDue(100, expired::add)
        }

        start.countDown()
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS).assert().isTrue()

        (removed.size + expired.size).assert().isEqualTo(entryCount)
        wheel.size.assert().isZero()
    }

    @Test
    fun `should not miss registrations racing a sweep watermark`() {
        val entryCount = 10_000
        val wheel = OpenLoopDeadlineWheel<TestDeadline>(
            resolutionNanos = 10,
            maximumTimeoutNanos = 100,
            initialTimeNanos = 0,
            deadlineNanos = TestDeadline::deadlineNanos,
        )
        val start = CountDownLatch(1)
        val expired = ConcurrentLinkedQueue<TestDeadline>()
        val executor = Executors.newFixedThreadPool(2)
        executor.execute {
            start.await()
            repeat(entryCount) {
                wheel.add(
                    TestDeadline(100),
                    nowNanos = 0,
                    onExpired = expired::add,
                )
            }
        }
        executor.execute {
            start.await()
            wheel.expireDue(100, expired::add)
        }

        start.countDown()
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS).assert().isTrue()

        expired.size.assert().isEqualTo(entryCount)
        wheel.size.assert().isZero()
    }

    @Test
    fun `should reject entries outside the active timeout horizon`() {
        val wheel = OpenLoopDeadlineWheel<TestDeadline>(
            resolutionNanos = 10,
            maximumTimeoutNanos = 100,
            initialTimeNanos = 0,
            deadlineNanos = TestDeadline::deadlineNanos,
        )

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            wheel.add(
                TestDeadline(100),
                nowNanos = 100,
                onExpired = { error("Must not expire while registering.") },
            )
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            wheel.add(
                TestDeadline(101),
                nowNanos = 0,
                onExpired = { error("Must not expire while registering.") },
            )
        }
    }

    private class TestDeadline(
        val deadlineNanos: Long,
    )
}

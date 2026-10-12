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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemoryFailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SubscriptionParkingTest {
    private val log = InMemoryEventLog(partitions = 2)
    private val appender = Appender(log)
    private val checkpoints = RecordingCheckpoints()
    private val ledger = InMemoryFailureLedger()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
    private val handled = Handled()
    private val failing = AtomicBoolean(true)
    private val attempts = AtomicInteger()
    private val handlerDelay = AtomicInteger()

    /** Fails x's version 2 while [failing]; records only what it handled successfully. */
    private val subscription = Subscription("parking", SubscriptionFilter("shop")) { entry ->
        if (entry.ref.id == "x" && entry.version == 2) {
            attempts.incrementAndGet()
            if (failing.get()) error("x-2 is broken")
        }
        if (entry.ref.id == "x") delay(handlerDelay.get().toLong())
        handled.record(entry)
    }

    private fun runtime() = SubscriptionRuntime(
        "group",
        log,
        checkpoints,
        ledger,
        listOf(subscription),
        testOptions(maxAttempts = 3),
        clock,
    )

    private val all = setOf(0, 1)

    /** x1 y1 x2 y2 x3 y3 x4: x parks at version 2. */
    private suspend fun parkX(runtime: SubscriptionRuntime) {
        listOf("x", "y", "x", "y", "x", "y", "x").forEach { appender.append(it) }
        runtime.start(all)
        eventually { ledger.isParked("parking", cart("x")) && handled.versionsOf("y").size == 3 }
        eventually { checkpointsReachEnd() }
    }

    private suspend fun checkpointsReachEnd(): Boolean = all.all { partition ->
        val end = appender.ends[partition] ?: 0L
        end == 0L || checkpoints.load("group", "parking", partition) == end
    }

    @Test
    fun `a failing aggregate is parked after its retries and the others move on`(): Unit = runBlocking {
        val runtime = runtime()
        parkX(runtime)
        runtime.stop()

        attempts.get().assert().isEqualTo(3)
        val parked = ledger.parked("parking", cart("x"))!!
        parked.fromVersion.assert().isEqualTo(2)
        parked.reason.assert().contains("x-2 is broken")
        parked.failedAt.assert().isEqualTo(clock.millis())
        handled.versionsOf("x").assert().isEqualTo(listOf(1))
        handled.versionsOf("y").assert().isEqualTo(listOf(1, 2, 3))
    }

    @Test
    fun `resume handles the parked aggregate from its parked version, then its new entries`(): Unit = runBlocking {
        val runtime = runtime()
        parkX(runtime)
        failing.set(false)
        runtime.resume("parking", cart("x")).assert().isTrue()
        ledger.isParked("parking", cart("x")).assert().isFalse()
        handled.versionsOf("x").assert().isEqualTo(listOf(1, 2, 3, 4))
        handled.entries.filter { it.first == "x" && it.second >= 2 }.all { it.third == null }.assert().isTrue()

        appender.append("x")
        eventually { handled.versionsOf("x").size == 5 }
        runtime.stop()
        handled.versionsOf("x").assert().isEqualTo(listOf(1, 2, 3, 4, 5))
    }

    @Test
    fun `resume while new entries arrive handles every version once, in order`(): Unit = runBlocking {
        val runtime = runtime()
        parkX(runtime)
        failing.set(false)
        handlerDelay.set(1)
        val writer = launch(Dispatchers.Default) {
            repeat(60) {
                appender.append("x")
                yield()
            }
        }
        val resumed = async(Dispatchers.Default) { runtime.resume("parking", cart("x")) }
        writer.join()
        resumed.await().assert().isTrue()
        eventually { handled.versionsOf("x").lastOrNull() == 64 }
        delay(20)
        runtime.stop()
        handled.versionsOf("x").assert().isEqualTo((1..64).toList())
    }

    @Test
    fun `a resume that fails again keeps the aggregate parked`(): Unit = runBlocking {
        val runtime = runtime()
        parkX(runtime)
        runtime.resume("parking", cart("x")).assert().isFalse()
        ledger.parked("parking", cart("x"))!!.fromVersion.assert().isEqualTo(2)
        appender.append("x")
        appender.append("y")
        eventually { handled.versionsOf("y").size == 4 }
        eventually { checkpointsReachEnd() }
        handled.versionsOf("x").assert().isEqualTo(listOf(1))
        runtime.stop()
    }

    @Test
    fun `skip resolves the aggregate without handling what was skipped`(): Unit = runBlocking {
        val runtime = runtime()
        parkX(runtime)
        runtime.skip("parking", cart("x")).assert().isTrue()
        runtime.skip("parking", cart("x")).assert().isFalse()
        ledger.isParked("parking", cart("x")).assert().isFalse()
        appender.append("x")
        eventually { handled.versionsOf("x") == listOf(1, 5) }
        runtime.stop()
    }

    @Test
    fun `a parked aggregate stays parked across a restart`(): Unit = runBlocking {
        val first = runtime()
        parkX(first)
        first.stop()

        val second = runtime()
        second.start(all)
        appender.append("x")
        appender.append("y")
        eventually { handled.versionsOf("y").size == 4 }
        eventually { checkpointsReachEnd() }
        handled.versionsOf("x").assert().isEqualTo(listOf(1))

        failing.set(false)
        second.resume("parking", cart("x")).assert().isTrue()
        handled.versionsOf("x").assert().isEqualTo(listOf(1, 2, 3, 4, 5))
        second.stop()
    }

    @Test
    fun `resume of an aggregate that is not parked changes nothing`(): Unit = runBlocking {
        val runtime = runtime()
        appender.append("x")
        runtime.start(all)
        eventually { handled.versionsOf("x") == listOf(1) }
        runtime.resume("parking", cart("x")).assert().isFalse()
        runtime.skip("parking", cart("x")).assert().isFalse()
        runtime.stop()
        runCatching { runtime.resume("parking", cart("x")) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalStateException::class.java)
        ledger.park(ParkedAggregate("parking", cart("x"), 1, "manual", 0))
        runtime.skip("parking", cart("x")).assert().isTrue()
        ledger.isParked("parking", cart("x")).assert().isFalse()
    }
}

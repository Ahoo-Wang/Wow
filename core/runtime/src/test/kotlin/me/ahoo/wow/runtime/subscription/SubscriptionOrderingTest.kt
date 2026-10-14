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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class SubscriptionOrderingTest {
    private val log = InMemoryEventLog(partitions = 1)
    private val appender = Appender(log)
    private val checkpoints = RecordingCheckpoints()
    private val ledger = InMemoryFailureLedger()
    private val handled = Handled()

    private fun runtime(vararg subscriptions: Subscription) =
        SubscriptionRuntime("group", log, checkpoints, ledger, subscriptions.toList(), testOptions())

    @Test
    fun `one aggregate's entries are handled one at a time and in version order`(): Unit = runBlocking {
        val inside = ConcurrentHashMap<String, AtomicInteger>()
        val overlaps = AtomicInteger()
        val maxParallel = AtomicInteger()
        val running = AtomicInteger()
        val runtime = runtime(
            Subscription("ordered", SubscriptionFilter("shop")) { entry ->
                if (inside.computeIfAbsent(entry.ref.id) { AtomicInteger() }.incrementAndGet() > 1) {
                    overlaps.incrementAndGet()
                }
                maxParallel.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                if (Random.nextBoolean()) delay(1) else yield()
                running.decrementAndGet()
                inside.getValue(entry.ref.id).decrementAndGet()
                handled.record(entry)
            },
        )
        val ids = (1..6).map { "a$it" }
        repeat(15) { ids.forEach { appender.append(it) } }
        runtime.start(setOf(0))
        repeat(15) { ids.forEach { appender.append(it) } }
        eventually { handled.size == 180 }
        runtime.stop()

        overlaps.get().assert().isEqualTo(0)
        ids.forEach { handled.versionsOf(it).assert().isEqualTo((1..30).toList()) }
        maxParallel.get().assert().isGreaterThan(1)
    }

    @Test
    fun `a blocked aggregate does not hold up the others`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val runtime = runtime(
            Subscription("blocking", SubscriptionFilter("shop")) { entry ->
                if (entry.ref.id == "slow") gate.await()
                handled.record(entry)
            },
        )
        appender.append("slow")
        repeat(20) { appender.append("fast") }
        runtime.start(setOf(0))

        eventually { handled.versionsOf("fast").size == 20 }
        handled.versionsOf("slow").assert().isEmpty()
        gate.complete(Unit)
        eventually { handled.versionsOf("slow") == listOf(1) }
        runtime.stop()
    }
}

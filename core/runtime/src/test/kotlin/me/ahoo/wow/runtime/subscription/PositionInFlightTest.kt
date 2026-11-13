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
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import org.junit.jupiter.api.Test

/**
 * A position's [SubscriptionPosition.inFlight]: the entries a worker took above its watermark, the window its
 * in-flight bound limits. One entry held keeps the watermark below it, so the entries completed after it still count
 * until it completes.
 */
class PositionInFlightTest {
    private val log = InMemoryEventLog(partitions = 1)
    private val appender = Appender(log)
    private val handled = Handled()

    @Test
    fun `a position should count the entries taken above its watermark, up to the in-flight bound`(): Unit =
        runBlocking {
            val slow = CompletableDeferred<Unit>()
            val runtime = SubscriptionRuntime(
                "group",
                log,
                InMemoryCheckpointStore(),
                InMemoryFailureLedger(),
                listOf(
                    Subscription("projector", SubscriptionFilter("shop")) {
                        if (it.ref.id == "a0") slow.await()
                        handled.record(it)
                    },
                ),
                testOptions(maxInFlight = WINDOW),
            )
            repeat(ENTRIES) { appender.append("a$it") }
            runtime.start()
            try {
                runtime.startPartition(0)
                // a0 is held and the two after it complete: the window is full, and the watermark stays below a0.
                eventually { handled.size == WINDOW - 1 }
                eventually { runtime.positions().single().inFlight == WINDOW }
                runtime.positions().single().watermark.assert().isZero()

                slow.complete(Unit)
                eventually { handled.size == ENTRIES }
                eventually { runtime.positions().single().let { it.inFlight == 0 && it.watermark == ENTRIES.toLong() } }
            } finally {
                runtime.stop()
            }
        }

    private companion object {
        const val WINDOW = 3
        const val ENTRIES = 5
    }
}

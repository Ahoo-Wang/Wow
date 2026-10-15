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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import java.time.Instant

/** Decision D154: where a subscription without a checkpoint starts, as the host overrides its handler's default. */
class SubscriptionStartTest {
    private val log = InMemoryEventLog(partitions = 1)
    private val checkpoints = RecordingCheckpoints()
    private val ledger = InMemoryFailureLedger()

    /** Appends [count] streams to `old`, the n-th (from 1) committed at n seconds. */
    private suspend fun append(count: Int, from: Int = 1) {
        (from until from + count).forEach { n ->
            log.append(
                EventStreamRecord(
                    cart("old"),
                    n,
                    "c-$n",
                    "r-$n",
                    "shop.cart.Act",
                    n * 1_000L,
                    listOf(EventRecord("shop.cart.Added", 1, "{}")),
                    Scope.NONE,
                ),
            )
        }
    }

    private fun second(n: Long): Instant = Instant.ofEpochMilli(n * 1_000)

    @Test
    fun `a start should be read from the beginning, the end or an instant`() {
        SubscriptionStart.parse("beginning").assert().isEqualTo(SubscriptionStart.Beginning)
        SubscriptionStart.parse(" END ").assert().isEqualTo(SubscriptionStart.End)
        SubscriptionStart.parse("2026-10-01T00:00:00Z")
            .assert().isEqualTo(SubscriptionStart.At(Instant.parse("2026-10-01T00:00:00Z")))
        SubscriptionStart.parse("2026-10-01T08:00:00+08:00")
            .assert().isEqualTo(SubscriptionStart.At(Instant.parse("2026-10-01T00:00:00Z")))
        assertThrownBy<IllegalArgumentException> { SubscriptionStart.parse("yesterday") }
            .hasMessageContaining("not \"yesterday\"")
        SubscriptionStart.of(StartFrom.BEGINNING).assert().isEqualTo(SubscriptionStart.Beginning)
        SubscriptionStart.of(StartFrom.END).assert().isEqualTo(SubscriptionStart.End)
        SubscriptionStart.Beginning.toString().assert().isEqualTo("beginning")
        SubscriptionStart.End.toString().assert().isEqualTo("end")
        SubscriptionStart.At(second(1)).toString().assert().isEqualTo("1970-01-01T00:00:01Z")
    }

    @Test
    fun `an instant should start at the first entry committed at or after it, found by a binary search`(): Unit =
        runBlocking {
            val counting = CountingLog(log)
            SubscriptionStart.At(second(5)).checkpointIn(counting, 0).assert().isEqualTo(0L)
            SubscriptionStart.End.checkpointIn(counting, 0).assert().isEqualTo(0L)
            append(1_000)
            SubscriptionStart.Beginning.checkpointIn(counting, 0).assert().isEqualTo(0L)
            SubscriptionStart.End.checkpointIn(counting, 0).assert().isEqualTo(1_000L)
            counting.tails.set(0)
            // The entry at sequence 500 is committed at 500 s: the start is after 499.
            SubscriptionStart.At(second(500)).checkpointIn(counting, 0).assert().isEqualTo(499L)
            counting.tails.get().assert().isLessThanOrEqualTo(11)
            SubscriptionStart.At(Instant.ofEpochMilli(500_500)).checkpointIn(counting, 0).assert().isEqualTo(500L)
            SubscriptionStart.At(second(0)).checkpointIn(counting, 0).assert().isEqualTo(0L)
            SubscriptionStart.At(second(1)).checkpointIn(counting, 0).assert().isEqualTo(0L)
            SubscriptionStart.At(second(1_000)).checkpointIn(counting, 0).assert().isEqualTo(999L)
            // None is committed at or after it yet: after the head, as from the end.
            SubscriptionStart.At(second(1_001)).checkpointIn(counting, 0).assert().isEqualTo(1_000L)
        }

    @Test
    fun `the host's start should override the handler's only for a subscription without a checkpoint`(): Unit =
        runBlocking {
            append(10)
            val fromStart = Handled()
            val fromInstant = Handled()
            val options = testOptions().copyWith(
                starts = mapOf("saga" to SubscriptionStart.Beginning, "reactor" to SubscriptionStart.At(second(7))),
            )
            val runtime = SubscriptionRuntime(
                "group",
                log,
                checkpoints,
                ledger,
                listOf(
                    Subscription("saga", SubscriptionFilter("shop"), StartFrom.END) { fromStart.record(it) },
                    Subscription("reactor", SubscriptionFilter("shop"), StartFrom.END) { fromInstant.record(it) },
                ),
                options,
            )
            runtime.start(setOf(0))
            // Saved at once, as a start from the end is, so a restart does not move it.
            checkpoints.savesOf("reactor").first().assert().isEqualTo(6L)
            eventually { fromStart.size == 10 && fromInstant.size == 4 }
            runtime.stop()
            fromStart.sequences().assert().isEqualTo((1L..10L).toList())
            fromInstant.sequences().assert().isEqualTo((7L..10L).toList())

            // With a checkpoint, the override no longer applies.
            append(2, from = 11)
            val again = Handled()
            val restarted = SubscriptionRuntime(
                "group",
                log,
                checkpoints,
                ledger,
                listOf(Subscription("reactor", SubscriptionFilter("shop"), StartFrom.END) { again.record(it) }),
                testOptions().copyWith(starts = mapOf("reactor" to SubscriptionStart.Beginning)),
            )
            restarted.start(setOf(0))
            eventually { again.size == 2 }
            restarted.stop()
            again.sequences().assert().isEqualTo(listOf(11L, 12L))
        }
}

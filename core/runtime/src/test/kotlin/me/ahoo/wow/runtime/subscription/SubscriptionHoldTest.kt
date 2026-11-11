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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.spi.ledger.ParkedAggregate
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A partition's start: what it reads is read at once and outside the runtime's mutex, its first checkpoints saved in
 * one write; and a partition held back while its node starts the others reads nothing, nor retries a parked entry,
 * until the hold ends, then takes every entry in order.
 */
class SubscriptionHoldTest {
    private val log = InMemoryEventLog(partitions = 2)
    private val appender = Appender(log)
    private val ledger = InMemoryFailureLedger()
    private val handled = Handled()
    private val seen = Handled()

    private val subscriptions = listOf(
        Subscription(NAME, SubscriptionFilter("shop")) { handled.record(it) },
        Subscription("other", SubscriptionFilter("shop")) { seen.record(it) },
    )

    private fun runtime(checkpoints: CheckpointStore, scanInterval: Duration = 1.seconds) = SubscriptionRuntime(
        "group",
        log,
        checkpoints,
        ledger,
        subscriptions,
        testOptions(maxAttempts = 1).copyWith(
            parked = ParkedRetryOptions(
                ParkedRetrySpec(maxRetries = 3, initialBackoff = Duration.ZERO),
                scanInterval = scanInterval
            ),
        ),
    )

    private fun idIn(partition: Int, skip: Int = 0): String =
        generateSequence(0) { it + 1 }.map { "a$it" }.filter { Partitioner.partitionOf(cart(it), 2) == partition }
            .drop(skip).first()

    @Test
    fun `a partition held back should read nothing and retry no parked entry until released, then take everything`(): Unit =
        runBlocking {
            val checkpoints = RecordingCheckpoints()
            val runtime = runtime(checkpoints, scanInterval = 5.milliseconds)
            val (a, p) = idIn(0) to idIn(0, skip = 1)
            val backlog = listOf(a, p, a, p).map { appender.append(it) }
            // p was parked from its first version, as migrate-data imports a 9.x compensation's execution.
            ledger.park(
                ParkedAggregate.of(ID, cart(p), 0, 1, "imported", failedAt = 0L)
            ).assert().isTrue()
            runtime.start()
            val reads = Job()
            try {
                runtime.startPartition(0, reads)
                runtime.partitions.assert().containsExactly(0)
                // Each subscription's first checkpoint is saved at once, all of the partition's in one write.
                checkpoints.batches.assert().containsExactly(2)
                checkpoints.savesOf(NAME).assert().containsExactly(0L)
                checkpoints.savesOf("other").assert().containsExactly(0L)

                // Checked at once: the partition opens its reads only once the job completes, and a scan with no
                // partition reading reads no parked entry.
                handled.size.assert().isZero()
                seen.size.assert().isZero()
                // The scans leave the held partition's parked entries alone, and its lag counts what waits.
                ledger.parked(ID, cart(p))!!.let {
                    it.retries.assert().isZero()
                    it.nextAttemptAt.assert().isNull()
                }
                runtime.positions().map { it.watermark }.assert().containsExactly(0L, 0L)

                reads.complete()
                eventually { seen.size == 4 && handled.size == 4 && ledger.parked(ID, cart(p)) == null }
                // Every entry, each aggregate's in version order.
                seen.sequences().sorted().assert().isEqualTo(backlog)
                seen.versionsOf(a).assert().containsExactly(1, 2)
                seen.versionsOf(p).assert().containsExactly(1, 2)
                handled.versionsOf(a).assert().containsExactly(1, 2)
                // The retry handles p's streams in version order; the tail's copies of them are skipped.
                handled.versionsOf(p).assert().containsExactly(1, 2)
                eventually { runtime.positions().all { it.watermark == backlog.last() } }
            } finally {
                runtime.stop()
            }
        }

    @Test
    fun `a partition's start should read outside the mutex, a second start of it and its stop waiting for it`(): Unit =
        runBlocking {
            val checkpoints = GatedCheckpoints()
            val runtime = runtime(checkpoints)
            appender.append(idIn(0))
            appender.append(idIn(1))
            runtime.start()
            try {
                val first = launch { runtime.startPartition(0) }
                eventually { checkpoints.loading.get() }
                val second = launch { runtime.startPartition(0) }
                val stop = launch { runtime.stopPartition(0) }
                // Partition 0 is reading its checkpoints: partition 1 starts and runs meanwhile.
                withTimeout(5.seconds) { runtime.startPartition(1) }
                runtime.partitions.assert().containsExactly(1)
                eventually { handled.size == 1 }
                // Checked at once: partition 0 is reading its held checkpoints, which the three calls wait for.
                first.isActive.assert().isTrue()
                second.isActive.assert().isTrue()
                stop.isActive.assert().isTrue()

                checkpoints.gate.complete(Unit)
                first.join()
                second.join()
                stop.join()
                // Started once, then stopped by the stop that waited for it.
                runtime.partitions.assert().containsExactly(1)
            } finally {
                runtime.stop()
            }
        }

    @Test
    fun `a subscription added while a partition starts should run in it too`(): Unit = runBlocking {
        val checkpoints = GatedCheckpoints()
        val runtime = runtime(checkpoints)
        val late = Handled()
        val entry = appender.append(idIn(0))
        runtime.start()
        try {
            val starting = launch { runtime.startPartition(0) }
            eventually { checkpoints.loading.get() }
            runtime.add(Subscription("late", SubscriptionFilter("shop")) { late.record(it) })

            checkpoints.gate.complete(Unit)
            starting.join()
            eventually { late.size == 1 && handled.size == 1 && seen.size == 1 }
            late.sequences().assert().containsExactly(entry)
            runtime.positions().map { it.subscription }.assert().containsExactlyInAnyOrder(NAME, "other", "late")
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `a start that outlived a restart of its runtime should open the partition once, in the new runtime`(): Unit =
        runBlocking {
            val checkpoints = GatedCheckpoints()
            val runtime = runtime(checkpoints)
            val entry = appender.append(idIn(0))
            runtime.start()
            try {
                val outlived = launch { runCatching { runtime.startPartition(0) } }
                eventually { checkpoints.loading.get() }
                runtime.stop()
                runtime.start()
                val again = launch { runtime.startPartition(0) }

                checkpoints.gate.complete(Unit)
                outlived.join()
                again.join()
                runtime.partitions.assert().containsExactly(0)
                runtime.positions().map { it.subscription }.assert().containsExactlyInAnyOrder(NAME, "other")
                eventually { handled.size == 1 && seen.size == 1 }
                handled.sequences().assert().containsExactly(entry)
                // The one worker of the subscription is done with the entry: a copy at or below it is dropped.
                eventually { runtime.positions().single { it.subscription == NAME }.watermark >= entry }
                handled.size.assert().isEqualTo(1)
            } finally {
                runtime.stop()
            }
        }

    /** Checkpoints whose loads of partition 0 wait for [gate]: a start of partition 0 hangs on them until then. */
    private class GatedCheckpoints(private val recorded: RecordingCheckpoints = RecordingCheckpoints()) :
        CheckpointStore by recorded {
        val gate = CompletableDeferred<Unit>()
        val loading = AtomicBoolean()

        override suspend fun load(group: String, subscription: String, partition: Int): Long? {
            if (partition == 0) {
                loading.set(true)
                gate.await()
            }
            return recorded.load(group, subscription, partition)
        }
    }

    private companion object {
        val ID = SubscriptionId("shop", NAME)
        const val NAME = "projector"
    }
}

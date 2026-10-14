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
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

/** Partitions start and stop one at a time as the node acquires and hands them off (roadmap 4.2, decision D71). */
class SubscriptionPartitionTest {
    private val log = InMemoryEventLog(partitions = 4)
    private val appender = Appender(log)
    private val checkpoints = RecordingCheckpoints()
    private val handled = Handled()

    /** Checkpoints are saved only by a stop, so each save shows one. */
    private val runtime = SubscriptionRuntime(
        "group",
        log,
        checkpoints,
        InMemoryFailureLedger(),
        listOf(Subscription("s", SubscriptionFilter("shop")) { handled.record(it) }),
        testOptions(checkpointInterval = 1.hours),
    )

    private fun idIn(partition: Int, skip: Int = 0): String =
        generateSequence(0) { it + 1 }.map { "a$it" }.filter { Partitioner.partitionOf(cart(it), 4) == partition }
            .drop(skip).first()

    @Test
    fun `a partition started later is read from its checkpoint, one stopped saves its checkpoint`(): Unit =
        runBlocking {
            runtime.start()
            runtime.partitions.assert().isEmpty()
            val inZero = appender.append(idIn(0))
            val inOne = appender.append(idIn(1))

            runtime.startPartition(1)
            runtime.startPartition(1)
            runtime.partitions.assert().containsExactly(1)
            eventually { handled.size == 1 }
            handled.sequences().assert().containsExactly(inOne)

            runtime.startPartition(0)
            eventually { handled.size == 2 }
            runtime.stopPartition(1)
            runtime.partitions.assert().containsExactly(0)
            // The starting checkpoints are saved at once, 0 included (G33).
            checkpoints.savesOf("s", 1).assert().containsExactly(0L, inOne)
            checkpoints.savesOf("s", 0).assert().containsExactly(0L)

            // Partition 1 is no longer read here; partition 0 still is.
            appender.append(idIn(1, skip = 1))
            val nextInZero = appender.append(idIn(0, skip = 1))
            eventually { handled.size == 3 }
            handled.sequences().assert().containsExactly(inOne, inZero, nextInZero)

            runtime.startPartition(1)
            eventually { handled.size == 4 }
            runtime.stop()
            checkpoints.savesOf("s", 0).assert().containsExactly(0L, nextInZero)
            runtime.stopPartition(0)
        }

    @Test
    fun `a partition is started only on a running runtime and within the log`(): Unit = runBlocking {
        runCatching { runtime.startPartition(0) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalStateException::class.java)
        runtime.start()
        runCatching { runtime.startPartition(4) }.exceptionOrNull()
            .assert().isInstanceOf(IllegalArgumentException::class.java)
        runtime.stopPartition(2)
        runtime.stop()
    }
}

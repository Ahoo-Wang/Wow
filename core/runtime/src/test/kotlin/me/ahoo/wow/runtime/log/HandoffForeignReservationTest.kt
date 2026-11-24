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

package me.ahoo.wow.runtime.log

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.EventLog
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Deep review r1 (runtime log): another node's append reaches a partition's counter before its entry reaches the
 * store. MongoDB reserves the block of an append without the lease in one write and inserts the stream in the next
 * (`MongoSequencer.allocate`, then `MongoLogAppender.insert`), and its `appendedElsewhere` names the partition once,
 * when the block is first listed (`MongoForeignBlocks`). A probe round that falls between the two writes wakes the
 * idle tail, whose read of the head finds nothing yet; the entry that lands a moment later is never named again, so
 * the tail reads it only at the backstop, `backstopInterval` (10 s) later, logging that a node wrote to a partition
 * it no longer owned. HandoffLog's KDoc promises it within a probe interval: "through a block or reservation the
 * probe has not seen, within a probe interval".
 */
class HandoffForeignReservationTest : HandoffLogFixtures() {
    /**
     * The store as MongoDB's probe answers: once another node has [reserved] a block in partition 0, the next call
     * names the partition, and no later one does, whether its entry has landed or not.
     */
    private class ReservingLog(delegate: EventLog) : EventLog by delegate {
        @Volatile
        var reserved = false

        @Volatile
        private var listed = false

        override suspend fun appendedElsewhere(cursors: Map<Int, Long>): Set<Int> {
            if (reserved && !listed && 0 in cursors) {
                listed = true
                return setOf(0)
            }
            return emptySet()
        }
    }

    @Test
    fun `another node's entry that lands after the probe named its reservation should be read within a probe interval`():
        Unit = runBlocking {
        val log = ReservingLog(store)
        // The default backstop: 10 s, the probe interval being shorter.
        val handoff = HandoffLog(log, "bank", HandoffOptions(probeInterval = PROBE), reads)
        val (seen, job) = collect(handoff)
        awaitOpen(handoff)
        handoff.append(next(refs[0]))
        seen.take(1)
        val headsBefore = store.heads.get()
        // Another node reserves its sequence: the counter lists its block; its stream is not inserted yet.
        log.reserved = true
        // The probe names the partition and wakes the idle tail, whose read of the head finds nothing past its cursor.
        eventually { store.heads.get() > headsBefore }
        // The other node's insert lands: a commit no hand-over brings.
        val landed = TimeSource.Monotonic.markNow()
        store.append(next(refs[1]))
        val entry = withTimeoutOrNull(PROBE * 4) { seen.receive() }
        entry.assert()
            .describedAs(
                "another node's entry, read ${landed.elapsedNow()} after it landed (probe interval $PROBE, " +
                    "backstop ${HandoffOptions(probeInterval = PROBE).backstopInterval})",
            )
            .isNotNull()
        job.cancelAndJoin()
    }

    private companion object {
        val PROBE = 300.milliseconds
    }
}

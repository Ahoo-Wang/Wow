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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.observation.ObservedEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A tail's probes on schedule shortly after it opens ([HandoffOptions.scheduledProbes]): another node's commit above
 * this node's block, which no gap in this node's own run shows while it keeps committing, is read at the next one.
 */
class HandoffScheduledProbeTest : HandoffLogFixtures() {
    /** The store as it is with another node's commit at [FOREIGN], above this node's sequences, once [foreign] is set. */
    private val above = ForeignAbove(store)

    private fun probing(window: Duration) =
        HandoffLog(above, "bank", HandoffOptions(probeInterval = PROBE, scheduledProbes = window), reads)

    private fun scheduled() = reads.reasons.count { it == ObservedEvent.TailRead.Reason.SCHEDULED }

    /** This node commits in partition 0 every [EVERY], never pausing a probe interval, until cancelled. */
    private fun CoroutineScope.committing(log: HandoffLog) = launch(Dispatchers.Default) {
        while (isActive) {
            log.append(next(refs[1]), null)
            delay(EVERY)
        }
    }

    @Test
    fun `another node's commit above this node's block is read at a scheduled probe while this node commits`(): Unit =
        runBlocking {
            val log = probing(window = 5.seconds)
            val (seen, tail) = collect(log)
            awaitOpen(log)
            val commits = committing(log)
            delay(100.milliseconds)
            above.foreign = next(refs[2])
            // Within the window's next probes, while this node never pauses for an idle one.
            val found = withTimeoutOrNull(3.seconds) {
                while (seen.receive().position.sequence != FOREIGN) Unit
            }
            commits.cancel()
            tail.cancel()
            found.assert().isNotNull()
            scheduled().assert().isGreaterThan(0)
        }

    @Test
    fun `after the window the tail reads on its idle rule, as without one`(): Unit = runBlocking {
        val log = probing(window = 300.milliseconds)
        val (seen, tail) = collect(log)
        awaitOpen(log)
        val commits = committing(log)
        // Past the window: no probe on schedule any more.
        delay(800.milliseconds)
        val before = scheduled()
        above.foreign = next(refs[2])
        val early = withTimeoutOrNull(1.seconds) {
            while (seen.receive().position.sequence != FOREIGN) Unit
        }
        early.assert().isNull()
        scheduled().assert().isEqualTo(before)
        // This node pauses: the idle probe reads it.
        commits.cancel()
        val late = withTimeoutOrNull(3.seconds) {
            while (seen.receive().position.sequence != FOREIGN) Unit
        }
        tail.cancel()
        late.assert().isNotNull()
        reads.reasons.assert().contains(ObservedEvent.TailRead.Reason.IDLE)
    }

    @Test
    fun `a tail without the window never probes on schedule`(): Unit = runBlocking {
        val log = probing(window = Duration.ZERO)
        val (_, tail) = collect(log)
        awaitOpen(log)
        val commits = committing(log)
        delay(PROBE * 4)
        commits.cancel()
        tail.cancel()
        scheduled().assert().isEqualTo(0)
    }

    @Test
    fun `the window is zero or more`() {
        assertThrows<IllegalArgumentException> { HandoffOptions(scheduledProbes = (-1).seconds) }
    }

    /**
     * [delegate] with another node's commit at [FOREIGN], once [foreign] is set: the head shows it, and a read from below
     * it delivers this node's entries up to the delegate's head, then it.
     */
    private class ForeignAbove(private val delegate: EventLog) : EventLog by delegate {
        @Volatile
        var foreign: EventStreamRecord? = null

        override suspend fun head(partition: Int): Long? {
            val own = delegate.head(partition)
            return if (partition == 0 && foreign != null) FOREIGN else own
        }

        override fun tail(partition: Int, after: Long?): Flow<LogEntry> = flow {
            val other = foreign.takeIf { partition == 0 }
            val own = delegate.head(partition) ?: 0L
            if (own > (after ?: 0L)) {
                emitAll(
                    delegate.tail(partition, after).transformWhile {
                        emit(it)
                        it.position.sequence < own
                    },
                )
            }
            if (other != null && (after ?: 0L) < FOREIGN) {
                emit(LogEntry(LogPosition(partition, FOREIGN), other))
            }
            awaitCancellation()
        }
    }

    private companion object {
        const val FOREIGN = 10_000L
        val PROBE = 200.milliseconds
        val EVERY = 40.milliseconds
    }
}

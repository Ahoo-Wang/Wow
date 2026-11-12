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
package me.ahoo.wow.spi.log

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/** [EventLog.appendAll]'s default: each stream appended alone, a failure thrown only while nothing may be written. */
class AppendAllTest {
    private val type = AggregateType("ctx", "agg")

    private fun stream(id: String, version: Int = 1): Append = Append.of(
        EventStreamRecord.of(
            type.ref("tenant", id),
            version,
            "command-$id-$version",
            "request-$id-$version",
            "ctx.agg.Act",
            0L,
            listOf(EventRecord.of("ctx.agg.Acted", 1, "{}")),
            Scope.NONE,
        )
    )

    @Test
    fun `a failure while nothing of the call may be written is thrown, a conflict before it included`() {
        val lost = IOException("lost")
        val alone = Scripted(mapOf("a" to lost))
        assertThrows<IOException> { runBlocking { alone.appendAll(listOf(stream("a"))) } }.assert().isSameAs(lost)
        val afterConflict = Scripted(mapOf("b" to lost), conflicts = setOf("a"))
        val streams = listOf(stream("a"), stream("b"))
        assertThrows<IOException> { runBlocking { afterConflict.appendAll(streams) } }.assert().isSameAs(lost)
    }

    @Test
    fun `once a stream committed, a later failure is unknown, and so are the streams not tried after it`(): Unit =
        runBlocking {
            val lost = IOException("lost")
            val log = Scripted(mapOf("b" to lost))
            val outcomes = log.appendAll(listOf(stream("a"), stream("b"), stream("c"), stream("b", 2)))
            outcomes[0].assert().isInstanceOf(AppendOutcome.Committed::class.java)
            (outcomes[1] as AppendOutcome.Unknown).cause.assert().isSameAs(lost)
            (outcomes[2] as AppendOutcome.Unknown).cause.assert().isSameAs(lost)
            // The aggregate whose stream is unknown does not go on: its next stream is not appended.
            outcomes[3].assert().isEqualTo(notAppendedAfter(stream("b").stream, outcomes[1]))
            log.appended.assert().containsExactly("a", "b")
        }

    @Test
    fun `a stream whose outcome is unknown may have committed, so a later failure is unknown too`(): Unit =
        runBlocking {
            val lost = IOException("lost")
            val timedOut = IOException("timed out")
            val log = Scripted(mapOf("b" to lost), unknown = mapOf("a" to timedOut))
            val outcomes = log.appendAll(listOf(stream("a"), stream("b")))
            (outcomes[0] as AppendOutcome.Unknown).cause.assert().isSameAs(timedOut)
            (outcomes[1] as AppendOutcome.Unknown).cause.assert().isSameAs(lost)
        }

    @Test
    fun `a cancellation is thrown, whatever the call committed before it`() {
        val log = Scripted(mapOf("b" to CancellationException("cancelled")))
        assertThrows<CancellationException> { runBlocking { log.appendAll(listOf(stream("a"), stream("b"))) } }
        log.appended.assert().containsExactly("a", "b")
    }

    /**
     * A log whose append fails for the ids in [failures], conflicts for [conflicts], is unknown for [unknown], and
     * commits the others.
     */
    private class Scripted(
        private val failures: Map<String, Exception>,
        private val conflicts: Set<String> = emptySet(),
        private val unknown: Map<String, Exception> = emptyMap(),
    ) : EventLog {
        val appended = mutableListOf<String>()
        override val partitions: Int = 1

        override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome {
            appended += stream.ref.id
            failures[stream.ref.id]?.let { throw it }
            if (stream.ref.id in conflicts) return AppendOutcome.Conflict.of(stream.version)
            unknown[stream.ref.id]?.let { return AppendOutcome.Unknown.of(it) }
            return AppendOutcome.Committed.of(LogPosition(0, appended.size.toLong()))
        }

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = emptyFlow()

        override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> = emptyFlow()

        override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = null

        override suspend fun findByRequestId(
            type: AggregateType,
            tenantId: String,
            requestId: String,
        ): EventStreamRecord? = null

        override fun tail(partition: Int, after: Long?): Flow<LogEntry> = emptyFlow()

        override suspend fun head(partition: Int): Long? = null
    }
}

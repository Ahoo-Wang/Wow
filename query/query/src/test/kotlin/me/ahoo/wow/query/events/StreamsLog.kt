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

package me.ahoo.wow.query.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.log.LogPosition
import me.ahoo.wow.spi.log.Transport

/** The streams [add] gives, read by [read]: what the events model reads of a log; it writes and tails nothing. */
class StreamsLog : EventLog {
    private val streams = mutableListOf<EventStreamRecord>()

    override val partitions: Int = 1

    fun add(stream: EventStreamRecord) {
        streams += stream
    }

    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> =
        streams.filter { it.ref == ref && it.version in fromVersion..toVersion }.sortedBy { it.version }.asFlow()

    override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome = unused()

    override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> = unused()

    override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = unused()

    override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
        unused()

    override suspend fun findByRequestId(tenantId: String, requestId: String): EventStreamRecord? = unused()

    override fun tail(partition: Int, after: Long?): Flow<LogEntry> = unused()

    override suspend fun head(partition: Int): Long? = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("The events model only reads streams.")
}

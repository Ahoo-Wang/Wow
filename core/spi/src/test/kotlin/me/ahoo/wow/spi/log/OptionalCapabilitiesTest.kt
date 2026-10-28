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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.junit.jupiter.api.Test

/** A backend that implements only what it must has none of the optional capabilities (finding R5.B-9). */
class OptionalCapabilitiesTest {
    private object Required : EventLog, SnapshotStore {
        override val partitions: Int = 1

        override suspend fun append(stream: EventStreamRecord, transport: ByteArray?): AppendOutcome = error("unused")

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = emptyFlow()

        override fun readEntries(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<LogEntry> = emptyFlow()

        override suspend fun positionOf(ref: AggregateRef, version: Int): LogPosition? = null

        override suspend fun findByRequestId(type: AggregateType, tenantId: String, requestId: String) = null

        override fun tail(partition: Int, after: Long?): Flow<LogEntry> = emptyFlow()

        override suspend fun head(partition: Int): Long? = null

        override suspend fun save(snapshot: SnapshotRecord): Boolean = false

        override suspend fun load(ref: AggregateRef): SnapshotRecord? = null
    }

    @Test
    fun `a snapshot store cannot rebuild unless it says otherwise`() {
        Required.rebuilds.assert().isNull()
    }
}

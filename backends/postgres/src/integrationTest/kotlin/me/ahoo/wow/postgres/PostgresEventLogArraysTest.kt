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

package me.ahoo.wow.postgres

import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.tck.log.EventLogSpec

/**
 * The log's contract with every group across partitions bound as arrays ([PostgresBatches]), as the log binds those
 * above [PostgresBatches.ARRAYS_ABOVE] rows; [PostgresEventLogTest] runs it with the default, the values form below it.
 */
class PostgresEventLogArraysTest : EventLogSpec() {
    override fun createLog(context: String, partitions: Int): EventLog =
        PostgresFixture.log(partitions, context, options = PostgresEventLogOptions(arraysAbove = 0))

    override val opensOneStorePerContext: Boolean = true

    override fun createSnapshots(context: String): SnapshotStore =
        PostgresSnapshotStore(PostgresFixture.dataSource, context, arraysAbove = 0)

    override fun snapshotTailsOf(log: EventLog, snapshots: SnapshotStore): SnapshotTailReader =
        (log as PostgresEventLog).snapshotTailsWith(snapshots as PostgresSnapshotStore)

    /** As [PostgresEventLogTest] refuses it: a `\u0000` in a payload string, which `jsonb` does not take. */
    override fun refused(stream: EventStreamRecord): EventStreamRecord = stream.copyWith(
        events = listOf(EventRecord("x.aggregate.Named", 1, """{"name":"a\u0000b"}""")),
    )
}

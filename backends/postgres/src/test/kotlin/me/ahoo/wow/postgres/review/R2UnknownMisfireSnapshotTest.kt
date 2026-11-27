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

package me.ahoo.wow.postgres.review

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import org.junit.jupiter.api.Test

/**
 * Round two, the upgrade path (D487 ① third item, D490 ⑤): the snapshot's `schedules` column, as a node of the next
 * minor writes it with a `Misfire` case this node does not know. [PostgresSnapshotStore.snapshotOf] decodes the
 * column with [PostgresSnapshotStore.decodeSchedules] on every load, so a snapshot that cannot be decoded fails the
 * load of its aggregate (and the log's replay fails on the same `wow.schedule.ScheduleSet`, R2UnknownMisfireTest).
 */
class R2UnknownMisfireSnapshotTest {
    @Test
    fun `a snapshot whose schedule names a misfire this node does not know should still be read`() {
        val written = PostgresSnapshotStore.encodeSchedules(mapOf("report" to ScheduleRule.cron("0 0 0 * * ?")))
        written.assert().contains(""""misfire":"coalesce"""")
        // The same column as the next minor writes it, with a case it added.
        val newer = written.replace(""""misfire":"coalesce"""", """"misfire":"fire-once"""")

        val read = runCatching { PostgresSnapshotStore.decodeSchedules(newer) }

        read.exceptionOrNull().assert().isNull()
        read.getOrThrow().keys.assert().containsExactly("report")
    }
}

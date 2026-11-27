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

package me.ahoo.wow.mongo.review

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.mongo.MongoRules
import org.junit.jupiter.api.Test

/**
 * Round two, the upgrade path (D487 ① third item, D490 ⑤): a snapshot document's `schedules`, as a node of the next
 * minor writes it with a `Misfire` case this node does not know. `MongoSnapshotStore.snapshotOf` reads each of them
 * with [MongoRules.of] on every load (`MongoSnapshotStore.kt:459-460`), so the load of the aggregate fails (and the
 * log's replay fails on the same `wow.schedule.ScheduleSet`, R2UnknownMisfireTest). The timer store names the same
 * value and skips its row by design (`MongoTimerRulesTest`); the snapshot has no such path.
 */
class R2UnknownMisfireSnapshotTest {
    @Test
    fun `a snapshot whose schedule names a misfire this node does not know should still be read`() {
        val written = MongoRules.document(ScheduleRule.cron("0 0 0 * * ?"))
        written.getString("misfire").assert().isEqualTo("coalesce")
        // The same sub-document as the next minor writes it, with a case it added.
        written["misfire"] = "fire-once"

        val read = runCatching { MongoRules.of(written) }

        read.exceptionOrNull().assert().isNull()
    }
}

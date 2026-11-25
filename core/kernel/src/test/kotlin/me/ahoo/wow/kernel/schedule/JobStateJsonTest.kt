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

package me.ahoo.wow.kernel.schedule

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test

class JobStateJsonTest {
    @Test
    fun `a job state should read back from its snapshot though only the job aggregate sets it`() {
        val state = jobStateOf("report", enabled = true, partition = 3, slot = 42, due = 1_000).also {
            it.timer = "report#tick@42"
            it.ticks = 7
            it.skipped = 2
        }
        val read = WowJson.mapper.readValue(WowJson.mapper.writeValueAsString(state), JobState::class.java)
        listOf(read.job, read.rule, read.override, read.enabled, read.partition, read.timer, read.slot, read.due)
            .assert().containsExactly("report", null, null, true, 3, "report#tick@42", 42L, 1_000L)
        listOf(read.ticks, read.skipped).assert().containsExactly(7L, 2L)
    }

    @Test
    fun `a job state of a job alone should hold the facts a new instance starts with`() {
        val fresh = JobState()
        val state = jobStateOf("report")
        listOf(state.job, state.rule, state.enabled, state.partition, state.slot, state.due)
            .assert().containsExactly("report", fresh.rule, fresh.enabled, fresh.partition, fresh.slot, fresh.due)
    }
}

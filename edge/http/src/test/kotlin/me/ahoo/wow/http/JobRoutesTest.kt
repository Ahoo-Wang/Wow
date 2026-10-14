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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.kernel.schedule.ScheduleJobModel
import org.junit.jupiter.api.Test

/**
 * The routes of a context's job commands (decision D198): a tenant-level caller enables a TENANT job's instance in its
 * own tenant, with its own time zone, reschedules and disables it; a context without jobs has no such routes.
 */
class JobRoutesTest {
    private val report = ScheduleJob("report", ScheduleRule.Cron("0 0 9 * * *"), JobScope.TENANT)
    private val node = HttpNode(jobs = listOf(report))
    private val tenantLevel = mapOf("X-Tenant-Level" to "true")

    @Test
    fun `a tenant enables, reschedules and disables its instance of a job`() {
        val enabled = node.post(
            "/shop/schedule/report/wow.schedule.enable",
            """{"job":"report","rule":{"zone":"Asia/Shanghai"}}""",
            headers = tenantLevel,
        )
        enabled.status.assert().withFailMessage { enabled.body.toString() }.isEqualTo(200)
        enabled.body!!["ref"]["tenantId"].asString().assert().isEqualTo(TENANT)
        node.post("/shop/schedule/report/wow.schedule.reschedule", """{"rule":null}""", headers = tenantLevel)
            .status.assert().isEqualTo(200)
        node.post("/shop/schedule/report/wow.schedule.disable", "{}", headers = tenantLevel)
            .status.assert().isEqualTo(200)
    }

    @Test
    fun `an unknown job is refused as an invalid job command`() {
        val answer = node.post("/shop/schedule/other/wow.schedule.enable", """{"job":"other"}""", headers = tenantLevel)
        answer.status.assert().isEqualTo(400)
        answer.body!!["code"].asString().assert().isEqualTo(ScheduleJobModel.JOB_INVALID)
    }

    @Test
    fun `a context without jobs has no job routes`() {
        HttpNode().post("/shop/schedule/report/wow.schedule.enable", """{"job":"report"}""").status.assert()
            .isEqualTo(404)
    }
}

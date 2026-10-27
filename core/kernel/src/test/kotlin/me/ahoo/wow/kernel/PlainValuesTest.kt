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

package me.ahoo.wow.kernel

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.schedule.ScheduleJob
import org.junit.jupiter.api.Test

/** The kernel's plain values (finding R5.A-24): made by `of`, equal by their fields, copied by `copyWith`, said by name. */
class PlainValuesTest {
    @Test
    fun `a command type is made by of, equal by its fields`() {
        val type = CommandType.of("shop.order.Pay", String::class.java)
        type.assert().isEqualTo(type).isEqualTo(CommandType.of("shop.order.Pay", String::class.java, emptySet()))
        type.hashCode().assert().isEqualTo(CommandType.of("shop.order.Pay", String::class.java).hashCode())
        type.assert().isNotEqualTo(CommandType.of("shop.order.Pay", String::class.java, setOf("Pay")))
        type.toString().assert().startsWith("CommandType(name=shop.order.Pay")
    }

    @Test
    fun `a handler's retry is said with its fields`() {
        val retry = checkNotNull(HandlerRetry.of(maxAttempts = 3, initialBackoff = "1s"))
        retry.assert().isEqualTo(retry).isEqualTo(HandlerRetry.of(maxAttempts = 3, initialBackoff = "1s"))
        retry.toString().assert().startsWith("HandlerRetry(maxAttempts=3")
    }

    @Test
    fun `a schedule job is made by of and copied`() {
        val rule = ScheduleRule.Cron("0 0 9 * * MON-FRI", "Asia/Shanghai", Misfire.CATCH_UP)
        val job = ScheduleJob.of("report", rule)
        job.assert().isEqualTo(job).isEqualTo(ScheduleJob.of("report", rule, JobScope.SYSTEM, false))
        job.copyWith(sharded = true).assert().isEqualTo(ScheduleJob.of("report", rule, sharded = true))
        job.copyWith(scope = JobScope.TENANT).scope.assert().isEqualTo(JobScope.TENANT)
        job.toString().assert().startsWith("ScheduleJob(name=report")
    }
}

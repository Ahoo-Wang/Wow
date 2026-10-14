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

package me.ahoo.wow.api.schedule

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/** The schedule model of decision D198: its rules, its system commands and events, and a slot's context. */
class SchedulesTest {
    private val ref = AggregateType("shop", "order").ref("t", "o-1")

    @Test
    fun `a rule is due as it says, counted from the commit that sets it`() {
        ScheduleRule.at(Instant.ofEpochMilli(5)).assert().isEqualTo(ScheduleRule.At(5))
        ScheduleRule.At(5).firstDue(100).assert().isEqualTo(5)
        ScheduleRule.after(1.minutes).assert().isEqualTo(ScheduleRule.After(60_000))
        ScheduleRule.After(60_000).firstDue(100).assert().isEqualTo(60_100)
        ScheduleRule.After(10).firstDue(Long.MAX_VALUE - 5).assert().isEqualTo(Long.MAX_VALUE)
        // A cron rule is evaluated by the runtime (roadmap 6.8c).
        ScheduleRule.cron("0 0 9 * * *").assert().isEqualTo(ScheduleRule.Cron("0 0 9 * * *", "UTC", Misfire.COALESCE))
        ScheduleRule.Cron("0 0 9 * * *", "Asia/Shanghai", Misfire.CATCH_UP).firstDue(100).assert().isNull()
    }

    @Test
    fun `an instant beyond epoch milliseconds is the first or the last of them`() {
        ScheduleRule.at(Instant.MAX).assert().isEqualTo(ScheduleRule.At(Long.MAX_VALUE))
        ScheduleRule.at(Instant.MIN).assert().isEqualTo(ScheduleRule.At(Long.MIN_VALUE))
    }

    @Test
    fun `a rule, a command or an event that does not add up is refused`() {
        assertThrownBy<IllegalArgumentException> { ScheduleRule.After(-1) }
        assertThrownBy<IllegalArgumentException> { ScheduleRule.Cron(" ") }
        assertThrownBy<IllegalArgumentException> { ScheduleRule.Cron("0 0 9 * * *", " ") }
        assertThrownBy<IllegalArgumentException> { SetSchedule(" ", ScheduleRule.At(1), "shop.order.Cancel", "{}") }
        assertThrownBy<IllegalArgumentException> { SetSchedule("k", ScheduleRule.At(1), " ", "{}") }
        assertThrownBy<IllegalArgumentException> { CancelSchedule(" ") }
        assertThrownBy<IllegalArgumentException> {
            ScheduleSet(
                " ",
                ScheduleRule.At(1),
                ref,
                "shop.order.Cancel",
                "{}"
            )
        }
        assertThrownBy<IllegalArgumentException> { ScheduleSet("k", ScheduleRule.At(1), ref, " ", "{}") }
        assertThrownBy<IllegalArgumentException> { ScheduleCancelled(" ") }
        assertThrownBy<IllegalArgumentException> { ScheduleContext(" ", "k", 1, 0) }
        assertThrownBy<IllegalArgumentException> { ScheduleContext("t", " ", 1, 0) }
        assertThrownBy<IllegalArgumentException> { ScheduleContext("t", "k", 0, 0) }
    }

    @Test
    fun `the system commands and events have system names`() {
        Schedules.nameOf(SetSchedule::class.java).assert().isEqualTo(Schedules.SET)
        Schedules.nameOf(CancelSchedule::class.java).assert().isEqualTo(Schedules.CANCEL)
        Schedules.nameOf(ScheduleSet::class.java).assert().isEqualTo(Schedules.SCHEDULE_SET)
        Schedules.nameOf(ScheduleCancelled::class.java).assert().isEqualTo(Schedules.SCHEDULE_CANCELLED)
        Schedules.nameOf(String::class.java).assert().isNull()
        Schedules.COMMANDS.keys.assert().containsExactly("wow.schedule.Set", "wow.schedule.Cancel")
        Schedules.EVENTS.keys.assert().containsExactly("wow.ScheduleSet", "wow.ScheduleCancelled")
    }

    @Test
    fun `a command sent for a slot carries its context in its headers`() {
        val context = ScheduleContext("$ref#pay@1", "pay", 2, 1_000)
        val command = Command("c", "r", ref, "body", headers = mapOf("app" to "web") + context.headers())
        command.schedule.assert().isEqualTo(context)
        Command("c", "r", ref, "body").schedule.assert().isNull()
        ScheduleContext.of(context.headers() - ScheduleContext.KEY).assert().isNull()
        ScheduleContext.of(context.headers() + (ScheduleContext.SLOT to "x")).assert().isNull()
        ScheduleContext.of(context.headers() - ScheduleContext.SLOT).assert().isNull()
        ScheduleContext.of(context.headers() - ScheduleContext.DUE).assert().isNull()
        val skipped = context.copy(slot = 5, skipped = 3)
        skipped.headers()[ScheduleContext.SKIPPED].assert().isEqualTo("3")
        ScheduleContext.of(skipped.headers()).assert().isEqualTo(skipped)
        // A slot skips only the slots before it: a count beyond them is read as none.
        ScheduleContext.of(
            skipped.headers() + (ScheduleContext.SKIPPED to "5")
        ).assert().isEqualTo(skipped.copy(skipped = 0))
        ScheduleContext.of(skipped.headers() + (ScheduleContext.SKIPPED to "x"))?.skipped.assert().isEqualTo(0L)
        context.headers().keys.assert().doesNotContain(ScheduleContext.SKIPPED)
        assertThrownBy<IllegalArgumentException> { ScheduleContext("t", "k", 1, 0, skipped = 1) }
        ScheduleContext.HEADERS.assert().containsExactlyInAnyOrder(*skipped.headers().keys.toTypedArray())
    }
}

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

package me.ahoo.wow.spi.timer

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.schedule.ScheduleContext
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test

/** A timer of decision D198: its rule as Wow's JSON writes it, its slots, and how it is made from `wow.ScheduleSet`. */
class TimerTest {
    private val ref = AggregateType("shop", "order").ref("t", "o-1")

    private fun timer(slot: Long = 1) = Timer(
        id = "$ref#pay@1",
        owner = ref,
        key = "pay",
        version = 1,
        partition = 0,
        rule = ScheduleRule.At(5),
        dueAt = 5,
        target = ref,
        commandType = "shop.order.Cancel",
        body = "{}",
        slot = slot,
    )

    @Test
    fun `a rule is written with its type and read back as written`() {
        listOf(
            ScheduleRule.At(5) to """{"type":"at","epochMillis":5}""",
            ScheduleRule.After(60_000) to """{"type":"after","millis":60000}""",
            ScheduleRule.Cron("0 0 9 * * *", "UTC", Misfire.CATCH_UP) to
                """{"type":"cron","expression":"0 0 9 * * *","zone":"UTC","misfire":"CATCH_UP"}""",
        ).forEach { (rule, json) ->
            WowJson.mapper.writeValueAsString(rule).assert().isEqualTo(json)
            WowJson.mapper.readValue(json, ScheduleRule::class.java).assert().isEqualTo(rule)
        }
    }

    @Test
    fun `a slot is sent with its own request id and context`() {
        timer(slot = 3).requestId.assert().isEqualTo("$ref#pay@1#3")
        timer(slot = 3).context.assert().isEqualTo(ScheduleContext("$ref#pay@1", "pay", 3, 5))
        // Claimed or retried, the slot is still sent as due when it was.
        timer(slot = 3).copyWith(dueAt = 9).context.due.assert().isEqualTo(5L)
        assertThrownBy<IllegalArgumentException> { timer(slot = 0) }
    }

    @Test
    fun `a timer's id escapes its names, so two schedules never join alike`() {
        val type = AggregateType("shop", "order")
        Timers.idOf(AggregateRef(type, "t", "o-1"), "pay", 2).assert().isEqualTo("shop.order/t/o-1#pay@2")
        Timers.idOf(AggregateRef(type, "t", "a#b"), "c", 1)
            .assert().isNotEqualTo(Timers.idOf(AggregateRef(type, "t", "a"), "b#c", 1))
        Timers.idOf(AggregateRef(type, "t/a", "b"), "c", 1)
            .assert().isNotEqualTo(Timers.idOf(AggregateRef(type, "t", "a/b"), "c", 1))
        Timers.idOf(AggregateRef(AggregateType("a.b", "c"), "t", "o"), "k", 1)
            .assert().isNotEqualTo(Timers.idOf(AggregateRef(AggregateType("a", "b.c"), "t", "o"), "k", 1))
        Timers.idOf(AggregateRef(type, "t", "100%@x"), "k@1", 3).assert().isEqualTo("shop.order/t/100%25%40x#k%401@3")
    }

    @Test
    fun `a change keeps its timer`() {
        TimerChange(timer(), timer().copyWith(status = TimerStatus.FIRED)).next.status
            .assert().isEqualTo(TimerStatus.FIRED)
        assertThrownBy<IllegalArgumentException> { TimerChange(timer(), timer().copyWith(id = "other")) }
        assertThrownBy<IllegalArgumentException> { TimerChange(timer(), timer().copyWith(key = "other")) }
        assertThrownBy<IllegalArgumentException> {
            TimerChange(timer(), timer().copyWith(owner = ref.type.ref(ref.tenantId, "other")))
        }
    }

    @Test
    fun `a set's timer is due as its rule says, never for a rule the runtime evaluates`() {
        val stream = EventStreamRecord(
            ref,
            2,
            "c",
            "r",
            "shop.order.Place",
            1_000,
            listOf(EventRecord("wow.ScheduleSet", 1, "{}")),
            Scope.NONE,
        )
        Timers.of(stream, ScheduleSet("pay", ScheduleRule.After(5), ref, "shop.order.Cancel", "{}"), 4).dueAt.assert()
            .isEqualTo(1_005)
        Timers.of(stream, ScheduleSet("cron", ScheduleRule.cron("0 0 9 * * *"), ref, "shop.order.Cancel", "{}"), 4)
            .dueAt.assert().isEqualTo(Timers.NOT_EVALUATED)
    }
}

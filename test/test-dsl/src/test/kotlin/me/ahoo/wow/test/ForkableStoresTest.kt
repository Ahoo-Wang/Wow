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

package me.ahoo.wow.test

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerStatus
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** The stores a fork copies are rebuilt from the writes they took, equal to the originals (decision D245). */
class ForkableStoresTest {
    private val owner = AggregateType("bank", "account").ref("tenant", "a")
    private val other = AggregateType("bank", "account").ref("tenant", "b")

    private fun timer(key: String, dueAt: Long, version: Long = 1) = Timer(
        id = "$owner#$key",
        owner = owner,
        key = key,
        version = version,
        partition = 0,
        rule = ScheduleRule.at(Instant.ofEpochMilli(dueAt)),
        dueAt = dueAt,
        target = owner,
        commandType = "bank.account.Close",
        body = "{}",
    )

    @Test
    fun `a copy of the timers holds what they held`() = runBlocking {
        val timers = ForkableTimerStore()
        timers.put(timer("a", 10)).assert().isTrue()
        timers.put(timer("b", 20)).assert().isTrue()
        timers.put(timer("c", 30)).assert().isTrue()
        val loaded = timers.due(setOf(0), 10, 10).single()
        timers.settle(listOf(TimerChange(loaded, loaded.copyWith(status = TimerStatus.PARKED, attempts = 3))))
        timers.cancel(owner, "b", 2).assert().isTrue()
        val copy = ForkableTimerStore(timers.writes)
        copy.parked(owner).map { it.key }.assert().containsExactly("a")
        copy.find(owner, "b")!!.status.assert().isEqualTo(TimerStatus.CANCELLED)
        copy.due(setOf(0), 100, 10).map { it.key }.assert().containsExactly("c")
        // The copy goes on alone.
        copy.cancelAll(owner, 3).assert().isEqualTo(2)
        timers.due(setOf(0), 100, 10).map { it.key }.assert().containsExactly("c")
    }

    @Test
    fun `a copy of the reservations holds what they held, expiring when they did`() = runBlocking {
        val reservations = ForkableReservationStore()
        val scope = ReservationScope.of(owner.type, "tenant", "handle")
        reservations.reserve(scope, "held", owner, "h1", 1.hours).assert().isNotNull()
        reservations.reserve(scope, "brief", owner, "h1", 1.milliseconds).assert().isNotNull()
        reservations.reserve(scope, "abandoned", owner, "h1", 1.hours).assert().isNotNull()
        reservations.abandon(scope, "abandoned", owner, "h1").assert().isTrue()
        reservations.confirm(scope, "confirmed", owner, 1)
        reservations.confirm(scope, "released", owner, 1)
        reservations.release(scope, "released", owner, 2).assert().isTrue()
        val held = reservations.find(scope, "held")!!
        Thread.sleep(2)
        val copy = ForkableReservationStore(reservations.writes)
        copy.find(scope, "held").assert().isEqualTo(held)
        copy.find(scope, "brief").assert().isNull()
        copy.find(scope, "abandoned").assert().isNull()
        copy.find(scope, "confirmed")!!.owner.assert().isEqualTo(owner)
        copy.find(scope, "released").assert().isNull()
        // The copy goes on alone.
        copy.release(scope, "confirmed", owner, 2).assert().isTrue()
        copy.reserve(scope, "confirmed", other, "h2", 1.hours).assert().isNotNull()
        reservations.find(scope, "confirmed")!!.owner.assert().isEqualTo(owner)
    }
}

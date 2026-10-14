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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.Timers
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * The slots of a schedule's rule (decision D198): when a timer is first due, which slot comes after one that fired,
 * and what a slot missed while no node fired it becomes. The runtime's firing loop, the `timers` subscription and the
 * test DSL share it, so a schedule steps through its slots the same way wherever it runs.
 *
 * - A rule due once ([ScheduleRule.At], [ScheduleRule.After]) has one slot.
 * - A [ScheduleRule.Cron] rule's first slot is the first time after the commit that set it; each next slot is the
 *   first time after the slot before it, never after now, so a late firing does not move the period.
 * - A slot found past due when it is claimed, or the next slot found past due when one fired, is caught up as the
 *   rule's [Misfire] says: [Misfire.COALESCE] moves to the latest time due, the ones in between counted in
 *   [Timer.skipped] and never sent; [Misfire.CATCH_UP] sends each in turn. Beyond [MAX_COUNTED] missed slots the
 *   count is estimated from the period.
 */
@InternalWowApi
object ScheduleSlots {
    /** How many missed slots are counted one by one before the rest are estimated. */
    const val MAX_COUNTED: Long = 100_000

    private val expressions = ConcurrentHashMap<String, CronExpression>()
    private val zones = ConcurrentHashMap<String, ZoneId>()

    /** The parsed expression of [rule], cached; an [IllegalArgumentException] when it is not valid. */
    @JvmStatic
    fun expressionOf(rule: ScheduleRule.Cron): CronExpression =
        expressions[rule.expression] ?: CronExpression.parse(rule.expression).also { expressions[rule.expression] = it }

    /** The time zone of [rule]; an [IllegalArgumentException] when it is not one. */
    @JvmStatic
    fun zoneOf(rule: ScheduleRule.Cron): ZoneId = zones[rule.zone] ?: try {
        ZoneId.of(rule.zone).also { zones[rule.zone] = it }
    } catch (invalid: java.time.DateTimeException) {
        throw IllegalArgumentException("\"${rule.zone}\" is not a time zone: ${invalid.message}", invalid)
    }

    /** Fails with an [IllegalArgumentException] unless [rule]'s expression and time zone are valid. */
    @JvmStatic
    fun validate(rule: ScheduleRule.Cron) {
        expressionOf(rule)
        zoneOf(rule)
    }

    /** The first time after [after] (epoch milliseconds) that [rule] names, or null when it names none. */
    @JvmStatic
    fun next(rule: ScheduleRule.Cron, after: Long): Long? =
        expressionOf(rule).next(Instant.ofEpochMilli(after), zoneOf(rule))?.toEpochMilli()

    /** When a schedule set by a commit at [commitTime] is first due: [Timers.NOT_EVALUATED] when never. */
    @JvmStatic
    fun firstDue(rule: ScheduleRule, commitTime: Long): Long = when (rule) {
        is ScheduleRule.Cron -> next(rule, commitTime) ?: Timers.NOT_EVALUATED
        else -> rule.firstDue(commitTime) ?: Timers.NOT_EVALUATED
    }

    /** The timer [set], an event of [stream], makes in [partitions] partitions, its first slot evaluated. */
    @JvmStatic
    fun timerOf(stream: EventStreamRecord, set: ScheduleSet, partitions: Int): Timer {
        val timer = Timers.of(stream, set, partitions)
        if (set.rule !is ScheduleRule.Cron) {
            return timer
        }
        val due = firstDue(set.rule, stream.commitTime)
        return timer.copy(dueAt = due, slotDueAt = due)
    }

    /**
     * [timer], about to be claimed at [now]: a recurring slot past due by more than one time of its rule moves to the
     * latest one due, the others skipped, when its rule coalesces; otherwise [timer] as it is.
     */
    @JvmStatic
    fun caughtUp(timer: Timer, now: Long): Timer {
        val rule = timer.rule as? ScheduleRule.Cron ?: return timer
        if (rule.misfire != Misfire.COALESCE) {
            return timer
        }
        val (due, steps) = latest(rule, timer.slotDueAt, now)
        if (steps == 0L) {
            return timer
        }
        return timer.copy(slot = timer.slot + steps, slotDueAt = due, skipped = timer.skipped + steps)
    }

    /**
     * [timer] after its slot was sent at [now]: its next slot, PENDING with its attempts reset, or null when its rule
     * has no next time (a rule due once, a cron expression that names no later time) and the timer is done. The next
     * slot is the first time after the slot's own due time; past due already, a coalescing rule moves on to the latest
     * time due.
     */
    @JvmStatic
    fun afterFired(timer: Timer, now: Long): Timer? {
        val rule = timer.rule as? ScheduleRule.Cron ?: return null
        val next = next(rule, timer.slotDueAt) ?: return null
        val (due, steps) = if (rule.misfire == Misfire.COALESCE) latest(rule, next, now) else next to 0L
        return timer.copy(
            status = TimerStatus.PENDING,
            slot = timer.slot + 1 + steps,
            slotDueAt = due,
            dueAt = due,
            skipped = steps,
            attempts = 0,
        )
    }

    /**
     * The latest time of [rule] due by [now] from [from], a time of it, and how many times it moved on from [from]:
     * none when the next one is not due yet.
     */
    private fun latest(rule: ScheduleRule.Cron, from: Long, now: Long): Pair<Long, Long> {
        var due = from
        var steps = 0L
        while (true) {
            val next = next(rule, due) ?: return due to steps
            if (next > now) {
                return due to steps
            }
            due = next
            steps++
            if (steps == MAX_COUNTED) {
                return estimated(rule, from, due, steps, now)
            }
        }
    }

    /**
     * Past [MAX_COUNTED] slots counted from [from] to [due]: the rest are estimated by the period they took, and the
     * latest time due is searched from shortly before [now].
     */
    private fun estimated(rule: ScheduleRule.Cron, from: Long, due: Long, steps: Long, now: Long): Pair<Long, Long> {
        val period = maxOf(1L, (due - from) / steps)
        var latest = due
        var counted = steps
        var probe = maxOf(due, now - 2 * period)
        if (probe > due) {
            counted += (probe - due) / period
            latest = probe
        }
        while (true) {
            val next = next(rule, probe) ?: return latest to counted
            if (next > now) {
                return latest to counted
            }
            latest = next
            probe = next
            counted++
        }
    }
}

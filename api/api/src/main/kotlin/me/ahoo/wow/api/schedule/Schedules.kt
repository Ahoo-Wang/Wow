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

import me.ahoo.wow.api.aggregate.AggregateRef
import java.time.Instant
import kotlin.time.Duration

/**
 * When a schedule is due (decision D198): [At] an instant, [After] a delay counted from the commit that sets it, or
 * by a [Cron] expression. A schedule is an aggregate's, under a key: set again, it is replaced; cancelled, it is gone.
 */
sealed interface ScheduleRule {
    /**
     * The first time the schedule is due, in epoch milliseconds, when it is set by a commit at [commitTime]; null when
     * the rule is evaluated by the runtime, as a [Cron] rule is.
     */
    fun firstDue(commitTime: Long): Long?

    /** Due once, at [epochMillis]. */
    data class At(val epochMillis: Long) : ScheduleRule {
        override fun firstDue(commitTime: Long): Long = epochMillis
    }

    /** Due once, [millis] after the commit that sets it. */
    data class After(val millis: Long) : ScheduleRule {
        init {
            require(millis >= 0) { "A schedule is not due before the commit that sets it." }
        }

        override fun firstDue(commitTime: Long): Long =
            if (commitTime > Long.MAX_VALUE - millis) Long.MAX_VALUE else commitTime + millis
    }

    /**
     * Due at each time [expression] names, in the time zone [zone] (UTC by default), six fields as Spring's
     * `CronExpression` reads them; a time missed while no node fired it is handled as [misfire] says (decision D198).
     * The runtime evaluates it: the first slot is the first time after the commit that sets it, and each next one the
     * first time after the slot before it.
     */
    data class Cron(
        val expression: String,
        val zone: String = UTC,
        val misfire: Misfire = Misfire.COALESCE,
    ) : ScheduleRule {
        init {
            require(expression.isNotBlank()) { "A cron rule needs an expression." }
            require(zone.isNotBlank()) { "A cron rule needs a time zone." }
        }

        override fun firstDue(commitTime: Long): Long? = null
    }

    companion object {
        const val UTC: String = "UTC"

        /** Due once at [at]; an instant beyond epoch milliseconds is the first or the last of them. */
        @JvmStatic
        fun at(at: Instant): ScheduleRule = At(
            try {
                at.toEpochMilli()
            } catch (_: ArithmeticException) {
                if (at.isBefore(Instant.EPOCH)) Long.MIN_VALUE else Long.MAX_VALUE
            },
        )

        /** Due once, [after] the commit that sets it. */
        @JvmStatic
        fun after(after: Duration): ScheduleRule = After(after.inWholeMilliseconds)

        /** Due by the cron [expression] in [zone]; see [Cron]. */
        @JvmStatic
        fun cron(expression: String, zone: String = UTC, misfire: Misfire = Misfire.COALESCE): ScheduleRule =
            Cron(expression, zone, misfire)
    }
}

/** What a recurring schedule does with the times it missed (decision D198). */
enum class Misfire {
    /** Fires the latest missed time only, and counts the others as skipped: the default. */
    COALESCE,

    /** Fires each missed time in turn. */
    CATCH_UP,
}

/**
 * The system command `wow.schedule.Set` (decision D198): sets the schedule [key] of the aggregate it is sent to, to
 * send it the command of type [commandType] (`context.aggregate.Command`), [command] its JSON, as [rule] says. Every
 * aggregate decides it without user code, on an aggregate that exists; a saga derives it
 * ([me.ahoo.wow.api.subscribe.SagaContext.schedule]).
 */
data class SetSchedule(val key: String, val rule: ScheduleRule, val commandType: String, val command: String) {
    init {
        require(key.isNotBlank()) { "A schedule needs a key." }
        require(commandType.isNotBlank()) { "A schedule names the type of its command." }
    }
}

/** The system command `wow.schedule.Cancel` (decision D198): cancels the schedule [key] of the aggregate it is sent to. */
data class CancelSchedule(val key: String) {
    init {
        require(key.isNotBlank()) { "A schedule needs a key." }
    }
}

/**
 * The system event `wow.ScheduleSet` (decision D198): the aggregate set its schedule [key], to send [target] the command
 * of type [commandType], [command] its JSON, as [rule] says. Committed with the aggregate's other events; the built-in
 * `timers` subscription derives the timer store from it.
 */
data class ScheduleSet(
    val key: String,
    val rule: ScheduleRule,
    val target: AggregateRef,
    val commandType: String,
    val command: String,
) {
    init {
        require(key.isNotBlank()) { "A schedule needs a key." }
        require(commandType.isNotBlank()) { "A schedule names the type of its command." }
    }
}

/** The system event `wow.ScheduleCancelled` (decision D198): the aggregate cancelled its schedule [key]. */
data class ScheduleCancelled(val key: String) {
    init {
        require(key.isNotBlank()) { "A schedule needs a key." }
    }
}

/**
 * Which time of a schedule a command was sent for (decision D198), as its target reads it from
 * [me.ahoo.wow.api.decide.Command.schedule]: the [timer] (`ref#key@version`) of the schedule [key], its [slot] (from 1,
 * one per time it is due), the time that slot was [due] (epoch milliseconds), and how many slots before it a
 * recurring schedule [skipped] because they were missed ([Misfire.COALESCE]). A command sent for a schedule carries it
 * in its headers ([HEADERS]); its request id is `timer#slot`.
 */
data class ScheduleContext(val timer: String, val key: String, val slot: Long, val due: Long, val skipped: Long = 0) {
    init {
        require(timer.isNotBlank()) { "A scheduled command names its timer." }
        require(key.isNotBlank()) { "A scheduled command names its schedule." }
        require(slot >= 1) { "A schedule's slots count from 1." }
        require(skipped in 0 until slot) { "A slot skips only the slots before it." }
    }

    /** The headers that carry this context; [SKIPPED] only when a slot was skipped. */
    fun headers(): Map<String, String> = buildMap {
        put(TIMER, timer)
        put(KEY, key)
        put(SLOT, slot.toString())
        put(DUE, due.toString())
        if (skipped > 0) {
            put(SKIPPED, skipped.toString())
        }
    }

    companion object {
        const val TIMER: String = "wow.schedule.timer"
        const val KEY: String = "wow.schedule.key"
        const val SLOT: String = "wow.schedule.slot"
        const val DUE: String = "wow.schedule.due"
        const val SKIPPED: String = "wow.schedule.skipped"

        /** The header names a scheduled command carries its context in. */
        @JvmField
        val HEADERS: Set<String> = setOf(TIMER, KEY, SLOT, DUE, SKIPPED)

        /** The context [headers] carry, or null when they are not a scheduled command's. */
        @JvmStatic
        fun of(headers: Map<String, String>): ScheduleContext? {
            val timer = headers[TIMER] ?: return null
            val key = headers[KEY] ?: return null
            val slot = headers[SLOT]?.toLongOrNull() ?: return null
            val due = headers[DUE]?.toLongOrNull() ?: return null
            val skipped = headers[SKIPPED]?.toLongOrNull()?.takeIf { it in 0 until slot } ?: 0
            return ScheduleContext(timer, key, slot, due, skipped)
        }
    }
}

/** The names of the schedule system commands and events (decision D198). */
object Schedules {
    const val SET: String = "wow.schedule.Set"
    const val CANCEL: String = "wow.schedule.Cancel"
    const val SCHEDULE_SET: String = "wow.ScheduleSet"
    const val SCHEDULE_CANCELLED: String = "wow.ScheduleCancelled"

    /** The schedule system commands, by their names. */
    @JvmField
    val COMMANDS: Map<String, Class<*>> = mapOf(SET to SetSchedule::class.java, CANCEL to CancelSchedule::class.java)

    /** The schedule system events, by their names. */
    @JvmField
    val EVENTS: Map<String, Class<*>> =
        mapOf(SCHEDULE_SET to ScheduleSet::class.java, SCHEDULE_CANCELLED to ScheduleCancelled::class.java)

    private val NAMES: Map<Class<*>, String> = (COMMANDS + EVENTS).entries.associate { (name, type) -> type to name }

    /** The system name of [type], a schedule command or event, or null when it is neither. */
    @JvmStatic
    fun nameOf(type: Class<*>): String? = NAMES[type]
}

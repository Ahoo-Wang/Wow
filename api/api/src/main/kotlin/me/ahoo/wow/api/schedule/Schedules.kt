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
import me.ahoo.wow.api.annotation.InternalWowApi
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

    /**
     * Due once, at [epochMillis].
     *
     * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
     */
    class At @InternalWowApi constructor(val epochMillis: Long) : ScheduleRule {
        override fun firstDue(commitTime: Long): Long = epochMillis

        override fun equals(other: Any?): Boolean =
            this === other || other is At && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "At(epochMillis=$epochMillis)"

        private fun valueFields(): List<Any?> = listOf(epochMillis)

        companion object {
            /** An At of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(epochMillis: Long): At = At(epochMillis)
        }
    }

    /**
     * Due once, [millis] after the commit that sets it.
     *
     * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
     */
    class After @InternalWowApi constructor(val millis: Long) : ScheduleRule {
        init {
            require(millis >= 0) { "A schedule is not due before the commit that sets it." }
        }

        override fun firstDue(commitTime: Long): Long =
            if (commitTime > Long.MAX_VALUE - millis) Long.MAX_VALUE else commitTime + millis

        override fun equals(other: Any?): Boolean =
            this === other || other is After && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "After(millis=$millis)"

        private fun valueFields(): List<Any?> = listOf(millis)

        companion object {
            /** An After of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(millis: Long): After = After(millis)
        }
    }

    /**
     * Due at each time [expression] names, in the time zone [zone] (UTC by default), six fields as Spring's
     * `CronExpression` reads them; a time missed while no node fired it is handled as [misfire] says (decision D198).
     * The runtime evaluates it: the first slot is the first time after the commit that sets it, and each next one the
     * first time after the slot before it.
     *
     * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
     */
    class Cron @InternalWowApi constructor(
        val expression: String,
        val zone: String = UTC,
        val misfire: Misfire = Misfire.COALESCE,
    ) : ScheduleRule {
        init {
            require(expression.isNotBlank()) { "A cron rule needs an expression." }
            require(zone.isNotBlank()) { "A cron rule needs a time zone." }
        }

        override fun firstDue(commitTime: Long): Long? = null

        /** This Cron with the given fields replaced: Wow's own copy, left out of the ABI. */
        @InternalWowApi
        fun copyWith(
            expression: String = this.expression,
            zone: String = this.zone,
            misfire: Misfire = this.misfire,
        ): Cron = Cron(expression, zone, misfire)

        override fun equals(other: Any?): Boolean =
            this === other || other is Cron && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Cron(expression=$expression, zone=$zone, misfire=$misfire)"

        private fun valueFields(): List<Any?> = listOf(expression, zone, misfire)

        companion object {
            /** A Cron of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            @JvmOverloads
            fun of(expression: String, zone: String = UTC, misfire: Misfire = Misfire.COALESCE): Cron =
                Cron(expression, zone, misfire)
        }
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
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class SetSchedule @InternalWowApi constructor(
    val key: String,
    val rule: ScheduleRule,
    val commandType: String,
    val command: String,
) {
    init {
        require(key.isNotBlank()) { "A schedule needs a key." }
        require(commandType.isNotBlank()) { "A schedule names the type of its command." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is SetSchedule && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "SetSchedule(key=$key, rule=$rule, commandType=$commandType, command=$command)"

    private fun valueFields(): List<Any?> = listOf(key, rule, commandType, command)

    companion object {
        /** A SetSchedule of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(key: String, rule: ScheduleRule, commandType: String, command: String): SetSchedule =
            SetSchedule(key, rule, commandType, command)
    }
}

/**
 * The system command `wow.schedule.Cancel` (decision D198): cancels the schedule [key] of the aggregate it is sent to.
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class CancelSchedule @InternalWowApi constructor(val key: String) {
    init {
        require(key.isNotBlank()) { "A schedule needs a key." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is CancelSchedule && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "CancelSchedule(key=$key)"

    private fun valueFields(): List<Any?> = listOf(key)

    companion object {
        /** A CancelSchedule of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(key: String): CancelSchedule = CancelSchedule(key)
    }
}

/**
 * The system event `wow.ScheduleSet` (decision D198): the aggregate set its schedule [key], to send [target] the command
 * of type [commandType], [command] its JSON, as [rule] says. Committed with the aggregate's other events; the built-in
 * `timers` subscription derives the timer store from it.
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class ScheduleSet @InternalWowApi constructor(
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

    override fun equals(other: Any?): Boolean =
        this === other || other is ScheduleSet && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ScheduleSet(key=$key, rule=$rule, target=$target, commandType=$commandType, command=$command)"

    private fun valueFields(): List<Any?> = listOf(key, rule, target, commandType, command)

    companion object {
        /** A ScheduleSet of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(
            key: String,
            rule: ScheduleRule,
            target: AggregateRef,
            commandType: String,
            command: String,
        ): ScheduleSet = ScheduleSet(key, rule, target, commandType, command)
    }
}

/**
 * The system event `wow.ScheduleCancelled` (decision D198): the aggregate cancelled its schedule [key].
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class ScheduleCancelled @InternalWowApi constructor(val key: String) {
    init {
        require(key.isNotBlank()) { "A schedule needs a key." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ScheduleCancelled && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ScheduleCancelled(key=$key)"

    private fun valueFields(): List<Any?> = listOf(key)

    companion object {
        /** A ScheduleCancelled of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(key: String): ScheduleCancelled = ScheduleCancelled(key)
    }
}

/**
 * Which time of a schedule a command was sent for (decision D198), as its target reads it from
 * [me.ahoo.wow.api.decide.Command.schedule]: the [timer] (`ref#key@version`) of the schedule [key], its [slot] (from 1,
 * one per time it is due), the time that slot was [due] (epoch milliseconds), and how many slots before it a
 * recurring schedule [skipped] because they were missed ([Misfire.COALESCE]). A command sent for a schedule carries it
 * in its headers ([HEADERS]); its request id is `timer#slot`.
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class ScheduleContext @InternalWowApi constructor(
    val timer: String,
    val key: String,
    val slot: Long,
    val due: Long,
    val skipped: Long = 0,
) {
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

    /** This ScheduleContext with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        timer: String = this.timer,
        key: String = this.key,
        slot: Long = this.slot,
        due: Long = this.due,
        skipped: Long = this.skipped,
    ): ScheduleContext = ScheduleContext(timer, key, slot, due, skipped)

    override fun equals(other: Any?): Boolean =
        this === other || other is ScheduleContext && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ScheduleContext(timer=$timer, key=$key, slot=$slot, due=$due, skipped=$skipped)"

    private fun valueFields(): List<Any?> = listOf(timer, key, slot, due, skipped)

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

        /** A ScheduleContext of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(timer: String, key: String, slot: Long, due: Long, skipped: Long = 0): ScheduleContext =
            ScheduleContext(timer, key, slot, due, skipped)
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

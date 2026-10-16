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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi

/** Who a job of [me.ahoo.wow.api.annotation.OnSchedule] runs for (decision D198). */
enum class JobScope {
    /** One instance for the service, in the default tenant, enabled by the host at startup. */
    SYSTEM,

    /** One instance per tenant, enabled by `wow.schedule.Enable` in that tenant. */
    TENANT,
}

/**
 * An instance's own rule of a job (decision D198): each part that is null follows the job's default (the host's
 * `wow.schedules.<job>.*`, else the annotation), so a tenant may keep the default expression in its own time [zone].
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class JobRule @InternalWowApi constructor(
    val expression: String? = null,
    val zone: String? = null,
    val misfire: Misfire? = null,
) {
    init {
        require(expression == null || expression.isNotBlank()) { "A job rule's expression is not blank." }
        require(zone == null || zone.isNotBlank()) { "A job rule's time zone is not blank." }
    }

    /** The rule this one makes of [default]: each part it does not set is the default's. */
    fun over(default: ScheduleRule.Cron): ScheduleRule.Cron =
        ScheduleRule.Cron(expression ?: default.expression, zone ?: default.zone, misfire ?: default.misfire)

    override fun equals(other: Any?): Boolean =
        this === other || other is JobRule && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "JobRule(expression=$expression, zone=$zone, misfire=$misfire)"

    private fun valueFields(): List<Any?> = listOf(expression, zone, misfire)

    companion object {
        /** A JobRule of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(expression: String? = null, zone: String? = null, misfire: Misfire? = null): JobRule =
            JobRule(expression, zone, misfire)
    }
}

/**
 * The system command `wow.schedule.Enable` (decision D198): creates, or enables again, the instance of the job [job]
 * it is sent to, the aggregate `<context>.schedule` whose id is the job's name in its tenant. [rule] overrides the
 * job's default rule (null keeps the instance's own override, if any); [partition] is the log partition of an
 * instance of a sharded job, which the host enables.
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class EnableJob @InternalWowApi constructor(val job: String, val rule: JobRule? = null, val partition: Int? = null) {
    init {
        require(job.isNotBlank()) { "A job has a name." }
        require(partition == null || partition >= 0) { "A partition is never negative." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is EnableJob && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "EnableJob(job=$job, rule=$rule, partition=$partition)"

    private fun valueFields(): List<Any?> = listOf(job, rule, partition)

    companion object {
        /** An EnableJob of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(job: String, rule: JobRule? = null, partition: Int? = null): EnableJob = EnableJob(job, rule, partition)
    }
}

/**
 * The system command `wow.schedule.Reschedule` (decision D198): overrides the rule of the job instance it is sent to
 * with [rule]; null follows the job's default again.
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class RescheduleJob @InternalWowApi constructor(val rule: JobRule? = null) {
    override fun equals(other: Any?): Boolean =
        this === other || other is RescheduleJob && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "RescheduleJob(rule=$rule)"

    private fun valueFields(): List<Any?> = listOf(rule)

    companion object {
        /** A RescheduleJob of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(rule: JobRule? = null): RescheduleJob = RescheduleJob(rule)
    }
}

/** The system command `wow.schedule.Disable` (decision D198): stops the job instance it is sent to. */
data object DisableJob

/**
 * The system command `wow.schedule.Tick` (decision D198): the schedule of a job instance is due; the slot is the
 * command's [ScheduleContext]. Only a slot newer than the last one ticks.
 */
data object TickJob

/**
 * The system event `wow.JobEnabled`: the instance of [job] runs by [rule], [override] its own part of it.
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class JobEnabled @InternalWowApi constructor(
    val job: String,
    val rule: ScheduleRule.Cron,
    val override: JobRule? = null,
    val partition: Int? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is JobEnabled && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "JobEnabled(job=$job, rule=$rule, override=$override, partition=$partition)"

    private fun valueFields(): List<Any?> = listOf(job, rule, override, partition)

    companion object {
        /** A JobEnabled of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(job: String, rule: ScheduleRule.Cron, override: JobRule? = null, partition: Int? = null): JobEnabled =
            JobEnabled(job, rule, override, partition)
    }
}

/**
 * The system event `wow.JobRescheduled`: the instance runs by [rule] from now on, [override] its own part of it.
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class JobRescheduled @InternalWowApi constructor(val rule: ScheduleRule.Cron, val override: JobRule? = null) {
    override fun equals(other: Any?): Boolean =
        this === other || other is JobRescheduled && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "JobRescheduled(rule=$rule, override=$override)"

    private fun valueFields(): List<Any?> = listOf(rule, override)

    companion object {
        /** A JobRescheduled of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(rule: ScheduleRule.Cron, override: JobRule? = null): JobRescheduled = JobRescheduled(rule, override)
    }
}

/** The system event `wow.JobDisabled`: the instance does not run until it is enabled again. */
data object JobDisabled

/**
 * The system event `wow.Ticked`: the instance ran the [slot] of its [timer], due at [due], after [skipped] slots the
 * schedule coalesced.
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class Ticked @InternalWowApi constructor(val timer: String, val slot: Long, val skipped: Long, val due: Long) {
    override fun equals(other: Any?): Boolean =
        this === other || other is Ticked && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "Ticked(timer=$timer, slot=$slot, skipped=$skipped, due=$due)"

    private fun valueFields(): List<Any?> = listOf(timer, slot, skipped, due)

    companion object {
        /** A Ticked of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(timer: String, slot: Long, skipped: Long, due: Long): Ticked = Ticked(timer, slot, skipped, due)
    }
}

/**
 * What an [me.ahoo.wow.api.annotation.OnSchedule] handler receives (decision D198): the [tenantId] of the instance
 * (the default tenant for a [JobScope.SYSTEM] job), the [job], the [slot] that was due at [due] (epoch milliseconds),
 * how many slots before it were [skipped] (coalesced), and, for a sharded job, the log [partition] whose aggregates
 * this tick is for.
 *
 * Fields may be added (decisions D59, D227): construct one with [of]; the constructor is internal to Wow.
 */
class ScheduleTick @InternalWowApi constructor(
    val tenantId: String,
    val job: String,
    val slot: Long,
    val skipped: Long = 0,
    val due: Long = 0,
    val partition: Int? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is ScheduleTick && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "ScheduleTick(tenantId=$tenantId, job=$job, slot=$slot, skipped=$skipped, due=$due, partition=$partition)"

    private fun valueFields(): List<Any?> = listOf(tenantId, job, slot, skipped, due, partition)

    companion object {
        /** A ScheduleTick of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            tenantId: String,
            job: String,
            slot: Long,
            skipped: Long = 0,
            due: Long = 0,
            partition: Int? = null,
        ): ScheduleTick = ScheduleTick(tenantId, job, slot, skipped, due, partition)
    }
}

/** The names of the job aggregate, its system commands and events (decision D198). */
object ScheduleJobs {
    /** The name of the built-in aggregate that keeps a context's jobs: `<context>.schedule`. */
    const val AGGREGATE: String = "schedule"

    /** The key of an instance's own schedule, which sends it [TICK]. */
    const val TICK_KEY: String = "tick"

    /** The component the commands of a job run as: `system:schedule`. */
    const val COMPONENT: String = "schedule"

    const val ENABLE: String = "wow.schedule.Enable"
    const val RESCHEDULE: String = "wow.schedule.Reschedule"
    const val DISABLE: String = "wow.schedule.Disable"
    const val TICK: String = "wow.schedule.Tick"
    const val JOB_ENABLED: String = "wow.JobEnabled"
    const val JOB_RESCHEDULED: String = "wow.JobRescheduled"
    const val JOB_DISABLED: String = "wow.JobDisabled"
    const val TICKED: String = "wow.Ticked"

    /** The job system commands, by their names. */
    @JvmField
    val COMMANDS: Map<String, Class<*>> = mapOf(
        ENABLE to EnableJob::class.java,
        RESCHEDULE to RescheduleJob::class.java,
        DISABLE to DisableJob::class.java,
        TICK to TickJob::class.java,
    )

    /** The job system events, by their names. */
    @JvmField
    val EVENTS: Map<String, Class<*>> = mapOf(
        JOB_ENABLED to JobEnabled::class.java,
        JOB_RESCHEDULED to JobRescheduled::class.java,
        JOB_DISABLED to JobDisabled::class.java,
        TICKED to Ticked::class.java,
    )

    private val NAMES: Map<Class<*>, String> = (COMMANDS + EVENTS).entries.associate { (name, type) -> type to name }

    /** The system name of [type], a job command or event, or null when it is neither. */
    @JvmStatic
    fun nameOf(type: Class<*>): String? = NAMES[type]

    /** The job aggregate of [context]. */
    @JvmStatic
    fun typeOf(context: String): AggregateType = AggregateType(context, AGGREGATE)

    /** Whether [name] is a job's: letters, digits, `_` and `-`. */
    @JvmStatic
    fun isJobName(name: String): Boolean = JOB_NAME.matches(name)

    private val JOB_NAME = Regex("[A-Za-z0-9_-]+")
}

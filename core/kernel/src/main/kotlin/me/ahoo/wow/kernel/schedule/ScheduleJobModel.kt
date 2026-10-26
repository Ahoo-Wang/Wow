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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.schedule.DisableJob
import me.ahoo.wow.api.schedule.EnableJob
import me.ahoo.wow.api.schedule.JobDisabled
import me.ahoo.wow.api.schedule.JobEnabled
import me.ahoo.wow.api.schedule.JobRescheduled
import me.ahoo.wow.api.schedule.JobRule
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.RescheduleJob
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.TickJob
import me.ahoo.wow.api.schedule.Ticked
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.spi.log.Partitioner

/**
 * A job of an [me.ahoo.wow.api.annotation.OnSchedule] handler (decision D198): its [name], its default [rule] (the
 * annotation's, overridden by the host's `wow.schedules.<name>.*`), its [scope] and whether it is [sharded].
 */
data class ScheduleJob(
    val name: String,
    val rule: ScheduleRule.Cron,
    val scope: JobScope = JobScope.SYSTEM,
    val sharded: Boolean = false,
) {
    init {
        require(ScheduleJobs.isJobName(name)) { "Job \"$name\" is named with letters, digits, '_' and '-' only." }
        require(!sharded || scope == JobScope.SYSTEM) { "Job $name is sharded: only a SYSTEM job is." }
        ScheduleSlots.validate(rule)
    }
}

/**
 * The state of one job instance, the aggregate `<context>.schedule` (decision D198): the [job] it runs, by [rule],
 * whose own part is [override] (null: the job's default); whether it is [enabled]; the log [partition] of a sharded
 * job's instance; and the last tick: the [timer] and [slot] it ran, due at [due], with how many [ticks] and how many
 * slots [skipped] in all.
 */
class JobState {
    var job: String? = null
    var rule: ScheduleRule.Cron? = null
    var override: JobRule? = null
    var enabled: Boolean = false
    var partition: Int? = null
    var timer: String? = null
    var slot: Long = 0
    var due: Long? = null
    var ticks: Long = 0
    var skipped: Long = 0
}

/**
 * The built-in aggregate that keeps a context's jobs (decision D198): `<context>.schedule`, one instance per job in its
 * tenant (the default tenant for a [JobScope.SYSTEM] job), whose id is the job's name, or, for a sharded job, one per
 * log partition ([shardRef]). It belongs to its tenant (no owner), has no lifecycle commands, and decides:
 *
 * - `wow.schedule.Enable(job, rule?)`: creates the instance or enables it again, with the job's default rule under its
 *   own override (the command's, else the one it kept); unchanged, nothing. It schedules its own `wow.schedule.Tick`
 *   under the key `tick` by that rule, so the firing loop of the partition's owner sends it each slot.
 * - `wow.schedule.Reschedule(rule?)`: an enabled instance's own override, null to follow the default again.
 * - `wow.schedule.Disable`: cancels the schedule.
 * - `wow.schedule.Tick`, sent by the schedule: commits `wow.Ticked(slot)` when the slot is newer than the last one it
 *   ran, of the same timer or a later one; an older or repeated slot, or a disabled instance, changes nothing. The tick
 *   also reschedules an instance whose rule is no longer its job's default under its override: one that did not
 *   override a part follows a change of the default from its next tick. A tick of a job the node no longer has
 *   disables the instance.
 *
 * An Enable that names an unknown job, a job of another scope (a SYSTEM job outside the default tenant), another
 * instance's id, or an invalid rule, and a Reschedule of an instance that is not enabled, are rejected with
 * [FrameworkCodes.SCHEDULE_JOB_INVALID].
 */
@InternalWowApi
object ScheduleJobModel {

    /** The model of [context]'s job aggregate, deciding for [jobs]. */
    @JvmStatic
    fun model(context: String, jobs: Collection<ScheduleJob>): AggregateModel<JobState> {
        val byName = jobs.associateBy { it.name }
        require(byName.size == jobs.size) { "Context $context has two jobs of one name." }
        return aggregate<JobState>(context, ScheduleJobs.AGGREGATE) {
            initial { JobState() }
            lifecycle()
            ownership(Ownership.TENANT)
            decide<EnableJob>(Creation.ALLOWED, ScheduleJobs.ENABLE) { state, body -> enable(byName, state, body) }
            decide<RescheduleJob>(name = ScheduleJobs.RESCHEDULE) { state, body -> reschedule(byName, state, body) }
            decide<DisableJob>(name = ScheduleJobs.DISABLE) { state, _ -> disable(state) }
            decide<TickJob>(name = ScheduleJobs.TICK) { state, _ -> tick(byName, state) }
            evolve<JobEnabled>(ScheduleJobs.JOB_ENABLED) { event ->
                job = event.job
                rule = event.rule
                override = event.override
                partition = event.partition
                enabled = true
            }
            evolve<JobRescheduled>(ScheduleJobs.JOB_RESCHEDULED) { event ->
                rule = event.rule
                override = event.override
            }
            evolve<JobDisabled>(ScheduleJobs.JOB_DISABLED) { enabled = false }
            evolve<Ticked>(ScheduleJobs.TICKED) { event ->
                timer = event.timer
                slot = event.slot
                due = event.due
                ticks++
                skipped += event.skipped
            }
        }
    }

    /** The instance of [job] in [tenantId] of [context]: its id is the job's name. */
    @JvmStatic
    fun refOf(context: String, job: String, tenantId: String = Caller.DEFAULT_TENANT): AggregateRef =
        ScheduleJobs.typeOf(context).ref(tenantId, job)

    /**
     * The instance of the sharded [job] of [context] for the log partition [partition] of [partitions]: the first id
     * `<job>~<partition>~<n>` that lies in that partition, so its ticks run on the partition's owner.
     */
    @JvmStatic
    fun shardRef(context: String, job: String, partition: Int, partitions: Int): AggregateRef {
        require(partition in 0 until partitions) { "Partition $partition is not one of $partitions." }
        return generateSequence(0) { it + 1 }
            .map { refOf(context, "$job~$partition~$it") }
            .first { Partitioner.partitionOf(it, partitions) == partition }
    }

    private fun DecideScope.enable(jobs: Map<String, ScheduleJob>, state: JobState, body: EnableJob): Decision {
        val job = jobs[body.job] ?: return invalid("No job ${body.job} in ${command.ref.type.context}.")
        problemOf(job, command.ref, state, body)?.let { return invalid(it) }
        val override = body.rule ?: state.override
        val rule = ruleOf(job, override) ?: return invalid("Job ${job.name} cannot run by $override.")
        if (state.enabled && state.rule == rule && state.override == override) {
            return Decision.NoOp
        }
        schedule(ScheduleJobs.TICK_KEY, rule, TickJob)
        return Decision.events(JobEnabled(job.name, rule, override, body.partition))
    }

    /** Why [body] cannot enable [job] on the instance [ref] in [state]; null when it can. */
    private fun problemOf(job: ScheduleJob, ref: AggregateRef, state: JobState, body: EnableJob): String? = when {
        job.scope == JobScope.SYSTEM && ref.tenantId != Caller.DEFAULT_TENANT ->
            "Job ${job.name} is a SYSTEM job: its instance is in the default tenant, not ${ref.tenantId}."

        state.job != null && state.job != job.name -> "Instance ${ref.id} runs the job ${state.job}, not ${job.name}."
        job.sharded -> when {
            body.partition == null -> "Job ${job.name} is sharded: its instance names its partition."
            !ref.id.startsWith("${job.name}~${body.partition}~") ->
                "Instance ${ref.id} is not the instance of ${job.name} for partition ${body.partition}."

            else -> null
        }

        body.partition != null -> "Job ${job.name} is not sharded: its instance has no partition."
        ref.id != job.name -> "The instance of job ${job.name} has the id ${job.name}, not ${ref.id}."
        else -> null
    }

    private fun DecideScope.reschedule(jobs: Map<String, ScheduleJob>, state: JobState, body: RescheduleJob): Decision {
        if (!state.enabled) {
            return invalid("The instance ${command.ref.id} is not enabled: enable it first.")
        }
        val job = jobs[state.job] ?: return invalid("No job ${state.job} in ${command.ref.type.context}.")
        val rule = ruleOf(job, body.rule) ?: return invalid("Job ${job.name} cannot run by ${body.rule}.")
        if (state.rule == rule && state.override == body.rule) {
            return Decision.NoOp
        }
        schedule(ScheduleJobs.TICK_KEY, rule, TickJob)
        return Decision.events(JobRescheduled(rule, body.rule))
    }

    private fun DecideScope.disable(state: JobState): Decision {
        if (!state.enabled) {
            return Decision.NoOp
        }
        cancelSchedule(ScheduleJobs.TICK_KEY)
        return Decision.events(JobDisabled)
    }

    private fun DecideScope.tick(jobs: Map<String, ScheduleJob>, state: JobState): Decision {
        val slot = command.schedule ?: return invalid("A tick is sent by the instance's schedule.")
        if (!state.enabled || !isNewer(state, slot.timer, slot.slot)) {
            return Decision.NoOp
        }
        val job = jobs[state.job]
        if (job == null) {
            cancelSchedule(ScheduleJobs.TICK_KEY)
            return Decision.events(JobDisabled)
        }
        val ticked = Ticked(slot.timer, slot.slot, slot.skipped, slot.due)
        val rule = ruleOf(job, state.override)
        if (rule == null || rule == state.rule) {
            return Decision.events(ticked)
        }
        schedule(ScheduleJobs.TICK_KEY, rule, TickJob)
        return Decision.events(ticked, JobRescheduled(rule, state.override))
    }

    /**
     * Whether the [slot] of [timer] is newer than the instance's last tick: a later slot of the same timer, or a slot
     * of a timer set at a later version (a reschedule), whose id ends with `@version`.
     */
    private fun isNewer(state: JobState, timer: String, slot: Long): Boolean {
        val last = state.timer ?: return true
        if (last == timer) {
            return slot > state.slot
        }
        return versionOf(timer) > versionOf(last)
    }

    private fun versionOf(timer: String): Long = timer.substringAfterLast('@').toLongOrNull() ?: 0

    /** [job]'s default rule under [override], or null when that is not a valid rule. */
    private fun ruleOf(job: ScheduleJob, override: JobRule?): ScheduleRule.Cron? {
        val rule = override?.over(job.rule) ?: job.rule
        return runCatching { ScheduleSlots.validate(rule) }.map { rule }.getOrNull()
    }

    private fun invalid(message: String): Decision = Decision.Rejected(
        Rejection(FrameworkCodes.SCHEDULE_JOB_INVALID, message)
    )
}

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

package me.ahoo.wow.runtime.schedule

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.schedule.DisableJob
import me.ahoo.wow.api.schedule.EnableJob
import me.ahoo.wow.api.schedule.JobRule
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.kernel.schedule.ScheduleJobModel
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.wait.Settlement

/**
 * The host's override of a job's default rule, `wow.schedules.<job>.*`: each part that is null keeps
 * the annotation's; [enabled] false disables a [JobScope.SYSTEM] job's instances at startup (a [JobScope.TENANT] job's
 * instances are each tenant's to enable or disable).
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class JobOptions @InternalWowApi constructor(
    val expression: String? = null,
    val zone: String? = null,
    val misfire: Misfire? = null,
    val enabled: Boolean = true,
) {
    /** The parts it overrides, as a rule over the annotation's. */
    val rule: JobRule get() = JobRule(expression, zone, misfire)

    override fun equals(other: Any?): Boolean =
        this === other || other is JobOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "JobOptions(expression=$expression, zone=$zone, misfire=$misfire, enabled=$enabled)"

    private fun valueFields(): List<Any?> = listOf(expression, zone, misfire, enabled)
}

/**
 * The jobs of a node: each context's jobs from its `@OnSchedule` handlers, their default rule the
 * annotation's under the host's [JobOptions]; and the startup of the [JobScope.SYSTEM] ones.
 */
internal object ScheduleJobRuntime {
    private val logger = KotlinLogging.logger {}

    /**
     * The jobs of each context of [declared], their defaults overridden by [options] (by job name): a job named twice
     * in a context, or an option of a job no context has, fails.
     */
    @JvmStatic
    fun configured(
        declared: Map<String, List<ScheduleJob>>,
        options: Map<String, JobOptions>,
    ): Map<String, List<ScheduleJob>> {
        val names = declared.values.flatten().mapTo(HashSet()) { it.name }
        options.keys.filterNot { it in names }.forEach {
            throw IllegalArgumentException(
                "wow.schedules.$it configures a job no @OnSchedule handler of the node runs: ${names.sorted()}.",
            )
        }
        return declared.filterValues { it.isNotEmpty() }.mapValues { (context, jobs) ->
            jobs.distinct().also { distinct ->
                distinct.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
                    throw IllegalArgumentException("Context $context has two jobs named $it with different rules.")
                }
            }.map { job ->
                val override = options[job.name] ?: return@map job
                job.copyWith(rule = override.rule.over(job.rule))
            }
        }
    }

    /**
     * The commands that bring the [JobScope.SYSTEM] [jobs] of [context] in line with the host's configuration at
     * startup: `wow.schedule.Enable` of each instance (one per partition of [partitions] for a sharded job) when the
     * job is enabled, which its instance decides as a no-op when nothing changed and reschedules when its default did;
     * `wow.schedule.Disable` otherwise.
     */
    @JvmStatic
    fun startup(
        context: String,
        jobs: List<ScheduleJob>,
        options: Map<String, JobOptions>,
        partitions: Int,
    ): List<Pair<AggregateRef, Any>> = jobs.filter { it.scope == JobScope.SYSTEM }.flatMap { job ->
        val enabled = options[job.name]?.enabled ?: true
        val instances = if (job.sharded) {
            (0 until partitions).map { partition -> ScheduleJobModel.shardRef(context, job.name, partition, partitions) to partition }
        } else {
            listOf(ScheduleJobModel.refOf(context, job.name) to null)
        }
        instances.map { (ref, partition) -> ref to (if (enabled) EnableJob(job.name, null, partition) else DisableJob) }
    }

    /**
     * Sends each of [commands] through [forward] as `system:schedule`, retrying a technical failure by [retry] until it
     * settles: committed, unchanged or rejected (a disabled job without an instance is not found). Ids come from [ids].
     */
    suspend fun send(
        model: AggregateModel<*>,
        commands: List<Pair<AggregateRef, Any>>,
        retry: RetryPolicy,
        ids: () -> String,
        forward: suspend (AggregateModel<*>, Command<*>) -> Settlement,
    ) {
        commands.forEach { (ref, body) ->
            var attempt = 0
            while (true) {
                attempt++
                val id = ids()
                val command = Command(id, id, ref, body, operator = Operator.system(ScheduleJobs.COMPONENT))
                val settled = attempt(model, command, forward)
                if (settled) {
                    break
                }
                delay(retry.backoff(minOf(attempt, retry.maxAttempts)))
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever fails is retried: the job must start.
    private suspend fun attempt(
        model: AggregateModel<*>,
        command: Command<*>,
        forward: suspend (AggregateModel<*>, Command<*>) -> Settlement,
    ): Boolean = try {
        val settlement = forward(model, command)
        if (settlement.failed) {
            logger.warn {
                "Starting the job instance ${command.ref} failed: ${settlement.committed.error?.code} " +
                    "${settlement.committed.error?.message}; retrying."
            }
            false
        } else {
            settlement.rejection?.let {
                logger.info { "The job instance ${command.ref} refused ${command.body}: ${it.code} ${it.message}" }
            }
            true
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logger.warn(failure) { "Starting the job instance ${command.ref} failed; retrying." }
        false
    }
}

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
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.kernel.schedule.ScheduleJobModel
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.SubscriptionId
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.wait.Settlement
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.ledger.ParkedRetrySpec

/**
 * The host's settings of a job, `wow.schedules.<job>.*`, the only place a job is configured: its rule ([cron],
 * [zone], [misfire]), each part null to keep the annotation's; [enabled] false disables a [JobScope.SYSTEM] or a
 * [JobScope.SHARDED] job's instances at startup (a [JobScope.TENANT] job's instances are each tenant's to enable or
 * disable); and the in-process [retry] and the [parked] retries of the subscriptions that run it, null to keep the
 * handler's `@Retry` and the global parked retries.
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal
 * to Wow.
 */
class JobOptions @InternalWowApi constructor(
    val cron: String? = null,
    val zone: String? = null,
    val misfire: Misfire? = null,
    val enabled: Boolean = true,
    val retry: HandlerRetry? = null,
    val parked: ParkedRetrySpec? = null,
) {
    /** The parts it overrides, as a rule over the annotation's. */
    val rule: JobRule get() = JobRule(cron, zone, misfire)

    override fun equals(other: Any?): Boolean =
        this === other || other is JobOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "JobOptions(cron=$cron, zone=$zone, misfire=$misfire, enabled=$enabled, retry=$retry, parked=$parked)"

    private fun valueFields(): List<Any?> = listOf(cron, zone, misfire, enabled, retry, parked)
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
            throw WowConfigurationException(
                "wow.schedules.$it",
                null,
                "The options of the job $it configure a job no @OnSchedule handler of the node runs: ${names.sorted()}.",
                "Name one of the node's jobs.",
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
     * [subscription] with each job's own settings in [options] (`wow.schedules.<job>.retry.*` and `.parked.*`) as
     * those of the subscriptions that run it: [declared] are the jobs of each context, by the name of the subscription
     * that runs each. A job is configured under its name alone: its subscription configured under
     * `wow.subscriptions.[<context>.<name>]` fails.
     */
    @JvmStatic
    fun subscriptionOptions(
        subscription: SubscriptionOptions,
        options: Map<String, JobOptions>,
        declared: Map<String, Map<String, ScheduleJob>>,
    ): SubscriptionOptions {
        val jobs = declared.flatMap { (context, jobs) ->
            jobs.map { (name, job) -> SubscriptionId(context, name) to job.name }
        }.toMap()
        val configured = subscription.starts.keys + subscription.retries.keys + subscription.parked.specs.keys
        configured.firstOrNull { it in jobs }?.let {
            val job = jobs.getValue(it)
            throw WowConfigurationException(
                "wow.subscriptions.[$it]",
                null,
                "The subscription $it runs the job $job, which is configured under wow.schedules.$job alone.",
                "Move its settings to wow.schedules.$job.retry.* and wow.schedules.$job.parked.*.",
            )
        }
        val retries = jobs.mapNotNull { (id, job) -> options[job]?.retry?.let { id to it } }.toMap()
        val specs = jobs.mapNotNull { (id, job) -> options[job]?.parked?.let { id to it } }.toMap()
        if (retries.isEmpty() && specs.isEmpty()) {
            return subscription
        }
        return subscription.copyWith(
            retries = subscription.retries + retries,
            parked = ParkedRetryOptions(
                subscription.parked.spec,
                subscription.parked.specs + specs,
                subscription.parked.scanInterval,
            ),
        )
    }

    /**
     * The commands that bring the [JobScope.SYSTEM] and [JobScope.SHARDED] [jobs] of [context] in line with the host's
     * configuration at startup: `wow.schedule.Enable` of each instance (one per partition of [partitions] for a
     * SHARDED job) when the job is enabled, which its instance decides as a no-op when nothing changed and reschedules
     * when its default did; `wow.schedule.Disable` otherwise.
     */
    @JvmStatic
    fun startup(
        context: String,
        jobs: List<ScheduleJob>,
        options: Map<String, JobOptions>,
        partitions: Int,
    ): List<Pair<AggregateRef, Any>> = jobs.filter { it.scope != JobScope.TENANT }.flatMap { job ->
        val enabled = options[job.name]?.enabled ?: true
        val instances = if (job.scope == JobScope.SHARDED) {
            (0 until partitions).map { ScheduleJobModel.shardRef(context, job.name, it, partitions) to it }
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

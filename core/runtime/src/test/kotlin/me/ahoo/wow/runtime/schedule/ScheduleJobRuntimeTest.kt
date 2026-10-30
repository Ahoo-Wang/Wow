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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.schedule.DisableJob
import me.ahoo.wow.api.schedule.EnableJob
import me.ahoo.wow.api.schedule.JobRule
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.kernel.schedule.ScheduleJobModel
import me.ahoo.wow.runtime.subscription.ParkedRetryOptions
import me.ahoo.wow.runtime.subscription.RetryPolicy
import me.ahoo.wow.runtime.subscription.SubscriptionId
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.runtime.wait.Settlement
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.ledger.ParkedRetrySpec
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/** The startup of the SYSTEM jobs (decision D198): each instance's command sent until it settles. */
class ScheduleJobRuntimeTest {
    private val job = ScheduleJob("nightly", ScheduleRule.Cron("0 0 3 * * *"))
    private val model = ScheduleJobModel.model("shop", listOf(job))
    private val ref = ScheduleJobModel.refOf("shop", "nightly")
    private val retry = RetryPolicy(maxAttempts = 2, initialBackoff = 1.milliseconds, maxBackoff = 2.milliseconds)

    private fun settlement(status: CommandResult.Status, code: String? = null) = Settlement(
        CommandResult(
            commandId = "c",
            requestId = "r",
            correlationId = "c",
            ref = ref,
            hop = Hop.committed("shop"),
            function = FunctionInfo(FunctionKind.COMMAND, "shop", ScheduleJobs.AGGREGATE, ScheduleJobs.ENABLE),
            status = status,
            error = code?.let { ErrorInfo(it, it) },
            signalTime = 0,
        ),
    )

    @Test
    fun `a command is sent again after a technical failure, and stops at a settlement or a rejection`(): Unit =
        runBlocking {
            val sent = ArrayList<Command<*>>()
            val attempts = AtomicInteger()
            val ids = AtomicInteger()
            ScheduleJobRuntime.send(
                model,
                listOf(ref to EnableJob("nightly"), ref to DisableJob),
                retry,
                { "id-${ids.incrementAndGet()}" },
            ) { _, command ->
                sent += command
                when (attempts.incrementAndGet()) {
                    1 -> throw IllegalStateException("down")
                    2 -> settlement(CommandResult.Status.FAILED, "wow.command.unknown")
                    3 -> settlement(CommandResult.Status.SUCCEEDED)
                    else -> settlement(CommandResult.Status.REJECTED, "wow.aggregate.not-found")
                }
            }
            sent.map {
                it.body
            }.assert().containsExactly(EnableJob("nightly"), EnableJob("nightly"), EnableJob("nightly"), DisableJob)
            sent.map { it.id }.distinct().size.assert().isEqualTo(4)
            sent.first().operator.assert().isEqualTo(Operator.system(ScheduleJobs.COMPONENT))
        }

    @Test
    fun `the host's options override the parts of a job's rule they set`() {
        val configured = ScheduleJobRuntime.configured(
            mapOf("shop" to listOf(job)),
            mapOf("nightly" to JobOptions(zone = "Asia/Tokyo", misfire = Misfire.CATCH_UP)),
        )
        configured.getValue("shop").single().rule.assert()
            .isEqualTo(ScheduleRule.Cron("0 0 3 * * *", "Asia/Tokyo", Misfire.CATCH_UP))
        JobOptions(cron = "0 0 4 * * *").rule.assert().isEqualTo(JobRule("0 0 4 * * *"))
        ScheduleJobRuntime.startup("shop", listOf(job), mapOf("nightly" to JobOptions(enabled = false)), 1)
            .assert().containsExactly(ref to DisableJob)
    }

    @Test
    fun `a job's retries should be those of the subscriptions that run it`() {
        val declared = mapOf("shop" to mapOf("Billing.onNightly" to job, "Audit.onNightly" to job))
        val budget = HandlerRetry(maxAttempts = 1)
        val spec = ParkedRetrySpec(maxRetries = 0)
        val subscription = SubscriptionOptions(retries = mapOf(SubscriptionId("shop", "Board.onOrder") to budget))
        val options = ScheduleJobRuntime.subscriptionOptions(
            subscription,
            mapOf("nightly" to JobOptions(retry = budget, parked = spec)),
            declared,
        )
        options.retries.assert().isEqualTo(
            mapOf(
                SubscriptionId("shop", "Board.onOrder") to budget,
                SubscriptionId("shop", "Billing.onNightly") to budget,
                SubscriptionId("shop", "Audit.onNightly") to budget,
            ),
        )
        options.parked.specOf(SubscriptionId("shop", "Billing.onNightly")).assert().isEqualTo(spec)
        options.parked.specOf(SubscriptionId("shop", "Board.onOrder")).assert().isEqualTo(subscription.parked.spec)
        // A job without retries of its own leaves the options as they are.
        ScheduleJobRuntime.subscriptionOptions(subscription, mapOf("nightly" to JobOptions()), declared)
            .assert().isSameAs(subscription)
    }

    @Test
    fun `a job's subscription configured under its subscription's name should fail`() {
        val declared = mapOf("shop" to mapOf("Billing.onNightly" to job))
        val id = SubscriptionId("shop", "Billing.onNightly")
        listOf(
            SubscriptionOptions(starts = mapOf(id to SubscriptionStart.Beginning)),
            SubscriptionOptions(retries = mapOf(id to HandlerRetry(maxAttempts = 1))),
            SubscriptionOptions(parked = ParkedRetryOptions(specs = mapOf(id to ParkedRetrySpec(maxRetries = 0)))),
        ).forEach { subscription ->
            assertThrownBy<WowConfigurationException> {
                ScheduleJobRuntime.subscriptionOptions(subscription, emptyMap(), declared)
            }.isInstanceOfSatisfying(WowConfigurationException::class.java) {
                it.key.assert().isEqualTo("wow.subscriptions.[shop.Billing.onNightly]")
            }
        }
    }
}

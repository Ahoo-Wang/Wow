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

import me.ahoo.wow.api.client.CommandRequest
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.schedule.JobRule
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleTick
import me.ahoo.wow.api.schedule.Ticked
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.contextOf
import me.ahoo.wow.test.scheduleSpec
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The test DSL of `@OnSchedule` handlers (decision D198): enabling job instances and moving time across slots. */
class ScheduleSpecDslTest {
    data class IssueInvoices(val tenantId: String, val slot: Long, val skipped: Long = 0)
    data class Sweep(val partition: Int?, val slot: Long)

    private val account = AggregateType("billing", "account")
    private val invoice = ScheduleJob("invoice", ScheduleRule.Cron("0 0 0 1 * *"), JobScope.TENANT)
    private val sweep = ScheduleJob("sweep", ScheduleRule.Cron("0 0 * * * *"), sharded = true)
    private val nightly = ScheduleJob("nightly", ScheduleRule.Cron("0 0 3 * * *"))

    private val handlers = listOf(
        ProcessorBinding.schedule("BillingSaga", "onInvoice", invoice) { tick, context ->
            listOf(
                CommandRequest.to(
                    account.ref(tick.tenantId, "all"),
                    IssueInvoices(tick.tenantId, tick.slot, tick.skipped),
                )
            )
        },
        ProcessorBinding.schedule("BillingSaga", "onSweep", sweep) { tick, context ->
            listOf(CommandRequest.to(account.ref(tick.tenantId, "sweep"), Sweep(tick.partition, tick.slot)))
        },
        ProcessorBinding.schedule("BillingSaga", "onNightly", nightly) { _, _ -> emptyList() },
    )

    @Test
    fun `each tenant's instance runs in its own time zone`() {
        scheduleSpec(handlers, context = "billing") {
            enable("invoice", tenantId = "acme", rule = JobRule(zone = "Asia/Shanghai"))
            enable("invoice", tenantId = "globex")
            // February 1st in Shanghai begins at 16:00 UTC on January 31st, eight hours before it does in UTC.
            advanceTime(30.days + 16.hours) {
                expectHandledBy("onInvoice")
                expectCommands(IssueInvoices("acme", 1))
            }
            advanceTime(8.hours) { expectCommands(IssueInvoices("globex", 1)) }
            ticks.map { it.tenantId }.assert().containsExactly("acme", "globex")
            ticks.first().due.assert().isEqualTo(Instant.parse("2026-01-31T16:00:00Z").toEpochMilli())
            now.assert().isEqualTo(Instant.parse("2026-02-01T00:00:00Z"))
        }
    }

    @Test
    fun `slots missed in one step are coalesced into the latest`() {
        scheduleSpec(handlers) {
            enable("invoice", tenantId = "acme")
            advanceTime(31.days) { expectCommands(IssueInvoices("acme", 1)) }
            advanceTime(120.days) { expectCommands(IssueInvoices("acme", 5, skipped = 3)) }
            instanceOf("invoice", "acme")?.ticks.assert().isEqualTo(2L)
            instanceOf("invoice", "other").assert().isNull()
            assertThrownBy<AssertionError> { advanceTime(1.days) { expectNoCommand() } }
                .hasMessageContaining("No slot")
            advanceTime(1.days)
        }
    }

    @Test
    fun `a sharded job ticks each partition's instance once per slot`() {
        scheduleSpec(handlers, partitions = 2) {
            enable("sweep", partition = 0)
            enable("sweep", partition = 1)
            advanceTime(1.hours) {
                expectHandledBy("onSweep")
                expectCommands(Sweep(0, 1), Sweep(1, 1))
            }
            ticks.map { it.partition }.assert().containsExactly(0, 1)
            ticks.map { it.tenantId }.distinct().assert().containsExactly(Caller.DEFAULT_TENANT)
        }
    }

    @Test
    fun `a rescheduled instance runs by its new rule, a disabled one not at all`() {
        scheduleSpec(handlers) {
            enable("invoice", tenantId = "acme")
            reschedule("invoice", tenantId = "acme", rule = JobRule("0 0 0 * * *"))
            advanceTime(1.days) { expectCommands(IssueInvoices("acme", 1)) }
            disable("invoice", tenantId = "acme")
            advanceTime(40.days)
            ticks.size.assert().isEqualTo(1)
            assertThrownBy<AssertionError> { reschedule("invoice", tenantId = "acme") }
                .hasMessageContaining("refused")
            // A job instance created after the start runs from the specification's current time.
            enable("nightly")
            advanceTime(1.days) { expectHandledBy("onNightly") }
        }
    }

    @Test
    fun `a tick handed over directly reaches its job's handlers`() {
        scheduleSpec(handlers) {
            whenTick(ScheduleTick("acme", "invoice", slot = 3, skipped = 1, due = 42)) {
                expectCommands(IssueInvoices("acme", 3, 1))
                expectDeterministic()
            }
            whenTick(ScheduleTick(Caller.DEFAULT_TENANT, "sweep", slot = 1, partition = 0)) {
                expectCommands(Sweep(0, 1))
            }
            ticks.size.assert().isEqualTo(2)
            assertThrownBy<IllegalArgumentException> { whenTick(ScheduleTick("acme", "unknown", 1)) {} }
            assertThrownBy<IllegalArgumentException> { enable("unknown") }
        }
    }

    @Test
    fun `a schedule specification needs job handlers`() {
        val saga = ProcessorBinding.saga<Any>("Other", "onEvent", null) { _, _ -> emptyList() }
        assertThrownBy<IllegalArgumentException> { scheduleSpec(listOf(saga)) {} }
    }

    @Test
    fun `a job handler is a saga handler of the job aggregate that takes only its job's ticks`(): Unit = runBlocking {
        val handler = handlers.first()
        handler.job.assert().isEqualTo(invoice)
        handler.kind.assert().isEqualTo(FunctionKind.SAGA)
        handler.stateType.assert().isEqualTo(JobState::class.java)
        handler.nullableState.assert().isFalse()
        val ref = ScheduleJobModel.refOf("billing", "nightly")
        val other = stateEvent(ref, JobState().apply { job = "nightly" }, Ticked("t", 1, 0, 0))
        handler.invoke(other, contextOf(FunctionKind.SAGA, handler.name, other)).assert().isEmpty()
        val own = stateEvent(ref, JobState().apply { job = "invoice" }, Ticked("t", 1, 0, 0))
        handler.invoke(own, contextOf(FunctionKind.SAGA, handler.name, own)).assert().hasSize(1)
    }

    @Test
    fun `a schedule specification's time stands still until it is moved`() {
        scheduleSpec(handlers) {
            now.assert().isEqualTo(Instant.parse("2026-01-01T00:00:00Z"))
            advanceTime(5.minutes)
            now.assert().isEqualTo(Instant.parse("2026-01-01T00:05:00Z"))
        }
    }
}

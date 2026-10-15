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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.schedule.DisableJob
import me.ahoo.wow.api.schedule.EnableJob
import me.ahoo.wow.api.schedule.JobDisabled
import me.ahoo.wow.api.schedule.JobEnabled
import me.ahoo.wow.api.schedule.JobRescheduled
import me.ahoo.wow.api.schedule.JobRule
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.schedule.RescheduleJob
import me.ahoo.wow.api.schedule.ScheduleCancelled
import me.ahoo.wow.api.schedule.ScheduleContext
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.api.schedule.TickJob
import me.ahoo.wow.api.schedule.Ticked
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** The built-in job aggregate (decision D198): enabling, rescheduling, disabling and ticking a job's instance. */
class ScheduleJobModelTest {
    private val monthly = ScheduleRule.Cron("0 0 0 1 * *")
    private val nightly = ScheduleJob("nightly", ScheduleRule.Cron("0 0 3 * * *"))
    private val invoice = ScheduleJob("invoice", monthly, JobScope.TENANT)
    private val sweep = ScheduleJob("sweep", ScheduleRule.Cron("0 */5 * * * *"), sharded = true)
    private val model = ScheduleJobModel.model("billing", listOf(nightly, invoice, sweep))

    @Test
    fun `a job is named plainly, and only a SYSTEM job is sharded`() {
        assertThrownBy<IllegalArgumentException> { ScheduleJob("night ly", monthly) }
        assertThrownBy<IllegalArgumentException> { ScheduleJob("x", monthly, JobScope.TENANT, sharded = true) }
        assertThrownBy<IllegalArgumentException> { ScheduleJob("x", ScheduleRule.Cron("nonsense")) }
        assertThrownBy<IllegalArgumentException> { ScheduleJobModel.model("billing", listOf(nightly, nightly.copy())) }
        model.type.assert().isEqualTo(ScheduleJobs.typeOf("billing"))
    }

    @Test
    fun `enabling a SYSTEM job creates its instance and schedules its tick`() {
        aggregateSpec(model, id = "nightly", tenantId = Caller.DEFAULT_TENANT) {
            whenCommand(EnableJob("nightly")) {
                expectEvents(JobEnabled("nightly", nightly.rule))
                expectScheduled(ScheduleJobs.TICK_KEY, TickJob, nightly.rule)
                expectState {
                    job.assert().isEqualTo("nightly")
                    enabled.assert().isTrue()
                    override.assert().isNull()
                }
            }
            whenCommand(EnableJob("nightly")) { expectUnchanged() }
        }
    }

    @Test
    fun `a job instance ticks slot after slot, and a monthly one coalesces the months it missed`() {
        aggregateSpec(model, id = "invoice", tenantId = "acme") {
            whenCommand(EnableJob("invoice")) { expectEventTypes(JobEnabled::class) }
            // 2026-01-01: the first slot is February's.
            advanceTime(31.days) {
                expectEvent(Ticked::class) {
                    slot.assert().isEqualTo(1L)
                    skipped.assert().isEqualTo(0L)
                    due.assert().isEqualTo(Instant.parse("2026-02-01T00:00:00Z").toEpochMilli())
                }
            }
            advanceTime(28.days) { expectEvent(Ticked::class) { slot.assert().isEqualTo(2L) } }
            // April and May are missed: June runs, the two before it skipped.
            advanceTime(92.days) {
                expectEvent(Ticked::class) {
                    slot.assert().isEqualTo(5L)
                    skipped.assert().isEqualTo(2L)
                    due.assert().isEqualTo(Instant.parse("2026-06-01T00:00:00Z").toEpochMilli())
                }
                expectState {
                    ticks.assert().isEqualTo(3L)
                    skipped.assert().isEqualTo(2L)
                    slot.assert().isEqualTo(5L)
                }
            }
            expectNothingDue(1.days)
        }
    }

    @Test
    fun `a catching-up job runs each slot it missed`() {
        val catchUp = ScheduleJob("invoice", monthly.copy(misfire = Misfire.CATCH_UP), JobScope.TENANT)
        aggregateSpec(ScheduleJobModel.model("billing", listOf(catchUp)), id = "invoice", tenantId = "acme") {
            whenCommand(EnableJob("invoice")) { expectEventTypes(JobEnabled::class) }
            advanceTime(91.days) {
                expectEvent(Ticked::class) {
                    slot.assert().isEqualTo(3L)
                    skipped.assert().isEqualTo(0L)
                }
                expectState { ticks.assert().isEqualTo(3L) }
            }
        }
    }

    @Test
    fun `a tenant overrides the time zone and keeps the default expression`() {
        val shanghai = JobRule(zone = "Asia/Shanghai")
        aggregateSpec(model, id = "invoice", tenantId = "acme") {
            whenCommand(EnableJob("invoice", shanghai)) {
                expectEvents(JobEnabled("invoice", monthly.copy(zone = "Asia/Shanghai"), shanghai))
            }
            // Midnight of February 1st in Shanghai is 16:00 UTC on January 31st.
            advanceTime(30.days + 16.hours) {
                expectEvent(Ticked::class) {
                    due.assert().isEqualTo(Instant.parse("2026-01-31T16:00:00Z").toEpochMilli())
                }
            }
            // Enabled again without a rule, it keeps its own; rescheduled without one, it follows the default.
            whenCommand(EnableJob("invoice")) { expectUnchanged() }
            whenCommand(RescheduleJob()) {
                expectEvents(JobRescheduled(monthly))
                expectScheduled(ScheduleJobs.TICK_KEY, TickJob, monthly)
            }
            whenCommand(RescheduleJob()) { expectUnchanged() }
            whenCommand(RescheduleJob(JobRule("0 0 0 15 * *"))) {
                expectEvents(JobRescheduled(ScheduleRule.Cron("0 0 0 15 * *"), JobRule("0 0 0 15 * *")))
            }
            whenCommand(RescheduleJob(JobRule("nonsense"))) { expectRejected(ScheduleJobModel.JOB_INVALID) }
        }
    }

    @Test
    fun `disabling cancels the tick, and enabling again schedules it anew`() {
        aggregateSpec(model, id = "invoice", tenantId = "acme") {
            whenCommand(EnableJob("invoice")) { expectEventTypes(JobEnabled::class) }
            whenCommand(DisableJob) {
                expectEvents(JobDisabled)
                expectScheduleCancelled(ScheduleJobs.TICK_KEY)
                expectState { enabled.assert().isFalse() }
            }
            whenCommand(DisableJob) { expectUnchanged() }
            expectNothingDue(60.days)
            whenCommand(RescheduleJob()) { expectRejected(ScheduleJobModel.JOB_INVALID) }
            whenCommand(EnableJob("invoice")) { expectEventTypes(JobEnabled::class) }
            advanceTime(31.days) { expectEventTypes(Ticked::class) }
        }
    }

    @Test
    fun `an enable that does not fit the job or its instance is rejected`() {
        aggregateSpec(model, id = "nightly", tenantId = "acme") {
            // A SYSTEM job's instance is in the default tenant.
            whenCommand(EnableJob("nightly")) { expectRejected(ScheduleJobModel.JOB_INVALID) }
        }
        aggregateSpec(model, id = "other", tenantId = Caller.DEFAULT_TENANT) {
            whenCommand(EnableJob("unknown")) { expectRejected(ScheduleJobModel.JOB_INVALID) }
            whenCommand(EnableJob("nightly")) { expectRejected(ScheduleJobModel.JOB_INVALID) }
            whenCommand(EnableJob("nightly", partition = 0)) { expectRejected(ScheduleJobModel.JOB_INVALID) }
            whenCommand(EnableJob("sweep")) { expectRejected(ScheduleJobModel.JOB_INVALID) }
            whenCommand(EnableJob("sweep", partition = 1)) { expectRejected(ScheduleJobModel.JOB_INVALID) }
        }
        aggregateSpec(model, id = "invoice", tenantId = "acme") {
            whenCommand(EnableJob("invoice", JobRule(zone = "Mars/Olympus"))) {
                expectRejected(ScheduleJobModel.JOB_INVALID)
            }
            whenCommand(EnableJob("invoice")) { expectEventTypes(JobEnabled::class) }
            whenCommand(EnableJob("nightly")) { expectRejected(ScheduleJobModel.JOB_INVALID) }
        }
    }

    @Test
    fun `a sharded job has an instance per partition, in that partition`() {
        val refs = (0 until 4).map { ScheduleJobModel.shardRef("billing", "sweep", it, 4) }
        refs.map { Partitioner.partitionOf(it, 4) }.assert().containsExactly(0, 1, 2, 3)
        refs.forEach { it.id.assert().startsWith("sweep~") }
        assertThrownBy<IllegalArgumentException> { ScheduleJobModel.shardRef("billing", "sweep", 4, 4) }
        val ref = refs[1]
        aggregateSpec(model, id = ref.id, tenantId = ref.tenantId) {
            whenCommand(EnableJob("sweep", partition = 1)) {
                expectEvents(JobEnabled("sweep", sweep.rule, partition = 1))
                expectState { partition.assert().isEqualTo(1) }
            }
            // Every five minutes from 00:05: at 05:00 the sixtieth slot runs, the 59 before it coalesced.
            advanceTime(5.hours) {
                expectEvent(Ticked::class) {
                    slot.assert().isEqualTo(60L)
                    skipped.assert().isEqualTo(59L)
                }
            }
        }
        ScheduleJobModel.refOf("billing", "nightly").tenantId.assert().isEqualTo(Caller.DEFAULT_TENANT)
    }

    @Test
    fun `a tick only of the instance's schedule, newer than the last, ticks`() {
        aggregateSpec(model, id = "invoice", tenantId = "acme") {
            whenCommand(EnableJob("invoice")) { expectEventTypes(JobEnabled::class) }
            whenCommand(TickJob) { expectRejected(ScheduleJobModel.JOB_INVALID) }
            val timer = "${ScheduleJobModel.refOf("billing", "invoice", "acme")}#tick@1"
            whenCommand(TickJob, headers = ScheduleContext(timer, "tick", 2, 7).headers()) {
                expectEvents(Ticked(timer, 2, 0, 7))
            }
            // The same slot again, or an older one: nothing.
            whenCommand(TickJob, headers = ScheduleContext(timer, "tick", 2, 7).headers()) { expectUnchanged() }
            whenCommand(TickJob, headers = ScheduleContext(timer, "tick", 1, 5).headers()) { expectUnchanged() }
            // A timer of an earlier version: stale; of a later one, a reschedule's: ticks.
            whenCommand(TickJob, headers = ScheduleContext("$timer-x@0", "tick", 9, 9).headers()) {
                expectUnchanged()
            }
            val later = "${ScheduleJobModel.refOf("billing", "invoice", "acme")}#tick@5"
            whenCommand(TickJob, headers = ScheduleContext(later, "tick", 1, 9, 0).headers()) {
                expectEvents(Ticked(later, 1, 0, 9))
            }
            whenCommand(DisableJob) { expectEventTypes(JobDisabled::class) }
            whenCommand(TickJob, headers = ScheduleContext(later, "tick", 2, 10).headers()) { expectUnchanged() }
        }
    }

    @Test
    fun `an instance that follows the default follows its change from its next tick`(): Unit = runBlocking {
        val codec = EventCodec()
        val kernel = Kernel(InMemoryEventLog(partitions = 1), codec, clock = { 1_000 })
        val ref = ScheduleJobModel.refOf("billing", "invoice", "acme")
        val shanghai = JobRule(zone = "Asia/Shanghai")
        kernel.execute(model, Command("c-1", "r-1", ref, EnableJob("invoice", shanghai)))
        val timer = "$ref#tick@1"
        val tick = Command("c-2", "r-2", ref, TickJob, headers = ScheduleContext(timer, "tick", 1, 900).headers())
        // The node's default is now on the 15th: the instance keeps its own time zone over it.
        val changed = ScheduleJob("invoice", ScheduleRule.Cron("0 0 0 15 * *"), JobScope.TENANT)
        val outcome = kernel.execute(ScheduleJobModel.model("billing", listOf(changed)), tick)
        val committed = outcome as CommandOutcome.Committed
        val rule = ScheduleRule.Cron("0 0 0 15 * *", "Asia/Shanghai")
        committed.stream.events.map { codec.decode(model, it) }.assert().containsExactly(
            Ticked(timer, 1, 0, 900),
            JobRescheduled(rule, shanghai),
            ScheduleSet(ScheduleJobs.TICK_KEY, rule, ref, ScheduleJobs.TICK, "{}"),
        )
        // A node without the job disables the instance on its next tick.
        val next = Command("c-3", "r-3", ref, TickJob, headers = ScheduleContext(timer, "tick", 2, 950).headers())
        val disabled = kernel.execute(ScheduleJobModel.model("billing", emptyList()), next) as CommandOutcome.Committed
        disabled.stream.events.map { codec.decode(model, it) }.assert()
            .containsExactly(JobDisabled, ScheduleCancelled(ScheduleJobs.TICK_KEY))
    }
}

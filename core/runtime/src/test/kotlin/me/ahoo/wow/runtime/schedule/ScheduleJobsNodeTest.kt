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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.schedule.DisableJob
import me.ahoo.wow.api.schedule.EnableJob
import me.ahoo.wow.api.schedule.JobRule
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleTick
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.memory.InMemoryTimerStore
import me.ahoo.wow.kernel.schedule.JobState
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.kernel.schedule.ScheduleJobModel
import me.ahoo.wow.runtime.binding.SubscriptionBindings
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.SubscriptionId
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.timer.SHOP
import me.ahoo.wow.runtime.timer.SHOP_SERVICE
import me.ahoo.wow.runtime.timer.TimedBackends
import me.ahoo.wow.runtime.timer.TimerOptions
import me.ahoo.wow.runtime.timer.orderModel
import me.ahoo.wow.runtime.timer.shopManifest
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.TimerStore
import me.ahoo.wow.spi.timer.Timers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The jobs of `@OnSchedule` handlers on service nodes (decision D198), with in-memory backends: a SYSTEM job enabled
 * at startup ticks its handler slot after slot, a TENANT job runs per tenant once enabled there, in its own time
 * zone, a SHARDED job ticks every partition's instance exactly once per slot, each job's subscription reads its own
 * instances alone, and the host's configuration (`wow.schedules.<job>.*`, and only there) overrides or disables a job
 * and sets the retries of its subscription.
 */
class ScheduleJobsNodeTest {
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val ticks = CopyOnWriteArrayList<ScheduleTick>()

    /** Each tick with the handler that got it. */
    private val handled = CopyOnWriteArrayList<Pair<String, ScheduleTick>>()

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    private fun handler(job: ScheduleJob, function: String = "on${job.name}"): ProcessorBinding<JobState> =
        ProcessorBinding.schedule("Jobs", function, job) { tick, _ ->
            ticks += tick
            handled += function to tick
            emptyList()
        }

    private fun node(
        name: String,
        backends: TimedBackends,
        jobs: List<ScheduleJob>,
        schedules: Map<String, JobOptions> = emptyMap(),
        handlers: List<ProcessorBinding<*>> = jobs.map { handler(it) },
        subscription: SubscriptionOptions = fastNodeOptions().subscription,
    ): ServiceNode = ServiceNode(
        SHOP_SERVICE,
        name,
        "$name.internal:9090",
        listOf(orderModel()),
        shopManifest(),
        backends,
        channel,
        options = fastNodeOptions().copyWith(
            timers = TimerOptions(pollInterval = 20.milliseconds, lookahead = 2.seconds, claimTimeout = 1.seconds),
            schedules = schedules,
            subscription = subscription,
        ),
        subscriptions = SubscriptionBindings(mapOf(SHOP to handlers)),
    ).also {
        nodes += it
        channel.bind(it.address, it.forwardHandler)
    }

    private suspend fun ServiceNode.send(ref: AggregateRef, body: Any): CommandResult {
        val context = context(SHOP)
        val model = context.models.first { it.type == ref.type }
        val id = "${ref.id}-${body.javaClass.simpleName}-${System.nanoTime()}"
        return context.coordinator.execute(
            model,
            Command(id, id, ref, body, operator = Operator("admin", ref.tenantId), wait = WaitPlan.COMMITTED),
        )
    }

    private suspend fun ServiceNode.tickTimer(ref: AggregateRef): Timer? =
        context(SHOP).backends.timers!!.find(Timers.ownerOf(ref), ScheduleJobs.TICK_KEY)

    @Test
    fun `a SYSTEM job is enabled at startup and its handler gets each slot`(): Unit = runBlocking {
        // The outcome of each slot is written late, as under load: the handler gets a tick while its timer is FIRING.
        val backends = TimedBackends { SlowOutcomes(InMemoryTimerStore()) }
        val node = node("node-1", backends, listOf(EVERY_SECOND_SYSTEM)).also { it.start() }
        eventually { ticks.size >= 2 }
        // Slot after slot, each once (the subscription may start after the first one committed).
        ticks.map { it.slot }.zipWithNext().forEach { (before, after) -> after.assert().isGreaterThan(before) }
        ticks.first().tenantId.assert().isEqualTo(Caller.DEFAULT_TENANT)
        ticks.first().job.assert().isEqualTo("heartbeat")
        ticks.first().partition.assert().isNull()
        // A tick reaches its handler once its slot committed, which may be before the firing loop wrote the slot's
        // outcome: the timer is FIRING until then, and PENDING at a later slot after.
        val instance = ScheduleJobModel.refOf(SHOP, "heartbeat")
        val ticked = ticks.last().slot
        eventually { node.tickTimer(instance)?.let { it.status == TimerStatus.PENDING && it.slot > ticked } == true }
        val timer = checkNotNull(node.tickTimer(instance))
        timer.operator.assert().isEqualTo(Operator.system(ScheduleJobs.COMPONENT))
        node.registry.find(ScheduleJobs.typeOf(SHOP)).assert().isNotNull()
    }

    @Test
    fun `a TENANT job runs per tenant once enabled there, each by its own rule`(): Unit = runBlocking {
        val node = node("node-1", TimedBackends(), listOf(EVERY_SECOND_TENANT)).also { it.start() }
        val acme = ScheduleJobModel.refOf(SHOP, "report", "acme")
        val globex = ScheduleJobModel.refOf(SHOP, "report", "globex")
        node.send(acme, EnableJob("report", JobRule(zone = "Asia/Shanghai"))).status.assert()
            .isEqualTo(CommandResult.Status.SUCCEEDED)
        node.send(globex, EnableJob("report")).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        eventually { ticks.map { it.tenantId }.toSet() == setOf("acme", "globex") }
        node.tickTimer(acme)?.rule.assert().isEqualTo(ScheduleRule.Cron("* * * * * *", "Asia/Shanghai"))
        node.tickTimer(acme)?.operator.assert().isEqualTo(Operator.system(ScheduleJobs.COMPONENT, "acme"))
        // An unknown job is refused.
        node.send(ScheduleJobModel.refOf(SHOP, "report", "acme"), EnableJob("missing")).status.assert()
            .isEqualTo(CommandResult.Status.REJECTED)
        node.send(globex, DisableJob).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        eventually { node.tickTimer(globex)?.status == TimerStatus.CANCELLED }
    }

    @Test
    fun `a sharded job ticks every partition's instance, each slot once`(): Unit = runBlocking {
        val backends = TimedBackends(partitions = 4)
        node("node-1", backends, listOf(SHARDED)).also { it.start() }
        eventually { ticks.filter { it.slot >= 2 }.mapNotNull { it.partition }.toSet() == setOf(0, 1, 2, 3) }
        val byPartitionAndSlot = ticks.groupBy { it.partition to it.slot }
        byPartitionAndSlot.values.forEach { it.size.assert().isEqualTo(1) }
        ticks.mapNotNull { it.partition }.toSet().assert().containsExactlyInAnyOrder(0, 1, 2, 3)
    }

    @Test
    fun `each job's subscription should read its own instances alone`(): Unit = runBlocking {
        node("node-1", TimedBackends(partitions = 2), listOf(SHARDED, EVERY_SECOND_SYSTEM)).also { it.start() }
        eventually { handled.map { it.first }.toSet() == setOf("onshard", "onheartbeat") }
        eventually { handled.count { it.first == "onheartbeat" } >= 2 && handled.count { it.first == "onshard" } >= 4 }
        // A shard instance's tick names its partition: none reaches the SYSTEM job's handler, and each reaches the
        // SHARDED job's once.
        handled.filter { it.first == "onheartbeat" }.forEach { it.second.partition.assert().isNull() }
        val sharded = handled.filter { it.first == "onshard" }.map { it.second }
        sharded.forEach { it.partition.assert().isNotNull() }
        sharded.groupBy { it.partition to it.slot }.values.forEach { it.size.assert().isEqualTo(1) }
    }

    @Test
    fun `a job's retries should be configured under its name`(): Unit = runBlocking {
        val backends = TimedBackends()
        val attempts = AtomicInteger()
        val failing = ProcessorBinding.schedule("Jobs", "onheartbeat", EVERY_SECOND_SYSTEM) { _, _ ->
            attempts.incrementAndGet()
            error("The job's downstream is down.")
        }
        // The node's own retry takes three attempts; the job's one.
        val once = mapOf("heartbeat" to JobOptions(retry = HandlerRetry(maxAttempts = 1)))
        node("node-1", backends, listOf(EVERY_SECOND_SYSTEM), once, listOf(failing)).also { it.start() }
        val instance = ScheduleJobModel.refOf(SHOP, "heartbeat")
        eventually { backends.backends(SHOP).ledger.isParked("Jobs.onheartbeat", instance) }
        attempts.get().assert().isEqualTo(1)
    }

    @Test
    fun `a job's subscription configured by its name should fail the node`() {
        val retries = fastNodeOptions().subscription.copyWith(
            retries = mapOf(SubscriptionId(SHOP, "Jobs.onnightly") to HandlerRetry(maxAttempts = 2)),
        )
        assertThrownBy<WowConfigurationException> {
            node("node-1", TimedBackends(), listOf(NIGHTLY), subscription = retries)
        }.isInstanceOfSatisfying(WowConfigurationException::class.java) {
            it.key.assert().isEqualTo("wow.subscriptions.[shop.Jobs.onnightly]")
            it.message.assert().contains("runs the job nightly, which is configured under wow.schedules.nightly")
        }
    }

    @Test
    fun `the host's configuration overrides a job's rule, and a restart reschedules its instance`(): Unit =
        runBlocking {
            val backends = TimedBackends()
            val first = node("node-1", backends, listOf(NIGHTLY)).also { it.start() }
            val ref = ScheduleJobModel.refOf(SHOP, "nightly")
            eventually { first.tickTimer(ref)?.rule == NIGHTLY.rule }
            first.stop()
            val override = mapOf("nightly" to JobOptions(cron = "0 0 4 * * *", zone = "Asia/Tokyo"))
            val second = node("node-2", backends, listOf(NIGHTLY), override).also { it.start() }
            eventually { second.tickTimer(ref)?.rule == ScheduleRule.Cron("0 0 4 * * *", "Asia/Tokyo") }
            second.stop()
            val disabled = mapOf("nightly" to JobOptions(enabled = false))
            val third = node("node-3", backends, listOf(NIGHTLY), disabled).also { it.start() }
            eventually { third.tickTimer(ref)?.status == TimerStatus.CANCELLED }
        }

    @Test
    fun `a configured job no handler runs, or two jobs of one name, fail the node`() {
        assertThrownBy<WowConfigurationException> {
            node("node-1", TimedBackends(), listOf(NIGHTLY), mapOf("other" to JobOptions()))
        }.hasMessageContaining("the job other").isInstanceOfSatisfying(WowConfigurationException::class.java) {
            it.key.assert().isEqualTo("wow.schedules.other")
        }
        assertThrownBy<IllegalArgumentException> {
            ScheduleJobRuntime.configured(
                mapOf(SHOP to listOf(NIGHTLY, NIGHTLY.copyWith(scope = JobScope.SHARDED))),
                emptyMap(),
            )
        }.hasMessageContaining("two jobs named nightly")
        ScheduleJobRuntime.configured(mapOf(SHOP to listOf(NIGHTLY, NIGHTLY), "empty" to emptyList()), emptyMap())
            .assert().isEqualTo(mapOf(SHOP to listOf(NIGHTLY)))
    }

    @Test
    fun `a node without jobs has no job aggregate, and the startup of a TENANT job sends nothing`() {
        val node = node("node-1", TimedBackends(), emptyList())
        node.registry.find(ScheduleJobs.typeOf(SHOP)).assert().isNull()
        ScheduleJobRuntime.startup(SHOP, listOf(EVERY_SECOND_TENANT), emptyMap(), 4).assert().isEmpty()
        ScheduleJobRuntime.startup(SHOP, listOf(SHARDED), emptyMap(), 2).map { it.second }.assert()
            .containsExactly(EnableJob("shard", partition = 0), EnableJob("shard", partition = 1))
        SubscriptionFactory.NONE.jobs(SHOP).assert().isEmpty()
        (SubscriptionFactory.NONE + SubscriptionBindings(mapOf(SHOP to listOf(handler(NIGHTLY))))).jobs(SHOP)
            .assert().isEqualTo(mapOf("Jobs.onnightly" to NIGHTLY))
    }

    /**
     * A timer store that writes the outcome of a sent slot (FIRING to its next slot, PENDING) [OUTCOME_DELAY] late,
     * as a loaded node's firing loop may: the slot's command, and the tick its handler gets, come first.
     */
    private class SlowOutcomes(private val store: TimerStore) : TimerStore by store {
        override suspend fun settle(changes: List<TimerChange>): Int {
            if (changes.any { it.loaded.status == TimerStatus.FIRING }) {
                delay(OUTCOME_DELAY)
            }
            return store.settle(changes)
        }
    }

    private companion object {
        val OUTCOME_DELAY = 300.milliseconds
        val EVERY_SECOND_SYSTEM = ScheduleJob("heartbeat", ScheduleRule.Cron("* * * * * *"))
        val EVERY_SECOND_TENANT = ScheduleJob("report", ScheduleRule.Cron("* * * * * *"), JobScope.TENANT)
        val SHARDED = ScheduleJob("shard", ScheduleRule.Cron("* * * * * *"), JobScope.SHARDED)
        val NIGHTLY = ScheduleJob("nightly", ScheduleRule.Cron("0 0 3 * * *"))
    }
}

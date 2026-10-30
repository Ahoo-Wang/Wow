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

package me.ahoo.wow.test

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.schedule.DisableJob
import me.ahoo.wow.api.schedule.EnableJob
import me.ahoo.wow.api.schedule.JobRule
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.RescheduleJob
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.api.schedule.ScheduleTick
import me.ahoo.wow.api.schedule.Ticked
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.schedule.JobState
import me.ahoo.wow.kernel.schedule.ScheduleJob
import me.ahoo.wow.kernel.schedule.ScheduleJobModel
import org.junit.jupiter.api.DynamicNode
import java.time.Instant
import java.util.stream.Stream
import kotlin.time.Duration

/**
 * Specifies a saga's `@OnSchedule` handlers against the real job aggregate: enable an instance of a
 * job, move the time on, and check the ticks the handlers got and the commands they derived. [handlers] are the
 * saga's, from the function the compiler generated for it (`<processor>Handlers`), which names the bounded context
 * their jobs run in; its log has [partitions] partitions (for a SHARDED job's instances). Their calls are answered
 * ([ScheduleSpec.answerCalls]) and their reads served ([ScheduleSpec.givenQueries]) as a saga specification's are, and
 * a failing handling is retried or parked by its job's `@Retry`.
 *
 * ```kotlin
 * scheduleSpec(billingSagaHandlers(), start = Instant.parse("2026-01-31T12:00:00Z")) {
 *     enable("monthly-invoice", tenantId = "acme", rule = JobRule(zone = "Asia/Shanghai"))
 *     advanceTime(1.days) {
 *         expectCommands(IssueInvoices("acme"))
 *     }
 *     ticks.last().assert().isEqualTo(ScheduleTick("acme", "monthly-invoice", slot = 1, due = ...))
 *     advanceTime(90.days) { ticks.last().skipped.assert().isEqualTo(2) }
 * }
 * ```
 *
 * Time stands still at [start] until [advanceTime] moves it; every instance of the specification shares it. A slot
 * runs as the runtime runs it: its instance commits `wow.Ticked`, and the handlers of its job get the tick. Moving past
 * several slots at once sends only the latest, the others counted as skipped, unless the job's rule catches up.
 * [whenTick] hands a tick over directly, without the job aggregate.
 */
fun scheduleSpec(
    handlers: List<ProcessorBinding<*>>,
    start: Instant = DEFAULT_START,
    partitions: Int = 1,
    block: ScheduleSpec.() -> Unit,
) {
    ScheduleSpec(handlers, start, partitions).block()
}

/**
 * The specification [scheduleSpec] runs, as a tree of tests for a JUnit `@TestFactory`: each [ScheduleSpec.advanceTime]
 * that checks what the handlers did, and each [ScheduleSpec.whenTick], is a test of its own, as [sagaSpecTests] does.
 */
fun scheduleSpecTests(
    handlers: List<ProcessorBinding<*>>,
    start: Instant = DEFAULT_START,
    partitions: Int = 1,
    block: ScheduleSpec.() -> Unit,
): Stream<DynamicNode> {
    val tree = TestTree()
    tree.run { ScheduleSpec(handlers, start, partitions, tree).block() }
    return tree.nodes.stream()
}

/** The job instances of a [scheduleSpec] and the handlers of their jobs. */
@WowTestDsl
class ScheduleSpec internal constructor(
    val handlers: List<ProcessorBinding<*>>,
    private val start: Instant,
    private val partitions: Int,
    /** Where the steps report when the specification is a tree of tests ([scheduleSpecTests]); null otherwise. */
    private val tree: TestTree?,
) {
    constructor(handlers: List<ProcessorBinding<*>>, start: Instant = DEFAULT_START, partitions: Int = 1) :
        this(handlers, start, partitions, null)

    private val jobs: Map<String, ScheduleJob> = handlers.mapNotNull { it.job }.associateBy { it.name }
    private val processor = ProcessorSpec(handlers, FunctionKind.SAGA, tree)

    /** The bounded context the handlers' jobs run in, as the compiler that generated their bindings named it. */
    private val context: String = handlers.mapNotNull { it.context }.distinct().let { contexts ->
        require(contexts.size <= 1) { "The handlers' jobs run in several contexts: $contexts." }
        contexts.singleOrNull() ?: throw IllegalArgumentException(
            "The handlers name no bounded context of their jobs: their module's compiler knew none, so set wow.context " +
                "(or one @BoundedContext) for it.",
        )
    }

    private val model = ScheduleJobModel.model(context, jobs.values)
    private val instances = LinkedHashMap<AggregateRef, AggregateSpec<JobState>>()
    private var elapsed = Duration.ZERO
    private val handed = ArrayList<ScheduleTick>()

    init {
        require(jobs.isNotEmpty()) { "A schedule specification needs the handlers of a saga's @OnSchedule jobs." }
    }

    /** Answers each synchronous call a handler makes with what [answer] returns, as [ProcessorSpec.answerCalls] does. */
    fun answerCalls(answer: (Command<*>) -> CommandResult) = processor.answerCalls(answer)

    /** Serves the handlers' reads from [queries], as [ProcessorSpec.givenQueries] does. */
    fun givenQueries(queries: NodeQueryApi) = processor.givenQueries(queries)

    /** The ticks the handlers got so far, in order. */
    val ticks: List<ScheduleTick> get() = handed.toList()

    /** The time the specification is at. */
    val now: Instant get() = start.plusMillis(elapsed.inWholeMilliseconds)

    /**
     * Enables the instance of [job] in [tenantId] (the default tenant for a SYSTEM or a SHARDED job) by its default
     * rule under [rule], as `wow.schedule.Enable` does; the instance of [partition] for a SHARDED job. Fails when the
     * instance refuses it.
     */
    fun enable(
        job: String,
        tenantId: String = DEFAULT_TENANT,
        rule: JobRule? = null,
        partition: Int? = null,
    ) {
        val instance = instance(job, tenantId, partition)
        check(instance.sendCommand(EnableJob(job, rule, partition), OPERATOR), "enable $job")
    }

    /** Overrides the rule of [job]'s instance in [tenantId] with [rule], null to follow the default again. */
    fun reschedule(
        job: String,
        tenantId: String = DEFAULT_TENANT,
        rule: JobRule? = null,
        partition: Int? = null
    ) {
        check(instance(job, tenantId, partition).sendCommand(RescheduleJob(rule), OPERATOR), "reschedule $job")
    }

    /** Disables [job]'s instance in [tenantId]. */
    fun disable(job: String, tenantId: String = DEFAULT_TENANT, partition: Int? = null) {
        check(instance(job, tenantId, partition).sendCommand(DisableJob, OPERATOR), "disable $job")
    }

    /** The state of [job]'s instance in [tenantId], or null when it was never enabled. */
    fun instanceOf(job: String, tenantId: String = DEFAULT_TENANT, partition: Int? = null): JobState? =
        instances[refOf(job, tenantId, partition)]?.stateOf()

    /**
     * Moves the time on by [duration]: each instance runs the slots then due, and the handlers of its job get each
     * tick, in order. [expect] checks what the handlers did with all of them together, and fails when no slot was
     * due; without it, nothing is checked.
     */
    fun advanceTime(duration: Duration, expect: (ProcessorExpectations.() -> Unit)? = null) {
        if (expect == null) {
            advance(duration, null)
        } else {
            step(tree, "advanceTime($duration)") { advance(duration, expect) }
        }
    }

    private fun advance(duration: Duration, expect: (ProcessorExpectations.() -> Unit)?) {
        require(!duration.isNegative()) { "Time only moves on." }
        elapsed += duration
        val events = instances.values.flatMap { spec ->
            spec.advanceAndSend(duration).mapNotNull { result ->
                (result.getOrThrow() as? CommandOutcome.Committed)?.let { stateEventOf(spec, it) }
            }
        }
        val ticked = events.flatMap { event ->
            val state = event.state
            event.events.filterIsInstance<Ticked>().map {
                ScheduleTick(event.ref.tenantId, state.job!!, it.slot, it.skipped, it.due, state.partition)
            }
        }
        handed += ticked
        if (expect == null) {
            if (events.isNotEmpty()) {
                processor.whenEvents(events) { }
            }
            return
        }
        if (ticked.isEmpty()) {
            throw AssertionError("No slot of an enabled job was due by $now.")
        }
        processor.whenEvents(events, expect)
    }

    /**
     * Hands [tick] to the handlers of its job directly, as the runtime would after its instance committed it, and
     * checks what they did in [expect].
     */
    fun whenTick(tick: ScheduleTick, expect: ProcessorExpectations.() -> Unit = {}) =
        step(tree, "whenTick(${tick.job}, slot ${tick.slot})") { tick(tick, expect) }

    private fun tick(tick: ScheduleTick, expect: ProcessorExpectations.() -> Unit) {
        val job = requireNotNull(jobs[tick.job]) { "No handler runs the job ${tick.job}: the jobs are ${jobs.keys}." }
        val ref = refOf(job.name, tick.tenantId, tick.partition)
        val state = JobState().apply {
            this.job = job.name
            rule = job.rule
            enabled = true
            partition = tick.partition
            slot = tick.slot
            due = tick.due
        }
        val event = stateEvent(
            ref,
            state,
            Ticked("$ref#${ScheduleJobs.TICK_KEY}@1", tick.slot, tick.skipped, tick.due),
            commandType = ScheduleJobs.TICK,
            commitTime = tick.due,
        )
        handed += tick
        processor.whenEvents(listOf(event), expect)
    }

    private fun instance(job: String, tenantId: String, partition: Int?): AggregateSpec<JobState> {
        val ref = refOf(job, tenantId, partition)
        return instances.getOrPut(ref) { AggregateSpec(model, ref, start).also { it.advanceAndSend(elapsed) } }
    }

    private fun refOf(job: String, tenantId: String, partition: Int?): AggregateRef {
        val definition = requireNotNull(jobs[job]) { "No handler runs the job $job: the jobs are ${jobs.keys}." }
        return if (definition.scope == JobScope.SHARDED && partition != null) {
            ScheduleJobModel.shardRef(context, job, partition, partitions)
        } else {
            val tenant = if (definition.scope == JobScope.TENANT) tenantId else Caller.DEFAULT_TENANT
            ScheduleJobModel.refOf(context, job, tenant)
        }
    }

    private fun stateEventOf(spec: AggregateSpec<JobState>, committed: CommandOutcome.Committed): StateEvent<JobState> {
        val stream = committed.stream
        return StateEvent(
            ref = stream.ref,
            version = stream.version,
            commandId = stream.commandId,
            requestId = stream.requestId,
            commandType = stream.commandType,
            commitTime = stream.commitTime,
            events = spec.decoded(stream),
            state = committed.state as JobState,
            scope = stream.scope,
            causation = stream.causation,
            headers = stream.headers,
            operator = stream.operator,
        )
    }

    private fun check(result: Result<CommandOutcome>, action: String) {
        val outcome = result.getOrThrow()
        val rejection = when (outcome) {
            is CommandOutcome.Rejected -> outcome.rejection
            is CommandOutcome.Committed -> outcome.rejection
            else -> null
        }
        if (rejection != null) {
            throw AssertionError("The instance refused to $action: ${rejection.code} ${rejection.message}")
        }
    }

    private companion object {
        /** Who the specification's job commands are sent as: the host's. */
        val OPERATOR = Operator.system(ScheduleJobs.COMPONENT)
    }
}

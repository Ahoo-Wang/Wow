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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.ReservationTenancy
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.ScheduleCancelled
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.CallerAdmission
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.KernelOptions
import me.ahoo.wow.kernel.StateCache
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.schedule.ScheduleSlots
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.Timers
import org.junit.jupiter.api.DynamicNode
import java.time.Instant
import java.util.stream.Stream
import kotlin.time.Duration

/**
 * Specifies an aggregate's behaviour as given events, a command and the expected outcome, against the real kernel
 * and an in-memory log:
 *
 * ```kotlin
 * aggregateSpec(cartModel) {
 *     given(CartCreated("ahoo"))
 *     whenCommand(AddItem("apple", 1)) {
 *         expectEvents(ItemAdded("apple", 1))
 *         expectState { items.assert().containsEntry("apple", 1) }
 *     }
 * }
 * ```
 *
 * The kernel checks that deciding never changes the state: a decide function or after hook that assigns state
 * fails the specification.
 *
 * Time stands still at [start] until [AggregateSpec.advanceTime] moves it: the time a decision reads
 * ([me.ahoo.wow.api.decide.DecideScope.now]), which its commit is recorded at, and the due times of
 * the commands the aggregate schedules, are read from it. Advancing the time sends the aggregate the
 * commands it scheduled for itself that are then due, as the runtime would, each with its timer's request id
 * (`timer#slot`) and its slot ([me.ahoo.wow.api.decide.Command.schedule]). A cron rule steps through its slots as the
 * runtime's firing loop does: advancing past several of them sends only the latest, the others counted as skipped,
 * unless the rule catches up ([me.ahoo.wow.api.schedule.Misfire.CATCH_UP]), which sends each in turn:
 *
 * ```kotlin
 * aggregateSpec(orderModel) {
 *     whenCommand(PlaceOrder(...)) {
 *         expectScheduled("pay-timeout", CancelOrder("unpaid"), ScheduleRule.after(30.minutes))
 *     }
 *     advanceTime(30.minutes) { expectEvents(OrderCancelled("unpaid")) }
 * }
 * ```
 *
 * The ids a decide function takes ([me.ahoo.wow.api.decide.DecideScope.ids]) are [SequentialIds]:
 * `id-1`, `id-2`… and `<aggregate>-1`… for aggregate ids, counted across the specification.
 *
 * Scenarios that share a prefix [AggregateSpec.fork] from it, at the current point, at one marked with
 * [AggregateSpec.ref], or after a command from inside its checks ([Expectations.fork]): each fork runs on its own
 * copy of the aggregate, its log, timers, reservations, ids and time, so its commands do not reach
 * the others, and nothing before the fork runs again: a domain service the decide functions call is not called
 * again for the prefix.
 *
 * The commands are sent by [caller], as the command gateway admits a caller's command: in its scope, by its
 * operator, with its application as the request fact `app_id` ([CallerAdmission]); none (an anonymous caller in no
 * scope) by default. The aggregate is in [tenantId], the caller's tenant by default.
 *
 * The whole specification is one test; [aggregateSpecTests] makes the same specification a tree of tests instead.
 */
fun <S : Any> aggregateSpec(
    model: AggregateModel<S>,
    id: String = "aggregate-1",
    caller: Caller? = null,
    tenantId: String = caller?.tenantId ?: DEFAULT_TENANT,
    start: Instant = DEFAULT_START,
    block: AggregateSpec<S>.() -> Unit,
) {
    AggregateSpec(model, model.type.ref(tenantId, id), start).also { it.caller = caller }.block()
}

/**
 * The specification [aggregateSpec] runs, as a tree of tests for a JUnit `@TestFactory`: each
 * [AggregateSpec.whenCommand], [AggregateSpec.advanceTime] and [AggregateSpec.expectNothingDue] is a test of its own,
 * and each [AggregateSpec.fork] a container of the tests it runs, named by the fork. A failing check fails its own
 * test only: the specification goes on, so the other steps and forks still run and report on their own.
 *
 * ```kotlin
 * @TestFactory
 * fun order() = aggregateSpecTests(orderModel) {
 *     whenCommand(PlaceOrder(...)) {
 *         expectEventTypes(OrderPlaced::class)
 *         fork("paid") { whenCommand(PayOrder(...)) { expectEventTypes(OrderPaid::class) } }
 *         fork("cancelled") { whenCommand(CancelOrder(...)) { expectEventTypes(OrderCancelled::class) } }
 *     }
 * }
 * ```
 *
 * The specification runs as the tree is built, as 9.x's did; the tests report what each step found. An exception
 * outside a step (a [AggregateSpec.given] that cannot commit, say) ends its fork, or the specification, and is
 * reported as a failing test `Error`.
 */
fun <S : Any> aggregateSpecTests(
    model: AggregateModel<S>,
    id: String = "aggregate-1",
    caller: Caller? = null,
    tenantId: String = caller?.tenantId ?: DEFAULT_TENANT,
    start: Instant = DEFAULT_START,
    block: AggregateSpec<S>.() -> Unit,
): Stream<DynamicNode> {
    val tree = TestTree()
    val spec = AggregateSpec(model, model.type.ref(tenantId, id), start, tree).also { it.caller = caller }
    tree.run { spec.block() }
    return tree.nodes.stream()
}

/**
 * The tenant a specification's aggregates are in by default, the default tenant of a caller ([Caller.DEFAULT_TENANT]):
 * that of [aggregateSpec], [ScheduleSpec]'s instances and [ProcessorSpec.givenAggregate].
 */
const val DEFAULT_TENANT: String = Caller.DEFAULT_TENANT

/** Where the time of an [aggregateSpec] starts by default. */
val DEFAULT_START: Instant = Instant.parse("2026-01-01T00:00:00Z")

/** The id of the aggregate [AggregateSpec.givenReservation] gives a key to by default. */
const val ANOTHER_AGGREGATE: String = "another-aggregate"

@WowTestDsl
@Suppress("TooManyFunctions") // The DSL's verbs.
class AggregateSpec<S : Any> private constructor(
    private val model: AggregateModel<S>,
    val ref: AggregateRef,
    private val start: Instant,
    origin: Snapshot?,
    /** Where the steps report when the specification is a tree of tests ([aggregateSpecTests]); null otherwise. */
    private val tree: TestTree?,
) {
    constructor(model: AggregateModel<S>, ref: AggregateRef, start: Instant = DEFAULT_START) :
        this(model, ref, start, null, null)

    /** A specification that reports to [tree] ([aggregateSpecTests]). */
    internal constructor(model: AggregateModel<S>, ref: AggregateRef, start: Instant, tree: TestTree) :
        this(model, ref, start, null, tree)

    private val codec = EventCodec()
    private val log = GivenStateLog(InMemoryEventLog(partitions = 1))
    private var millis = origin?.millis ?: start.toEpochMilli()
    private var given: GivenState? = origin?.given
    private val reservations = ForkableReservationStore(origin?.reservations.orEmpty())
    private val ids = origin?.ids?.copy() ?: SequentialIds()
    private val kernel = Kernel(
        log,
        codec,
        clock = { millis },
        reservations = reservations,
        states = GivenStates(),
        ids = ids,
        options = KernelOptions(verifyDecidePurity = true),
    )
    private val timers = ForkableTimerStore(origin?.timers.orEmpty())
    private var sequence = origin?.sequence ?: 0
    private var forks = 0

    /**
     * The caller that sends the commands that name none: [aggregateSpec]'s, or [asCaller]'s within its block; a fork
     * starts with the caller of the point it forks at.
     */
    internal var caller: Caller? = null

    /** The points marked with [ref]: a fork from one starts on a copy of it. */
    private val marks = LinkedHashMap<String, Snapshot>(origin?.marks.orEmpty())
    private var forkName: String? = null

    init {
        if (origin != null) {
            log.offset = origin.offset
            runBlocking { log.restore(origin.log) }
        }
    }

    /** The time the specification is at. */
    val now: Instant get() = Instant.ofEpochMilli(millis)

    /** The timers the aggregate keeps, in whatever status, by key. */
    fun timer(key: String): Timer? = runBlocking { timers.find(Timers.ownerOf(ref), key) }

    /**
     * Whether the aggregate holds [key] in [namespace], reserved with [tenancy]: reserved by a command
     * that committed and not released since ([me.ahoo.wow.api.decide.DecideScope.release]), as a rename releases its
     * old key.
     */
    fun holds(namespace: String, key: String, tenancy: ReservationTenancy = ReservationTenancy.TENANT): Boolean =
        runBlocking {
            val scope = ReservationScope.of(ref, ReservationKey(namespace, key, tenancy == ReservationTenancy.SYSTEM))
            reservations.find(scope, key)?.owner == ref
        }

    /**
     * Gives [key] in [namespace], reserved with [tenancy], to [owner], another aggregate of this type
     * (`another-aggregate` by default: a key is unique within its aggregate type), as a command of it
     * that committed would: a command reserving it is then refused, and [holds] says the aggregate
     * does not hold it.
     */
    fun givenReservation(
        namespace: String,
        key: String,
        owner: AggregateRef = model.type.ref(ref.tenantId, ANOTHER_AGGREGATE),
        tenancy: ReservationTenancy = ReservationTenancy.TENANT,
    ) {
        require(owner != ref) { "$owner is the aggregate under test: a command of it reserves the key." }
        require(owner.type == ref.type) { "$owner holds keys of its own type only: they do not block ${ref.type}'s." }
        runBlocking {
            val scope = ReservationScope.of(owner, ReservationKey(namespace, key, tenancy == ReservationTenancy.SYSTEM))
            reservations.confirm(scope, key, owner, ReservationStore.UNVERSIONED)
        }
    }

    /**
     * Moves the time on by [duration], then sends the aggregate each command it scheduled for itself that is due by
     * then, earliest first, as the runtime would. Without [expect] it never fails, whether or not a
     * command was due. [expect] checks the outcome of the last one sent, and fails when none was due: use
     * [expectNothingDue] to show that none is, after a schedule was cancelled, say.
     */
    fun advanceTime(duration: Duration, expect: (Expectations<S>.() -> Unit)? = null) = step("advanceTime($duration)") {
        val fired = tick(duration)
        if (expect != null) {
            val last = fired.lastOrNull() ?: throw AssertionError(
                "No command the aggregate scheduled was due by $now: expectNothingDue checks that none is.",
            )
            verify(last, "advanceTime($duration)", "command-$sequence", expect)
        }
    }

    /**
     * Moves the time on by [within], and fails when a command the aggregate scheduled for itself is due by then: a
     * cancelled schedule, say, does not fire. Nothing is sent.
     */
    fun expectNothingDue(within: Duration) = step("expectNothingDue($within)") {
        require(!within.isNegative()) { "Time only moves on." }
        val due = runBlocking { dueTimers(millis + within.inWholeMilliseconds) }
        if (due.isNotEmpty()) {
            throw AssertionError(
                "Expected nothing due by ${Instant.ofEpochMilli(millis + within.inWholeMilliseconds)} but " +
                    due.joinToString { "${it.key} (${it.commandType}, due at ${Instant.ofEpochMilli(it.dueAt)})" } +
                    " is.",
            )
        }
        tick(within)
    }

    /** Moves the time on and sends the due commands, as [advanceTime] does: the outcome of each, in order. */
    private fun tick(duration: Duration): List<Result<CommandOutcome>> {
        require(!duration.isNegative()) { "Time only moves on." }
        millis += duration.inWholeMilliseconds
        val fired = ArrayList<Result<CommandOutcome>>()
        runBlocking {
            while (true) {
                val loaded = dueTimers(millis).firstOrNull() ?: break
                // A recurring slot past due by more than one time of its rule is caught up as its misfire policy says.
                val timer = ScheduleSlots.caughtUp(loaded, millis)
                val type = requireNotNull(model.findCommandType(timer.commandType)) {
                    "${model.type} has no command ${timer.commandType}, which the timer ${timer.id} sends."
                }
                val body = codec.decodeTree(WowJson.mapper.readTree(timer.body), type.type)
                val command = Command(
                    timer.requestId,
                    timer.requestId,
                    ref,
                    body,
                    causation = timer.causation,
                    headers = timer.headers + timer.context.headers(),
                )
                val outcome = execute(command)
                // A recurring timer moves on to its next slot; one due once is done.
                val next = ScheduleSlots.afterFired(timer, millis) ?: timer.copyWith(status = TimerStatus.FIRED, attempts = 1)
                timers.settle(listOf(TimerChange(loaded, next)))
                fired += outcome
            }
        }
        return fired
    }

    private suspend fun dueTimers(by: Long): List<Timer> =
        timers.due(setOf(PARTITION), by, Int.MAX_VALUE).filter { it.target == ref }

    /** Executes [command], recording the schedules it commits; a decide function's exception is the failure. */
    private suspend fun execute(command: Command<*>): Result<CommandOutcome> = try {
        Result.success(kernel.execute(model, command).also { record(it) })
    } catch (
        @Suppress("TooGenericExceptionCaught") // Whatever the decide function throws, expectError checks it.
        failure: Exception,
    ) {
        Result.failure(failure)
    }

    /** Applies the schedules [outcome] committed to the aggregate's timers, as the `timers` subscription would. */
    private suspend fun record(outcome: CommandOutcome) {
        val stream = (outcome as? CommandOutcome.Committed)?.stream ?: return
        val owner = Timers.ownerOf(stream.ref)
        if (stream.events.any { it.name == Lifecycle.AGGREGATE_DELETED }) {
            timers.cancelAll(owner, stream.version.toLong())
        }
        stream.events.filter { it.name in Schedules.EVENTS }.forEach { record ->
            when (val event = codec.decode(model, record)) {
                is ScheduleSet -> timers.put(ScheduleSlots.timerOf(stream, event, PARTITIONS))
                is ScheduleCancelled -> timers.cancel(owner, event.key, stream.version.toLong())
            }
        }
    }

    /**
     * Starts the aggregate in [state] at [version], as if its first [version] streams had left it there (9.x's
     * `givenState`), in [scope]: the commands after it are decided on that state, and their streams continue from
     * [version]; an aggregate whose id is its owner's is given in that owner's scope by default. It is the first step
     * of a specification, before any event or command. The state is copied through
     * its JSON, as a snapshot would be, so changing [state] afterwards changes nothing.
     */
    fun givenState(state: S, version: Int = 1, scope: Scope = unscoped) {
        require(version >= 1) { "A given state is at a version from 1." }
        check(given == null && currentVersion() == 0) { "givenState is the first step of a specification." }
        given = GivenState(codec.stateText(state), version, scope)
        log.offset = version
    }

    /** Starts the aggregate in its initial state changed by [build], at [version], as [givenState] does. */
    fun givenState(version: Int = 1, scope: Scope = unscoped, build: S.() -> Unit) {
        givenState(model.initialState(ref).apply(build), version, scope)
    }

    /**
     * Commits [events] as one stream, as if an earlier command had produced them, leaving the aggregate in [scope]
     * (by default the scope it already has, at first none, or the owner its id is of an aggregate whose id is its
     * owner's). The stream is committed at [commitTime], the specification's time by default, in the name of
     * [operator], none by default: the commit a sourcing function reads ([me.ahoo.wow.api.read.EventStream]).
     */
    fun given(vararg events: Any, scope: Scope? = null, operator: Operator? = null, commitTime: Instant = now) {
        require(events.isNotEmpty()) { "given needs at least one event." }
        commitGiven(events.map { codec.encode(model, it) }, scope, operator, commitTime)
    }

    /**
     * Commits [records] as one stream, as the log holds them, leaving the aggregate in [scope], in the name of
     * [operator] at [commitTime] as [given] does: a history the model's classes can no longer write, such as an
     * event the aggregate retired, whose record the kernel skips, or a record of an older revision, read through
     * its upgrades.
     */
    fun givenRecords(
        vararg records: EventRecord,
        scope: Scope? = null,
        operator: Operator? = null,
        commitTime: Instant = now,
    ) {
        require(records.isNotEmpty()) { "givenRecords needs at least one record." }
        commitGiven(records.toList(), scope, operator, commitTime)
    }

    /**
     * The scope of a history given without one: none, but the owner its id is of an aggregate whose id is its owner's,
     * as the kernel records it.
     */
    private val unscoped: Scope
        get() = if (model.idSource == AggregateIdSource.OWNER) Scope(ownerId = ref.id) else Scope.NONE

    private fun commitGiven(records: List<EventRecord>, scope: Scope?, operator: Operator?, commitTime: Instant) {
        runBlocking {
            val last = log.read(ref).toList().lastOrNull()
            val version = currentVersion() + 1
            val stream = EventStreamRecord(
                ref = ref,
                version = version,
                commandId = "given-$version",
                requestId = "given-$version",
                commandType = "${model.type}.Given",
                commitTime = commitTime.toEpochMilli(),
                events = records,
                scope = scope ?: last?.scope ?: given?.scope ?: unscoped,
                operator = operator,
            )
            check(log.append(stream) is AppendOutcome.Committed) { "Could not commit the given events." }
        }
    }

    /**
     * Runs [block] with [caller] sending the commands that name no caller of their own, as [aggregateSpec]'s
     * `caller` does for the whole specification: a scenario of several callers, the owner then another user, say.
     */
    fun asCaller(caller: Caller, block: AggregateSpec<S>.() -> Unit) {
        val previous = this.caller
        this.caller = caller
        try {
            block()
        } finally {
            this.caller = previous
        }
    }

    /**
     * Sends [command] through the kernel and checks its outcome in [expect]. An exception the decide function throws
     * fails the specification, unless [expect] checks it with [Expectations.expectError].
     *
     * [caller] sends it, the specification's by default ([aggregateSpec], [asCaller]), as the gateway admits a
     * caller's command: in its scope, by its operator, with its application as the request fact `app_id`. [scope]
     * names another scope it acts in, as a caller crossing scopes does (its operator then acting elsewhere);
     * [headers] adds request facts (a scheduled command's [me.ahoo.wow.api.schedule.ScheduleContext.headers], say)
     * and [operator] replaces the caller's. Without a caller, the command is an anonymous one in [scope], none by
     * default.
     *
     * A command whose body names its aggregate ([me.ahoo.wow.api.annotation.AggregateId]) names the specification's,
     * or none: one that names another fails the specification, as admission refuses it.
     */
    @Suppress("LongParameterList")
    fun whenCommand(
        command: Any,
        requestId: String = "request-${++sequence}",
        expectedVersion: Int? = null,
        scope: Scope? = null,
        headers: Map<String, String> = emptyMap(),
        operator: Operator? = null,
        caller: Caller? = null,
        expect: Expectations<S>.() -> Unit,
    ) = step("whenCommand(${command.javaClass.simpleName})") {
        val commandId = "command-${++sequence}"
        val sent = commandOf(commandId, requestId, command, expectedVersion, scope, headers, operator, caller)
        verify(send(sent), "whenCommand(${command.javaClass.simpleName})", commandId, expect)
    }

    /**
     * The command [commandId] of [body], sent by [caller] or the specification's, as [whenCommand] describes: its scope,
     * operator and request facts are the caller's admission ([CallerAdmission]), [scope], [headers] and [operator]
     * overriding them.
     */
    @Suppress("LongParameterList")
    private fun commandOf(
        commandId: String,
        requestId: String,
        body: Any,
        expectedVersion: Int?,
        scope: Scope?,
        headers: Map<String, String>,
        operator: Operator?,
        caller: Caller?,
    ): Command<Any> {
        val inBody = model.aggregateIdOf(body)
        require(inBody == null || inBody == ref.id) {
            "${body.javaClass.simpleName} names the aggregate $inBody in its body, but the specification is of " +
                "${ref.id}: admission refuses it as malformed."
        }
        val sender = caller ?: this.caller
        // A scope other than the sender's own is one it acts in elsewhere.
        val target = scope?.takeIf { sender != null && it != sender.scope }?.let { TargetScope.of(ref.tenantId, it) }
        val origin = sender?.let { CallerAdmission.of(it, target) }
        return Command(
            commandId,
            requestId,
            ref,
            body,
            expectedVersion,
            origin?.scope ?: scope ?: Scope.NONE,
            headers = origin?.facts.orEmpty() + headers,
            operator = operator ?: origin?.operator,
        )
    }

    /**
     * Sends [commands] in order, as [whenCommand] sends one (by [caller], in [scope], with [headers], in the name of
     * [operator]), as the setup of a scenario: each commits, or changes nothing. A command that is rejected, or whose
     * decide function throws, fails the specification, naming the command and what it was.
     */
    fun givenCommands(
        vararg commands: Any,
        scope: Scope? = null,
        headers: Map<String, String> = emptyMap(),
        operator: Operator? = null,
        caller: Caller? = null,
    ) {
        require(commands.isNotEmpty()) { "givenCommands needs at least one command." }
        commands.forEach { body ->
            val commandId = "command-${++sequence}"
            val where = "givenCommands(${body.javaClass.simpleName}) ($commandId)"
            val command = commandOf(commandId, "request-$sequence", body, null, scope, headers, operator, caller)
            val outcome = send(command).getOrElse { throw located(AssertionError("It threw $it.", it), where) }
            // A rejection with events commits them, but the setup expected the command to be taken.
            val taken = outcome is CommandOutcome.Committed && outcome.rejection == null ||
                outcome is CommandOutcome.Unchanged
            if (!taken) {
                throw located(
                    AssertionError("Expected it to commit but it was ${describe(outcome, model, codec)}."),
                    where
                )
            }
        }
    }

    /**
     * Sends [body] as a command of [operator], and gives back its outcome unchecked: for the specifications built on
     * this one, such as [ScheduleSpec].
     */
    internal fun sendCommand(body: Any, operator: Operator? = null): Result<CommandOutcome> =
        send(Command("command-${++sequence}", "request-$sequence", ref, body, operator = operator))

    /** Moves the time on by [duration] as [advanceTime] does, and gives back the outcome of each command sent. */
    internal fun advanceAndSend(duration: Duration): List<Result<CommandOutcome>> = tick(duration)

    /** The aggregate's committed state now. */
    internal fun stateOf(): S = currentState(currentVersion())

    /** The events of [stream], one of this aggregate's, decoded, without those the model retired. */
    internal fun decoded(stream: EventStreamRecord): List<Any> = codec.decodeEvents(model, stream.events)

    private fun send(command: Command<*>): Result<CommandOutcome> = runBlocking { execute(command) }

    /**
     * Checks [result] of the command [commandId], sent by the step [step], with [expect]: a failing check names the
     * step, the command and the fork it is in, in a single run as in a tree of tests.
     */
    private fun verify(
        result: Result<CommandOutcome>,
        step: String,
        commandId: String,
        expect: Expectations<S>.() -> Unit,
    ) {
        val forker: (String, AggregateModel<S>?, AggregateSpec<S>.() -> Unit) -> Unit =
            { name, model, block -> fork(name, null, model ?: this.model, block) }
        val expectations = Expectations(model, codec, result, currentVersion(), ::currentState, ::currentTags, forker)
        try {
            expectations.refuseDroppedEvents()
            expectations.expect()
            expectations.verify()
        } catch (failure: AssertionError) {
            throw located(failure, "$step ($commandId)")
        }
    }

    /** Runs [action], one step of the specification: a test of its own in a [tree], which reports its failure. */
    private fun step(name: String, action: () -> Unit) = step(tree, name, action)

    /**
     * Marks the current point as [name]: a later [fork] from it starts the aggregate where it is now, whatever the
     * specification does in between. The mark is a copy of the aggregate, its log, timers, reservations, ids and
     * time.
     */
    fun ref(name: String) {
        require(name.isNotBlank()) { "A ref needs a name." }
        require(name !in marks) { "The ref $name is marked already." }
        marks[name] = snapshot()
    }

    /**
     * Runs [block] on a copy of the aggregate as it is at the ref [from] ([ref]), or now when [from] is null, so that
     * several scenarios share a prefix: what [block] does stays in the fork, and the specification goes on from where
     * it was. A failure in the fork names it ([name], or [from]).
     *
     * The fork starts on a copy of everything the specification holds: the aggregate's log, its given
     * state, its timers, the reservations, the ids counted so far and the time. Nothing before the fork runs again,
     * so a domain service the decide functions called before it is not called again, however many forks there are.
     * In a tree of tests ([aggregateSpecTests]) the fork is a container named by it.
     *
     * ```kotlin
     * aggregateSpec(orderModel) {
     *     whenCommand(PlaceOrder(...)) { expectEventTypes(OrderPlaced::class) }
     *     fork("paid") { whenCommand(PayOrder(...)) { expectEventTypes(OrderPaid::class) } }
     *     fork("cancelled") { whenCommand(CancelOrder(...)) { expectEventTypes(OrderCancelled::class) } }
     * }
     * ```
     */
    fun fork(name: String = "", from: String? = null, block: AggregateSpec<S>.() -> Unit) =
        fork(name, from, model, block)

    /**
     * Runs [block] on a copy of the aggregate as [fork] does, with [model] deciding from there on:
     * the same aggregate built with other services, as a domain module's generated factory builds it
     * (`orderModel(name, OutOfStockInventory)`), so that one branch of a scenario sees a service answer otherwise.
     * The copy keeps the log, which [model] reads as its own, so [model] is of the same aggregate type; the services
     * the commands before the fork called are not called again.
     */
    fun fork(name: String = "", from: String? = null, model: AggregateModel<S>, block: AggregateSpec<S>.() -> Unit) {
        require(model.type == this.model.type) {
            "A fork's model decides ${this.model.type}, the aggregate of the specification, not ${model.type}."
        }
        val origin = if (from == null) {
            snapshot()
        } else {
            marks[from] ?: throw IllegalArgumentException("No ref $from: the refs are ${marks.keys}.")
        }
        val label = name.ifBlank { from ?: "fork ${++forks}" }
        val path = forkName?.let { "$it > $label" } ?: label
        val fork = { branch: TestTree? ->
            AggregateSpec(model, ref, start, origin, branch).also {
                it.forkName = path
                it.caller = caller
            }
        }
        runFork(tree, label, path, fork, block)
    }

    /** [failure] of the step [where], naming it and the fork it is in; a fork's own failure as it is. */
    private fun located(failure: AssertionError, where: String): AssertionError {
        if (failure is ForkAssertionError) return failure
        val message = "$where: ${failure.message}"
        val fork = forkName ?: return AssertionError(message, failure)
        return ForkAssertionError("In fork \"$fork\", $message", failure)
    }

    /** A copy of everything the specification holds now, which a fork starts from. */
    private fun snapshot(): Snapshot = Snapshot(
        log = runBlocking { log.entries() },
        offset = log.offset,
        given = given,
        millis = millis,
        sequence = sequence,
        ids = ids.copy(),
        timers = timers.writes,
        reservations = reservations.writes,
        marks = LinkedHashMap(marks),
    )

    /** The version of the aggregate's last stream: its given state's when nothing followed it, 0 before anything. */
    private fun currentVersion(): Int =
        runBlocking { log.read(ref).toList().lastOrNull()?.version } ?: given?.version ?: 0

    /** The aggregate's committed state: its initial state before the first commit. */
    private fun currentState(version: Int): S {
        @Suppress("UNCHECKED_CAST")
        return (committedAt(version)?.state ?: model.initialState(ref)) as S
    }

    /** The aggregate's tags at [version]: none before the first commit. */
    private fun currentTags(version: Int): Map<String, List<String>> = committedAt(version)?.tags.orEmpty()

    /** The aggregate's committed state and metadata at [version]; null before the first commit. */
    private fun committedAt(version: Int): CachedState? {
        val base = given
        if (base == null && version == 0) {
            return null
        }
        return runBlocking {
            if (base == null) kernel.stateAt(model, ref, version) else kernel.stateAt(model, ref, version, base.fresh())
        } ?: throw AssertionError("The log does not hold version $version of $ref.")
    }

    /** The state [givenState] started the aggregate in: its JSON, read anew for each use, as states are mutable. */
    private data class GivenState(val text: String, val version: Int, val scope: Scope)

    private fun GivenState.fresh(): CachedState = CachedState(codec.decodeState(model, text), version, scope)

    /**
     * The kernel's states after a [givenState]: the given state evolved by the streams after it, read anew for each
     * command, since the log does not hold the streams before it. Before one, the kernel replays the log.
     */
    private inner class GivenStates : StateCache {
        override fun take(ref: AggregateRef): CachedState? {
            val base = given?.takeIf { ref == this@AggregateSpec.ref } ?: return null
            return runBlocking { kernel.stateAt(model, ref, currentVersion(), base.fresh()) }
        }

        override fun put(ref: AggregateRef, state: CachedState) = Unit
    }

    /**
     * Everything a specification holds at a point, which a fork copies: the entries of its log, as
     * appended, and the given state under them, the writes its timers and reservations took, the ids and the time,
     * the commands sent so far ([sequence]) and the refs marked before it. It is not changed after it is taken.
     */
    private class Snapshot(
        val log: List<LogEntry>,
        val offset: Int,
        val given: GivenState?,
        val millis: Long,
        val sequence: Int,
        val ids: SequentialIds,
        val timers: List<TimerWrite>,
        val reservations: List<ReservationWrite>,
        val marks: Map<String, Snapshot>,
    )
}

/**
 * A failure in a [AggregateSpec.fork], which names the fork and, for a failing check of a command, the step and the
 * command: `In fork "paid", whenCommand(ShipOrder) (command-4): Expected …`.
 */
class ForkAssertionError(message: String, cause: Throwable) : AssertionError(message, cause)

/** The timers of a specification live in one partition. */
private const val PARTITIONS = 1
private const val PARTITION = 0

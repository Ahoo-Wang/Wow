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

package me.ahoo.wow.kernel.test

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CachedState
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemoryReservationStore
import me.ahoo.wow.kernel.InMemoryTimerStore
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.StateCache
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.ScheduleChange
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.Timers
import java.time.Instant
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
 * The kernel checks that deciding never changes the state (F4): a decide function or after hook that assigns state
 * fails the specification.
 *
 * Time stands still at [start] until [AggregateSpec.advanceTime] moves it: the commit times, and the due times of
 * the commands the aggregate schedules (decision D166), are read from it. Advancing the time sends the aggregate the
 * commands it scheduled for itself that are then due, as the runtime would, each with its timer's id as request id:
 *
 * ```kotlin
 * aggregateSpec(orderModel) {
 *     whenCommand(PlaceOrder(...)) { expectScheduled("pay-timeout", CancelOrder("unpaid"), after = 30.minutes) }
 *     advanceTime(30.minutes) { expectEvents(OrderCancelled("unpaid")) }
 * }
 * ```
 *
 * The ids a decide function takes ([me.ahoo.wow.api.decide.DecideScope.ids], decision D171) are [SequentialIds]:
 * `id-1`, `id-2`… and `<aggregate>-1`… for aggregate ids, counted across the specification.
 *
 * Scenarios that share a prefix [AggregateSpec.fork] from it, at the current point or at one marked with
 * [AggregateSpec.ref]: each fork runs on its own copy of the aggregate, so its commands do not reach the others.
 */
fun <S : Any> aggregateSpec(
    model: AggregateModel<S>,
    id: String = "aggregate-1",
    tenantId: String = "tenant",
    start: Instant = DEFAULT_START,
    block: AggregateSpec<S>.() -> Unit,
) {
    AggregateSpec(model, model.type.ref(tenantId, id), start).block()
}

/** Where the time of an [aggregateSpec] starts by default. */
val DEFAULT_START: Instant = Instant.parse("2026-01-01T00:00:00Z")

@Suppress("TooManyFunctions") // The DSL's verbs.
class AggregateSpec<S : Any>(
    private val model: AggregateModel<S>,
    val ref: AggregateRef,
    private val start: Instant = DEFAULT_START,
) {
    private val codec = EventCodec()
    private val log = GivenStateLog(InMemoryEventLog(partitions = 1))
    private var millis = start.toEpochMilli()
    private var given: GivenState? = null
    private val kernel = Kernel(
        log,
        codec,
        clock = { millis },
        verifyDecidePurity = true,
        reservations = InMemoryReservationStore(),
        states = GivenStates(),
        ids = SequentialIds(),
    )
    private val timers = InMemoryTimerStore()
    private var sequence = 0

    /** What the specification did so far, without the checks: a fork replays a prefix of it on its own copy. */
    private val steps = ArrayList<AggregateSpec<S>.() -> Unit>()
    private val marks = LinkedHashMap<String, Mark>()
    private var forkName: String? = null

    /** The time the specification is at. */
    val now: Instant get() = Instant.ofEpochMilli(millis)

    /** The timers the aggregate keeps (decision D166), in whatever status, by key. */
    fun timer(key: String): Timer? = runBlocking { timers.find(Timers.ownerOf(ref), key) }

    /**
     * Moves the time on by [duration], then sends the aggregate each command it scheduled for itself that is due by
     * then, earliest first, as the runtime would (decision D166). Without [expect] it never fails, whether or not a
     * command was due. [expect] checks the outcome of the last one sent, and fails when none was due: use
     * [expectNothingDue] to show that none is, after a schedule was cancelled, say.
     */
    fun advanceTime(duration: Duration, expect: (Expectations<S>.() -> Unit)? = null) {
        val fired = tick(duration)
        if (expect != null) {
            val last = fired.lastOrNull() ?: throw AssertionError(
                "No command the aggregate scheduled was due by $now: expectNothingDue checks that none is.",
            )
            verify(last, expect)
        }
    }

    /**
     * Moves the time on by [within], and fails when a command the aggregate scheduled for itself is due by then: a
     * cancelled schedule, say, does not fire. Nothing is sent.
     */
    fun expectNothingDue(within: Duration) {
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
        steps += { tick(duration) }
        millis += duration.inWholeMilliseconds
        val fired = ArrayList<Result<CommandOutcome>>()
        runBlocking {
            while (true) {
                val timer = dueTimers(millis).firstOrNull() ?: break
                val type = requireNotNull(model.findCommandType(timer.commandType)) {
                    "${model.type} has no command ${timer.commandType}, which the timer ${timer.id} sends."
                }
                val body = codec.decodeTree(WowJson.mapper.readTree(timer.body), type.type)
                val command =
                    Command(timer.id, timer.requestId, ref, body, causation = timer.causation, headers = timer.headers)
                val outcome = execute(command)
                timers.update(timer.copy(status = TimerStatus.FIRED, attempts = 1), TimerStatus.PENDING)
                fired += outcome
            }
        }
        return fired
    }

    private suspend fun dueTimers(by: Long): List<Timer> =
        timers.due(PARTITION, by, Int.MAX_VALUE).filter { it.target == ref }

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
        stream.schedules.forEach { change ->
            when (change) {
                is ScheduleChange.Put -> timers.put(Timers.of(stream, change, PARTITIONS))
                is ScheduleChange.Cancel -> timers.cancel(owner, change.key, stream.version.toLong())
            }
        }
        if (stream.events.any { it.name == Lifecycle.AGGREGATE_DELETED }) {
            timers.cancelAll(owner, stream.version.toLong())
        }
    }

    /**
     * Starts the aggregate in [state] at [version], as if its first [version] streams had left it there (9.x's
     * `givenState`), in [scope]: the commands after it are decided on that state, and their streams continue from
     * [version]. It is the first step of a specification, before any event or command. The state is copied through
     * its JSON, as a snapshot would be, so changing [state] afterwards changes nothing.
     */
    fun givenState(state: S, version: Int = 1, scope: Scope = Scope.NONE) {
        require(version >= 1) { "A given state is at a version from 1." }
        check(given == null && currentVersion() == 0) { "givenState is the first step of a specification." }
        steps += { givenState(state, version, scope) }
        given = GivenState(codec.stateText(state), version, scope)
        log.offset = version
    }

    /** Starts the aggregate in its initial state changed by [build], at [version], as [givenState] does. */
    fun givenState(version: Int = 1, scope: Scope = Scope.NONE, build: S.() -> Unit) {
        givenState(model.initialState(ref).apply(build), version, scope)
    }

    /**
     * Commits [events] as one stream, as if an earlier command had produced them, leaving the aggregate in [scope]
     * (by default the scope it already has).
     */
    fun given(vararg events: Any, scope: Scope? = null) {
        require(events.isNotEmpty()) { "given needs at least one event." }
        steps += { given(*events, scope = scope) }
        runBlocking {
            val last = log.read(ref).toList().lastOrNull()
            val version = currentVersion() + 1
            val stream = EventStreamRecord(
                ref = ref,
                version = version,
                commandId = "given-$version",
                requestId = "given-$version",
                commandType = "${model.type}.Given",
                commitTime = millis,
                events = events.map { codec.encode(model, it) },
                scope = scope ?: last?.scope ?: given?.scope ?: Scope.NONE,
            )
            check(log.append(stream) is AppendOutcome.Committed) { "Could not commit the given events." }
        }
    }

    /**
     * Sends [command], from a caller in [scope], through the kernel and checks its outcome in [expect]. An exception
     * the decide function throws fails the specification, unless [expect] checks it with
     * [Expectations.expectError].
     */
    fun whenCommand(
        command: Any,
        requestId: String = "request-${++sequence}",
        expectedVersion: Int? = null,
        scope: Scope = Scope.NONE,
        expect: Expectations<S>.() -> Unit,
    ) {
        val result = send(Command("command-${++sequence}", requestId, ref, command, expectedVersion, scope))
        verify(result, expect)
    }

    private fun send(command: Command<*>): Result<CommandOutcome> {
        steps += { send(command) }
        return runBlocking { execute(command) }
    }

    private fun verify(result: Result<CommandOutcome>, expect: Expectations<S>.() -> Unit) {
        val expectations = Expectations(model, codec, result, currentVersion(), ::currentState)
        expectations.expect()
        expectations.verify()
    }

    /**
     * Marks the current point as [name]: a later [fork] from it starts the aggregate where it is now, whatever the
     * specification does in between.
     */
    fun ref(name: String) {
        require(name.isNotBlank()) { "A ref needs a name." }
        require(name !in marks) { "The ref $name is marked already." }
        marks[name] = Mark(steps.size, sequence)
    }

    /**
     * Runs [block] on a copy of the aggregate as it is at the ref [from] ([ref]), or now when [from] is null, so that
     * several scenarios share a prefix: what [block] does stays in the fork, and the specification goes on from where
     * it was. A failure in the fork names it ([name], or [from]).
     *
     * ```kotlin
     * aggregateSpec(orderModel) {
     *     whenCommand(PlaceOrder(...)) { expectEventTypes(OrderPlaced::class) }
     *     fork("paid") { whenCommand(PayOrder(...)) { expectEventTypes(OrderPaid::class) } }
     *     fork("cancelled") { whenCommand(CancelOrder(...)) { expectEventTypes(OrderCancelled::class) } }
     * }
     * ```
     */
    @Suppress("ThrowsCount")
    fun fork(name: String = "", from: String? = null, block: AggregateSpec<S>.() -> Unit) {
        val mark = if (from == null) {
            Mark(steps.size, sequence)
        } else {
            marks[from] ?: throw IllegalArgumentException("No ref $from: the refs are ${marks.keys}.")
        }
        val fork = AggregateSpec(model, ref, start)
        val label = name.ifBlank { from ?: "fork ${steps.size}" }
        fork.forkName = forkName?.let { "$it > $label" } ?: label
        steps.subList(0, mark.steps).forEach { step -> fork.step() }
        fork.sequence = mark.sequence
        marks.filterValues { it.steps <= mark.steps }.forEach { (key, value) -> fork.marks[key] = value }
        try {
            fork.block()
        } catch (failure: ForkAssertionError) {
            throw failure
        } catch (failure: AssertionError) {
            throw ForkAssertionError("In fork \"${fork.forkName}\": ${failure.message}", failure)
        }
    }

    /** The version of the aggregate's last stream: its given state's when nothing followed it, 0 before anything. */
    private fun currentVersion(): Int =
        runBlocking { log.read(ref).toList().lastOrNull()?.version } ?: given?.version ?: 0

    /** The aggregate's committed state: its initial state before the first commit. */
    private fun currentState(version: Int): S {
        val base = given
        if (base == null && version == 0) {
            return model.initialState(ref)
        }
        val committed = runBlocking {
            if (base == null) kernel.stateAt(model, ref, version) else kernel.stateAt(model, ref, version, base.fresh())
        } ?: throw AssertionError("The log does not hold version $version of $ref.")
        @Suppress("UNCHECKED_CAST")
        return committed.state as S
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

    private data class Mark(val steps: Int, val sequence: Int)
}

/** A failure in a [AggregateSpec.fork], which names the fork. */
class ForkAssertionError(message: String, cause: Throwable) : AssertionError(message, cause)

/** The timers of a specification live in one partition. */
private const val PARTITIONS = 1
private const val PARTITION = 0

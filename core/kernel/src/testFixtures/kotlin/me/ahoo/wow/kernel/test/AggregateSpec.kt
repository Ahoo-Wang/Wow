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
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.InMemoryEventLog
import me.ahoo.wow.kernel.InMemoryReservationStore
import me.ahoo.wow.kernel.InMemoryTimerStore
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.api.lifecycle.Lifecycle
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

class AggregateSpec<S : Any>(
    private val model: AggregateModel<S>,
    val ref: AggregateRef,
    start: Instant = DEFAULT_START,
) {
    private val codec = EventCodec()
    private val log = InMemoryEventLog(partitions = 1)
    private var millis = start.toEpochMilli()
    private val kernel = Kernel(
        log,
        codec,
        clock = { millis },
        verifyDecidePurity = true,
        reservations = InMemoryReservationStore(),
    )
    private val timers = InMemoryTimerStore()
    private var sequence = 0

    /** The time the specification is at. */
    val now: Instant get() = Instant.ofEpochMilli(millis)

    /** The timers the aggregate keeps (decision D166), in whatever status, by key. */
    fun timer(key: String): Timer? = runBlocking { timers.find(Timers.ownerOf(ref), key) }

    /**
     * Moves the time on by [duration], then sends the aggregate each command it scheduled for itself that is due by
     * then, earliest first, as the runtime would (decision D166). [expect] checks the outcome of the last one sent,
     * and fails when none was due.
     */
    fun advanceTime(duration: Duration, expect: (Expectations<S>.() -> Unit)? = null) {
        require(!duration.isNegative()) { "Time only moves on." }
        millis += duration.inWholeMilliseconds
        var last: CommandOutcome? = null
        runBlocking {
            while (true) {
                val timer = timers.due(PARTITION, millis, Int.MAX_VALUE).firstOrNull { it.target == ref } ?: break
                val type = requireNotNull(model.findCommandType(timer.commandType)) {
                    "${model.type} has no command ${timer.commandType}, which the timer ${timer.id} sends."
                }
                val body = codec.decodeTree(WowJson.mapper.readTree(timer.body), type.type)
                val outcome = kernel.execute(
                    model,
                    Command(timer.id, timer.requestId, ref, body, causation = timer.causation, headers = timer.headers),
                )
                timers.update(timer.copy(status = TimerStatus.FIRED, attempts = 1), TimerStatus.PENDING)
                record(outcome)
                last = outcome
            }
        }
        if (expect != null) {
            val fired = last ?: throw AssertionError("No command the aggregate scheduled was due by $now.")
            Expectations(model, codec, fired).expect()
        }
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
     * Commits [events] as one stream, as if an earlier command had produced them, leaving the aggregate in [scope]
     * (by default the scope it already has).
     */
    fun given(vararg events: Any, scope: Scope? = null) {
        require(events.isNotEmpty()) { "given needs at least one event." }
        runBlocking {
            val committed = log.read(ref).toList()
            val version = committed.size + 1
            val stream = EventStreamRecord(
                ref = ref,
                version = version,
                commandId = "given-$version",
                requestId = "given-$version",
                commandType = "${model.type}.Given",
                commitTime = millis,
                events = events.map { codec.encode(model, it) },
                scope = scope ?: committed.lastOrNull()?.scope ?: Scope.NONE,
            )
            check(log.append(stream) is AppendOutcome.Committed) { "Could not commit the given events." }
        }
    }

    /** Sends [command], from a caller in [scope], through the kernel and checks its outcome in [expect]. */
    fun whenCommand(
        command: Any,
        requestId: String = "request-${++sequence}",
        expectedVersion: Int? = null,
        scope: Scope = Scope.NONE,
        expect: Expectations<S>.() -> Unit,
    ) {
        val outcome = runBlocking {
            kernel.execute(model, Command("command-${++sequence}", requestId, ref, command, expectedVersion, scope))
                .also { record(it) }
        }
        Expectations(model, codec, outcome).expect()
    }
}

class Expectations<S : Any>(
    private val model: AggregateModel<S>,
    private val codec: EventCodec,
    val outcome: CommandOutcome,
) {
    /** The command committed exactly [events], in order. */
    fun expectEvents(vararg events: Any) {
        val committed = committed().stream.events.map { codec.decode(model, it) }
        if (committed != events.toList()) {
            throw AssertionError("Expected events ${events.toList()} but got $committed.")
        }
    }

    /** The command committed events of exactly these types, in order. */
    fun expectEventTypes(vararg types: Class<*>) {
        val committed = committed().stream.events.map { codec.decode(model, it).javaClass }
        if (committed != types.toList()) {
            throw AssertionError("Expected event types ${types.map { it.simpleName }} but got ${committed.map { it.simpleName }}.")
        }
    }

    /** The command was accepted and changed nothing. */
    fun expectUnchanged() {
        if (outcome !is CommandOutcome.Unchanged) throw AssertionError("Expected no change but got $outcome.")
    }

    /** The command was rejected with [code]. */
    fun expectRejected(code: String) {
        val rejected = outcome as? CommandOutcome.Rejected ?: throw AssertionError("Expected a rejection but got $outcome.")
        if (rejected.rejection.code != code) {
            throw AssertionError("Expected rejection $code but got ${rejected.rejection}.")
        }
    }

    /**
     * The command scheduled [command] under [key] (decision D166), for [target] (the aggregate itself by default), due
     * [at] or [after] its commit when either is given.
     */
    fun expectScheduled(
        key: String,
        command: Any,
        at: Instant? = null,
        after: Duration? = null,
        target: AggregateRef? = null,
    ) {
        val stream = committed().stream
        val put = stream.schedules.filterIsInstance<ScheduleChange.Put>().firstOrNull { it.key == key }
            ?: throw AssertionError("Expected a schedule $key but got ${stream.schedules}.")
        val scheduled = codec.decodeTree(WowJson.mapper.readTree(put.body), command.javaClass)
        if (scheduled != command) {
            throw AssertionError("Expected $key to send $command but it sends $scheduled.")
        }
        val due = at?.toEpochMilli() ?: after?.let { stream.commitTime + it.inWholeMilliseconds }
        if (due != null && put.dueAt != due) {
            throw AssertionError("Expected $key due at ${Instant.ofEpochMilli(due)} but it is due at ${Instant.ofEpochMilli(put.dueAt)}.")
        }
        val expectedTarget = target ?: stream.ref
        if (put.target != expectedTarget) {
            throw AssertionError("Expected $key for $expectedTarget but it is for ${put.target}.")
        }
    }

    /** The command cancelled the schedule [key] (decision D166). */
    fun expectScheduleCancelled(key: String) {
        val schedules = committed().stream.schedules
        if (ScheduleChange.Cancel(key) !in schedules) {
            throw AssertionError("Expected the schedule $key cancelled but got $schedules.")
        }
    }

    /** The committed stream leaves the aggregate in [scope]. */
    fun expectScope(scope: Scope) {
        val committed = committed().stream.scope
        if (committed != scope) {
            throw AssertionError("Expected scope $scope but got $committed.")
        }
    }

    /** Checks the state after the committed events. */
    fun expectState(check: S.() -> Unit) {
        @Suppress("UNCHECKED_CAST")
        (committed().state as S).check()
    }

    private fun committed(): CommandOutcome.Committed =
        outcome as? CommandOutcome.Committed ?: throw AssertionError("Expected committed events but got $outcome.")
}

/** The timers of a specification live in one partition. */
private const val PARTITIONS = 1
private const val PARTITION = 0

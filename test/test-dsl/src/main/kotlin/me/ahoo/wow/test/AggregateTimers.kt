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

import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.ScheduleCancelled
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.kernel.RetryDefaults
import me.ahoo.wow.kernel.observedName
import me.ahoo.wow.kernel.schedule.ScheduleSlots
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerStatus

/**
 * The commands the aggregate [ref] of an [AggregateSpec] schedules, kept as the runtime keeps them: [record] applies
 * the schedules a commit sets or cancels, as the `timers` subscription would, and [fire] sends the due ones and
 * settles their timers, as the firing loop would with the node's default retry ([RetryDefaults]). A fork's timers
 * start on a copy of the [writes] its origin's took.
 */
internal class AggregateTimers<S : Any>(
    private val model: AggregateModel<S>,
    private val ref: AggregateRef,
    private val codec: EventCodec,
    writes: List<TimerWrite>,
) {
    private val timers = ForkableTimerStore(writes)

    /** The writes the timers took, which a fork starts from. */
    val writes: List<TimerWrite> get() = timers.writes

    /** The timer [key] of the aggregate, in whatever status. */
    fun find(key: String): Timer? = runBlocking { timers.find(ref, key) }

    /**
     * Sends each command due by [by], earliest first, and settles its timer as a node's runner does: the commands the
     * aggregate scheduled for itself with [execute], the outcome of each, in order. A failed send leaves its timer
     * PENDING, due again after the node's default backoff, and PARKED at its last attempt. A command for another
     * context's aggregate is sent there, as a node sends it to that context's topic, and decided by none here; one for
     * another aggregate of the context, which a node decides on that aggregate, is refused by name.
     */
    suspend fun fire(
        by: Long,
        execute: suspend (Command<*>) -> Result<CommandOutcome>,
    ): List<Result<CommandOutcome>> {
        val fired = ArrayList<Result<CommandOutcome>>()
        while (true) {
            val loaded = timers.due(setOf(PARTITION), by, Int.MAX_VALUE).firstOrNull() ?: break
            // A recurring slot past due by more than one time of its rule is caught up as its misfire policy says.
            val timer = ScheduleSlots.caughtUp(loaded, by)
            if (timer.target != ref) {
                timers.settle(listOf(TimerChange(loaded, sentElsewhere(timer, by))))
                continue
            }
            val type = requireNotNull(model.findCommandType(timer.commandType)) {
                "${model.type} has no command ${timer.commandType}, which the timer ${timer.id} sends."
            }
            val body = codec.decodeTree(WowJson.mapper.readTree(timer.body), type.type)
            // As a node's firer sends it: in the scope and the name of the commit that scheduled it, with the request
            // facts that commit propagates and the slot's own (ContextTimerFirer).
            val command = Command(
                timer.requestId,
                timer.requestId,
                ref,
                body,
                scope = timer.scope,
                headers = RequestFacts.propagatedOf(timer.headers) + timer.context.headers(),
                causation = timer.causation,
                operator = timer.operator,
            )
            val outcome = execute(command)
            val next = outcome.fold({ fired(timer, by, it.observedName.value) }, { failed(timer, it, by) })
            timers.settle(listOf(TimerChange(loaded, next)))
            fired += outcome
        }
        return fired
    }

    /**
     * [timer], sent at [now] with [outcome]: a recurring rule moves on to its next slot; a rule due once is done, due
     * when its slot was, as a node's runner settles a sent timer.
     */
    private fun fired(timer: Timer, now: Long, outcome: String): Timer =
        ScheduleSlots.afterFired(timer, now)?.copyWith(outcome = outcome) ?: timer.copyWith(
            status = TimerStatus.FIRED,
            attempts = timer.attempts + 1,
            outcome = outcome,
            dueAt = timer.slotDueAt,
        )

    /**
     * [timer] after one more failed send at [now], as a node's runner settles it with its default retry: PENDING
     * again, due the backoff after [now], or PARKED once its attempts are spent.
     */
    private fun failed(timer: Timer, failure: Throwable, now: Long): Timer {
        val attempts = timer.attempts + 1
        val reason = FailureClassification.DEFAULT.reasonOf(failure)
        if (attempts >= RetryDefaults.TIMER_MAX_ATTEMPTS) {
            return timer.copyWith(status = TimerStatus.PARKED, attempts = attempts, outcome = reason)
        }
        val backoff = RetryDefaults.backoff(
            RetryDefaults.TIMER_INITIAL_BACKOFF,
            RetryDefaults.TIMER_MAX_BACKOFF,
            RetryDefaults.TIMER_MULTIPLIER,
            attempts,
        )
        return timer.copyWith(
            status = TimerStatus.PENDING,
            attempts = attempts,
            outcome = reason,
            dueAt = now + backoff.inWholeMilliseconds,
        )
    }

    /**
     * [timer] of a command for another aggregate than the specification's, due at [now]: sent, as a node's firer sends
     * one for another context's aggregate to that context's topic, deciding nothing here. One for another aggregate of
     * the specification's context a node decides on that aggregate, which a specification of one aggregate cannot.
     */
    private fun sentElsewhere(timer: Timer, now: Long): Timer {
        check(timer.target.type.context != ref.type.context) {
            "The timer '${timer.key}' of $ref sends ${timer.commandType} to ${timer.target}, another aggregate of its " +
                "context, which a node decides on that aggregate: a specification of one aggregate cannot decide " +
                "another's command. Check the schedule with expectScheduled(…, target = …), and the command in a " +
                "specification of ${timer.target.type}."
        }
        return fired(timer, now, ObservedOutcome.SENT.value)
    }

    suspend fun dueTimers(by: Long): List<Timer> =
        timers.due(setOf(PARTITION), by, Int.MAX_VALUE).filter { it.target == ref }

    /** Applies the schedules [outcome] committed to the aggregate's timers, as the `timers` subscription would. */
    suspend fun record(outcome: CommandOutcome) {
        (outcome as? CommandOutcome.Committed)?.stream?.let { record(it) }
    }

    /**
     * Applies the schedules [stream] sets or cancels to the aggregate's timers, as the `timers` subscription does for
     * every stream that holds them: a command's commit, and a history a specification is given.
     */
    suspend fun record(stream: EventStreamRecord) {
        val owner = stream.ref
        if (stream.events.any { it.name == Lifecycle.AGGREGATE_DELETED }) {
            timers.cancelAll(owner, stream.version)
        }
        stream.events.filter { it.name in Schedules.EVENTS }.forEach { record ->
            when (val event = codec.decode(model, record)) {
                is ScheduleSet -> timers.put(ScheduleSlots.timerOf(stream, event, PARTITIONS))
                is ScheduleCancelled -> timers.cancel(owner, event.key, stream.version)
            }
        }
    }
}

/** The timers of a specification live in one partition. */
private const val PARTITIONS = 1
private const val PARTITION = 0

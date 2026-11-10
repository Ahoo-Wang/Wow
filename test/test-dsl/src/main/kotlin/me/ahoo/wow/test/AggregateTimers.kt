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
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.ScheduleCancelled
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandOutcome
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.schedule.ScheduleSlots
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.timer.Timer
import me.ahoo.wow.spi.timer.TimerChange
import me.ahoo.wow.spi.timer.TimerStatus
import me.ahoo.wow.spi.timer.Timers

/**
 * The commands the aggregate [ref] of an [AggregateSpec] schedules for itself, kept as the runtime keeps them:
 * [record] applies the schedules a commit sets or cancels, as the `timers` subscription would, and [fire] sends the
 * due ones, as the firing loop would. A fork's timers start on a copy of the [writes] its origin's took.
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
    fun find(key: String): Timer? = runBlocking { timers.find(Timers.ownerOf(ref), key) }

    /**
     * Sends the aggregate each command it scheduled for itself that is due by [by], earliest first, with [execute],
     * and settles its timer once it is sent: the outcome of each, in order.
     */
    suspend fun fire(
        by: Long,
        execute: suspend (Command<*>) -> Result<CommandOutcome>,
    ): List<Result<CommandOutcome>> {
        val fired = ArrayList<Result<CommandOutcome>>()
        while (true) {
            val loaded = dueTimers(by).firstOrNull() ?: break
            // A recurring slot past due by more than one time of its rule is caught up as its misfire policy says.
            val timer = ScheduleSlots.caughtUp(loaded, by)
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
            val next = ScheduleSlots.afterFired(timer, by) ?: timer.copyWith(status = TimerStatus.FIRED, attempts = 1)
            timers.settle(listOf(TimerChange(loaded, next)))
            fired += outcome
        }
        return fired
    }

    suspend fun dueTimers(by: Long): List<Timer> =
        timers.due(setOf(PARTITION), by, Int.MAX_VALUE).filter { it.target == ref }

    /** Applies the schedules [outcome] committed to the aggregate's timers, as the `timers` subscription would. */
    suspend fun record(outcome: CommandOutcome) {
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
}

/** The timers of a specification live in one partition. */
private const val PARTITIONS = 1
private const val PARTITION = 0

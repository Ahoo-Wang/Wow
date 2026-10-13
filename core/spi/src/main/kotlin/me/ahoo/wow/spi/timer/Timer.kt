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

package me.ahoo.wow.spi.timer

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.ScheduleChange

/**
 * One scheduled command (decision D166): send the command of type [commandType], [body] its JSON, to [target] at
 * [dueAt] (epoch milliseconds).
 *
 * - [id] is `ref#key@version` for an aggregate's timer and `executionId#n` for a saga's ([Timers]); it is also the
 *   request id the command is sent with, so sending it again is deduplicated by its target.
 * - [owner] and [key] name the timer: an aggregate's ref, or a saga and its target ([Timers.sagaOwner]); [version]
 *   orders the owner's changes ([TimerStore]).
 * - [partition] is the partition of the [target] among the scheduling context's log partitions: the owner of that
 *   partition fires the timer, so a target of the same context runs where its aggregate's commands run.
 * - [causation] links the command to the commit that scheduled it; [headers] and [operator] are the scheduling
 *   command's, which the command inherits (decisions D163, D166).
 * - [status], [attempts] and [outcome] record what the runner did: [outcome] is what the target answered
 *   ([TimerOutcomes]), or why a timer is parked.
 */
data class Timer(
    val id: String,
    val owner: String,
    val key: String,
    val version: Long,
    val partition: Int,
    val dueAt: Long,
    val target: AggregateRef,
    val commandType: String,
    val body: String,
    val causation: Causation? = null,
    val headers: Map<String, String> = emptyMap(),
    val operator: Operator? = null,
    val status: TimerStatus = TimerStatus.PENDING,
    val attempts: Int = 0,
    val outcome: String? = null,
) {
    init {
        require(id.isNotBlank()) { "A timer needs an id." }
        require(owner.isNotBlank()) { "A timer needs an owner." }
        require(key.isNotBlank()) { "A timer needs a key." }
        require(partition >= 0) { "A partition is never negative." }
        require(commandType.isNotBlank()) { "A timer names the type of its command." }
        require(attempts >= 0) { "A timer's attempts are never negative." }
    }

    /** The request id the command is sent with: the timer's [id]. */
    val requestId: String get() = id
}

/** Where a [Timer] is. */
enum class TimerStatus {
    /** Waiting to be sent, or being retried after a technical failure. */
    PENDING,

    /** Sent, and settled by its target: committed, already committed, unchanged or rejected ([Timer.outcome]). */
    FIRED,

    /** Cancelled or rescheduled by its owner, its owner deleted, expired, or skipped by an operator. */
    CANCELLED,

    /** Failed technically past its attempts; parked under `wow.timers` until an operator resumes or skips it. */
    PARKED,
}

/** The [Timer.outcome]s the runtime records. */
object TimerOutcomes {
    const val COMMITTED = "COMMITTED"
    const val ALREADY_COMMITTED = "ALREADY_COMMITTED"
    const val UNCHANGED = "UNCHANGED"

    /** The target refused the command: a normal state guard, not retried. */
    const val REJECTED = "REJECTED"

    /** Sent to another context's command topic: its broker acknowledged it (decision D33). */
    const val SENT = "SENT"
    const val EXPIRED = "EXPIRED"
    const val SKIPPED = "SKIPPED"
}

/** How timers are named and derived from the log (decision D166). */
object Timers {
    /** The id of the timer [ref] scheduled under [key] in its stream at [version]: `ref#key@version`. */
    @JvmStatic
    fun idOf(ref: AggregateRef, key: String, version: Int): String = "$ref#$key@$version"

    /** The owner of an aggregate's timers: the aggregate. */
    @JvmStatic
    fun ownerOf(ref: AggregateRef): String = ref.toString()

    /** The owner of the timers the saga [subscription] keeps of [target]: `subscription>target`. */
    @JvmStatic
    fun sagaOwner(subscription: String, target: AggregateRef): String = "$subscription>$target"

    /** The id of the [index]-th schedule (from 0) of the saga's [execution]: `executionId#index`. */
    @JvmStatic
    fun sagaTimerId(execution: ExecutionId, index: Int): String {
        require(index >= 0) { "A call index is never negative." }
        return "$execution#$index"
    }

    /**
     * The timer [put] of [stream] schedules, in [partitions] log partitions: its causation is the commit (`ref@version`)
     * in the chain of the command that committed it.
     */
    @JvmStatic
    fun of(stream: EventStreamRecord, put: ScheduleChange.Put, partitions: Int): Timer = Timer(
        id = idOf(stream.ref, put.key, stream.version),
        owner = ownerOf(stream.ref),
        key = put.key,
        version = stream.version.toLong(),
        partition = Partitioner.partitionOf(put.target, partitions),
        dueAt = put.dueAt,
        target = put.target,
        commandType = put.commandType,
        body = put.body,
        causation = Causation(stream.causation?.correlationId ?: stream.commandId, "${stream.ref}@${stream.version}"),
        headers = stream.headers,
        operator = stream.operator,
    )
}

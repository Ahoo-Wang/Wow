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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.schedule.ScheduleContext
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner

/**
 * One schedule of an aggregate (decisions D166, D198): send the command of type [commandType], [body] its JSON, to
 * [target] each time [rule] says, the next time [dueAt] (epoch milliseconds), for its [slot], due at [slotDueAt].
 *
 * - [id] is `ref#key@version`, the schedule as the owner's stream at [version] set it ([Timers.idOf]); the command of
 *   each slot is sent with the request id `id#slot` ([requestId]), so sending it again is deduplicated by its target.
 * - [owner] and [key] name the schedule: the aggregate that set it ([Timers.ownerOf]), compared field by field, never
 *   by its joined name, so two aggregates whose tenant and id join alike keep their timers apart (finding A2-12);
 *   [version] orders the owner's changes ([TimerStore]).
 * - [slot] counts the times the schedule is due, from 1: firing a slot marks it fired and moves [dueAt] to the next
 *   one in one write; a rule due once has one slot, after which the timer is FIRED. A recurring rule's slot is the
 *   number of its times since it was set, so slots it [skipped] (missed and coalesced, [me.ahoo.wow.api.schedule.Misfire])
 *   are counted too: [skipped] is how many slots before this one were not sent.
 * - [slotDueAt] is when the current [slot] is due, as its rule said; [dueAt] is when the firing loop next looks at the
 *   timer: the slot's due time at first, then the claim's deadline while it is sent, or the next attempt after a
 *   technical failure. Every send of the slot, retried or claimed anew, carries [slotDueAt] ([context]), so its
 *   target never decides it before then; it moves only with the slot.
 * - [partition] is the partition of the [target] among the scheduling context's log partitions: the owner of that
 *   partition fires the timer, so a target of the same context runs where its aggregate's commands run.
 * - [causation] links the command to the commit that set the schedule; [headers] and [operator] are the setting
 *   command's, which the command inherits (decisions D163, D166), and [scope] the setting commit's, the owner and space
 *   the command is sent in (decision D189).
 * - [status], [attempts] and [outcome] record what the firing loop did: [outcome] is what the target answered
 *   ([TimerOutcomes]), or why a timer is parked.
 */
data class Timer(
    val id: String,
    val owner: AggregateRef,
    val key: String,
    val version: Long,
    val partition: Int,
    val rule: ScheduleRule,
    val dueAt: Long,
    val target: AggregateRef,
    val commandType: String,
    val body: String,
    val slot: Long = 1,
    val causation: Causation? = null,
    val headers: Map<String, String> = emptyMap(),
    val operator: Operator? = null,
    val status: TimerStatus = TimerStatus.PENDING,
    val attempts: Int = 0,
    val outcome: String? = null,
    val scope: Scope = Scope.NONE,
    val slotDueAt: Long = dueAt,
    val skipped: Long = 0,
) {
    init {
        require(id.isNotBlank()) { "A timer needs an id." }
        require(key.isNotBlank()) { "A timer needs a key." }
        require(partition >= 0) { "A partition is never negative." }
        require(commandType.isNotBlank()) { "A timer names the type of its command." }
        require(slot >= 1) { "A timer's slots count from 1." }
        require(attempts >= 0) { "A timer's attempts are never negative." }
        require(skipped in 0 until slot) { "A slot skips only the slots before it." }
    }

    /** The request id the command of the current [slot] is sent with: `id#slot`. */
    val requestId: String get() = "$id#$slot"

    /**
     * Which time of the schedule the command of the current [slot] is sent for, as its target reads it: due at
     * [slotDueAt], however many attempts or claims it took.
     */
    val context: ScheduleContext get() = ScheduleContext(id, key, slot, slotDueAt, skipped)
}

/** Where a [Timer] is. */
enum class TimerStatus {
    /** Waiting to be sent, or being retried after a technical failure. */
    PENDING,

    /**
     * Claimed by the owner of its partition, which is sending its slot (decision D198): a conditional write from the
     * PENDING row as loaded, so a timer cancelled or rescheduled after it was loaded is never sent. [Timer.dueAt] is
     * then the claim's deadline: a claim left by a node that died is due again once it lapses, and the next owner
     * claims it anew.
     */
    FIRING,

    /** Its last slot is sent, and settled by its target: committed, already committed, unchanged or rejected. */
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

/** How timers are named and derived from the log (decisions D166, D198). */
object Timers {
    /** The id of the schedule [ref] set under [key] in its stream at [version]: `ref#key@version`. */
    @JvmStatic
    fun idOf(ref: AggregateRef, key: String, version: Int): String = "$ref#$key@$version"

    /** The owner of an aggregate's timers: the aggregate. */
    @JvmStatic
    fun ownerOf(ref: AggregateRef): AggregateRef = ref

    /**
     * The timer [set] makes, an event of [stream], in [partitions] log partitions: its first slot due as its rule says
     * counted from the commit; a rule the runtime evaluates (cron) is [NOT_EVALUATED] here, and the runtime gives it
     * its first slot before it writes it. Its causation is the commit (`ref@version`) in the chain of the command that
     * committed it.
     */
    @JvmStatic
    fun of(stream: EventStreamRecord, set: ScheduleSet, partitions: Int): Timer = Timer(
        id = idOf(stream.ref, set.key, stream.version),
        owner = ownerOf(stream.ref),
        key = set.key,
        version = stream.version.toLong(),
        partition = Partitioner.partitionOf(set.target, partitions),
        rule = set.rule,
        dueAt = set.rule.firstDue(stream.commitTime) ?: NOT_EVALUATED,
        target = set.target,
        commandType = set.commandType,
        body = set.command,
        causation = Causation(stream.causation?.correlationId ?: stream.commandId, "${stream.ref}@${stream.version}"),
        headers = stream.headers - ScheduleContext.HEADERS,
        operator = stream.operator,
        scope = stream.scope,
    )

    /** The due time of a timer whose rule this module does not evaluate (cron): never, until the runtime does. */
    const val NOT_EVALUATED: Long = Long.MAX_VALUE
}

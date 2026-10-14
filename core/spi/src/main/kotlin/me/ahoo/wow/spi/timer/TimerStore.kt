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

/**
 * The timers of one bounded context's schedules (decisions D166, D198): a view derived from the log, rebuilt by
 * replaying it, never the source of truth. The built-in `timers` subscription writes them from the system events
 * `wow.ScheduleSet` and `wow.ScheduleCancelled` the aggregates commit. The node's firing loop reads the timers [due]
 * in the partitions it owns, one query for all of them, sends their commands and records each outcome with [settle].
 *
 * - **Key**: a timer is unique by its owner and key ([Timer.owner], [Timer.key]); its [Timer.id] changes with each
 *   reschedule, so a write of the same timer again is recognised, and a change of an old one does nothing.
 * - **Order**: each change carries the owner's [Timer.version]. A change older than what the store holds for the key
 *   does nothing, so a replay, or an old partition owner running beside the new one (decision D45), never moves a
 *   timer back nor revives one that fired or was cancelled.
 * - **Conditional writes**: [settle] writes a change only while the store holds the timer as it was loaded (the same
 *   [Timer.id], [Timer.status], [Timer.slot] and [Timer.attempts]); a cancel, a reschedule or another node's write
 *   that won the race leaves it as it is. Firing a slot marks it fired and moves [Timer.dueAt] to the next slot in that
 *   one write.
 */
interface TimerStore {
    /**
     * Writes [timer], PENDING, unless the store holds its owner's key at a later version, or holds this very timer
     * ([Timer.id]) in whatever status: writing a timer again is idempotent and never revives it.
     *
     * @return true when the store changed.
     */
    suspend fun put(timer: Timer): Boolean

    /**
     * Cancels the timer [owner] keeps under [key] when it is PENDING (or PARKED) and the store holds it at [version] or
     * earlier; nothing otherwise.
     *
     * @return true when a timer was cancelled.
     */
    suspend fun cancel(owner: String, key: String, version: Long): Boolean

    /**
     * Cancels every PENDING (or PARKED) timer of [owner] held at [version] or earlier: the owner aggregate was deleted.
     *
     * @return how many timers were cancelled.
     */
    suspend fun cancelAll(owner: String, version: Long): Int

    /** The timer [owner] keeps under [key], in whatever status, or null. */
    suspend fun find(owner: String, key: String): Timer?

    /**
     * Up to [limit] PENDING timers of any of [partitions] due at or before [until] (epoch milliseconds), earliest first;
     * ties by id. One query for every partition the caller owns.
     */
    suspend fun due(partitions: Set<Int>, until: Long, limit: Int): List<Timer>

    /**
     * Writes each change's [TimerChange.next] status, slot, due time, attempts and outcome, when the store still holds
     * its [TimerChange.loaded] timer as loaded (same id, status, slot and attempts); in as few round trips as the
     * backend allows.
     *
     * @return how many changes were written.
     */
    suspend fun settle(changes: List<TimerChange>): Int

    /** The PARKED timers whose target is [target], earliest due first. */
    suspend fun parked(target: AggregateRef): List<Timer>
}

/**
 * A change of the timer [loaded] from the store to [next] (decision D198): its next status, slot, due time, attempts or
 * outcome. It applies only while the store holds [loaded] as loaded ([TimerStore.settle]).
 */
data class TimerChange(val loaded: Timer, val next: Timer) {
    init {
        require(next.id == loaded.id && next.owner == loaded.owner && next.key == loaded.key) {
            "A change keeps its timer: ${loaded.id} cannot become ${next.id}."
        }
    }
}
